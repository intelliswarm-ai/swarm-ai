package ai.intelliswarm.swarmai.agent.subagent.coding;

import ai.intelliswarm.swarmai.agent.subagent.IsolatedWorkspace;
import ai.intelliswarm.swarmai.agent.subagent.Subagent;
import ai.intelliswarm.swarmai.agent.subagent.SubagentResult;
import ai.intelliswarm.swarmai.agent.subagent.SubagentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link Subagent} backed by an OS process. Reads stdout/stderr concurrently
 * (so the process can't deadlock on a full pipe buffer), waits up to the
 * spec's timeout, and produces a {@link SubagentResult} via the supplied
 * {@link SubagentOutputParser}.
 *
 * <h2>Critical race fix</h2>
 * The workspace is closed <em>before</em> the result future completes. A waiter
 * that gets the result and immediately tries to read the workspace path would
 * otherwise race against cleanup. This matches the convention established by
 * the in-process subagent.
 *
 * <h2>Cancellation</h2>
 * {@link #cancel()} sets status to CANCELLED and {@code destroyForcibly()}s the
 * process. The driver loop, which is in {@code Process.waitFor()}, observes
 * the destroyed process exiting and finalises with cancelled status.
 */
public final class ProcessSubagent implements Subagent {

    private static final Logger logger = LoggerFactory.getLogger(ProcessSubagent.class);

    private final String id;
    private final Process process;
    private final IsolatedWorkspace workspace;
    private final Duration timeout;
    private final SubagentOutputParser parser;

    private volatile SubagentStatus status = SubagentStatus.PENDING;
    private final CompletableFuture<SubagentResult> resultFuture = new CompletableFuture<>();
    private final Instant startedAt = Instant.now();

    public ProcessSubagent(String id,
                            Process process,
                            IsolatedWorkspace workspace,
                            Duration timeout,
                            ExecutorService executor,
                            SubagentOutputParser parser) {
        this.id = Objects.requireNonNull(id, "id");
        this.process = Objects.requireNonNull(process, "process");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.parser = Objects.requireNonNull(parser, "parser");
        Objects.requireNonNull(executor, "executor");

        this.status = SubagentStatus.RUNNING;
        executor.submit(() -> driveProcess(executor));
    }

    @Override
    public String id() { return id; }

    @Override
    public SubagentStatus status() { return status; }

    @Override
    public SubagentResult awaitResult(Duration cap) {
        Objects.requireNonNull(cap, "cap");
        try {
            if (cap.isZero()) {
                // Caller wants "wait indefinitely subject to spec timeout"
                return resultFuture.get();
            }
            return resultFuture.get(cap.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Caller's wait elapsed; cancel the subagent and report TIMED_OUT
            cancel();
            return SubagentResult.timedOut(id, Map.of("waitMs", cap.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancel();
            return SubagentResult.cancelled(id, Map.of("reason", "awaitResult interrupted"));
        } catch (java.util.concurrent.ExecutionException e) {
            // driveProcess always completes the future normally, so this is a defensive net.
            return SubagentResult.failure(id,
                    "internal error: " + (e.getCause() == null ? e.getMessage() : e.getCause().getMessage()),
                    Map.of());
        }
    }

    @Override
    public void cancel() {
        if (status.isTerminal()) return;
        status = SubagentStatus.CANCELLED;
        process.destroyForcibly();
    }

    /** True if the cancel happened (was running); for tests + diagnostics. */
    boolean wasCancelled() {
        return status == SubagentStatus.CANCELLED;
    }

    /** The driver loop — reads streams, waits for exit, finalises. */
    private void driveProcess(ExecutorService executor) {
        // Read both streams concurrently to avoid pipe-buffer deadlock.
        CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(() -> {
            try (var s = process.getInputStream()) {
                return new String(s.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, executor);
        CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(() -> {
            try (var s = process.getErrorStream()) {
                return new String(s.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, executor);

        boolean exitedInTime;
        try {
            exitedInTime = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            finalizeAsCancelled("interrupted while waiting for process");
            return;
        }

        // Cancellation may have flipped status while we were waiting.
        if (status == SubagentStatus.CANCELLED) {
            finalizeAsCancelled("cancelled by caller");
            return;
        }

        if (!exitedInTime) {
            process.destroyForcibly();
            finalizeAsTimeout();
            return;
        }

        // Read whatever was captured up to exit; cap stream reads so a
        // misbehaving CLI can't block us forever.
        String stdout = readSafely(stdoutFuture);
        String stderr = readSafely(stderrFuture);
        int exit = process.exitValue();
        Duration elapsed = Duration.between(startedAt, Instant.now());

        ProcessOutput output = new ProcessOutput(exit, stdout, stderr, elapsed);
        SubagentResult result;
        try {
            result = parser.parse(id, output);
        } catch (RuntimeException e) {
            logger.warn("output parser threw for subagent {}: {}", id, e.getMessage());
            result = SubagentResult.failure(id,
                    "output parser threw: " + e.getMessage(),
                    Map.of("exitCode", exit, "stdoutBytes", stdout.length()));
        }

        status = result.status();

        // Race fix: close workspace BEFORE completing the future so callers
        // that immediately query workspace state see it already gone.
        try {
            workspace.close();
        } catch (RuntimeException e) {
            logger.debug("workspace close threw: {}", e.getMessage());
        }
        resultFuture.complete(result);
    }

    private void finalizeAsTimeout() {
        // Wait briefly for destroyForcibly to settle, then close + complete.
        try { process.waitFor(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        status = SubagentStatus.TIMED_OUT;
        try { workspace.close(); } catch (RuntimeException ignored) {}
        resultFuture.complete(SubagentResult.timedOut(id,
                Map.of("timeoutMs", timeout.toMillis())));
    }

    private void finalizeAsCancelled(String reason) {
        try { process.waitFor(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        status = SubagentStatus.CANCELLED;
        try { workspace.close(); } catch (RuntimeException ignored) {}
        resultFuture.complete(SubagentResult.cancelled(id, Map.of("reason", reason)));
    }

    private static String readSafely(CompletableFuture<String> f) {
        try {
            return f.get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "";
        }
    }
}
