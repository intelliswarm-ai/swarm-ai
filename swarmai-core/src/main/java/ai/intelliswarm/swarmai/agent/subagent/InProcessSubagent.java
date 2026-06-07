package ai.intelliswarm.swarmai.agent.subagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-process {@link Subagent} implementation: a single Spring AI
 * {@link ChatClient} call, optionally with tools, executed asynchronously on
 * a supplied {@link ExecutorService}. Lighter than wrapping the full
 * {@link ai.intelliswarm.swarmai.agent.Agent} class — meant for delegated
 * tasks where the parent doesn't need the parent agent's full hook /
 * memory / observability machinery.
 *
 * <p>Tool calling: Spring AI's {@code .toolNames(...)} on the prompt builder
 * lets the model call any registered tool by name. Tool execution loops
 * (model returns tool call → harness executes → model continues) are
 * handled by Spring AI itself; we don't write the loop here.
 *
 * <p>Workspace lifecycle: when the spec carries a
 * {@link SubagentSpec#workspaceFactory()}, the spawner has already
 * materialised the workspace and stored it on the subagent — we only ensure
 * it's closed in the {@code finally} of the run loop.
 */
public final class InProcessSubagent implements Subagent {

    private static final Logger log = LoggerFactory.getLogger(InProcessSubagent.class);

    private final SubagentSpec spec;
    private final ChatClient chatClient;
    private final ExecutorService executor;
    private final IsolatedWorkspace workspace; // may be null
    private final long startedAtMs;

    private final AtomicReference<SubagentStatus> status = new AtomicReference<>(SubagentStatus.PENDING);
    private final CompletableFuture<SubagentResult> resultFuture = new CompletableFuture<>();
    private volatile java.util.concurrent.Future<?> workFuture;

    public InProcessSubagent(SubagentSpec spec,
                              ChatClient chatClient,
                              ExecutorService executor,
                              IsolatedWorkspace workspace) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.workspace = workspace; // optional
        this.startedAtMs = System.currentTimeMillis();
    }

    /**
     * Submit the subagent's run loop to the executor. Idempotent — repeated
     * calls after a successful submit are no-ops.
     */
    public void start() {
        if (!status.compareAndSet(SubagentStatus.PENDING, SubagentStatus.RUNNING)) {
            return; // already running or terminal
        }
        workFuture = executor.submit(this::run);
    }

    private void run() {
        long t0 = System.currentTimeMillis();
        SubagentResult pendingResult = null;
        SubagentStatus pendingStatus = null;
        try {
            var requestBuilder = chatClient.prompt()
                    .system(spec.systemPrompt())
                    .user(spec.userPrompt());
            if (!spec.allowedToolNames().isEmpty()) {
                requestBuilder = requestBuilder.toolNames(
                        spec.allowedToolNames().toArray(new String[0]));
            }
            String output = requestBuilder.call().content();
            if (output == null) output = "";

            long durationMs = System.currentTimeMillis() - t0;
            pendingResult = SubagentResult.success(spec.id(), output, baseMetrics(durationMs));
            pendingStatus = SubagentStatus.COMPLETED;
        } catch (Throwable t) {
            long durationMs = System.currentTimeMillis() - t0;
            log.warn("subagent {} failed: {}", spec.id(), t.getMessage());
            pendingResult = SubagentResult.failure(spec.id(),
                    t.getClass().getSimpleName() + ": " + t.getMessage(),
                    withErrorClass(baseMetrics(durationMs), t));
            pendingStatus = SubagentStatus.FAILED;
        } finally {
            // Reap workspace BEFORE completing the future so callers waking up on
            // awaitResult() see a fully cleaned-up state.
            closeWorkspaceQuietly();
        }
        if (status.compareAndSet(SubagentStatus.RUNNING, pendingStatus)) {
            resultFuture.complete(pendingResult);
        }
    }

    @Override
    public String id() {
        return spec.id();
    }

    @Override
    public SubagentStatus status() {
        return status.get();
    }

    @Override
    public SubagentResult awaitResult(Duration waitUntil) {
        Objects.requireNonNull(waitUntil, "waitUntil");

        long waitMillis = effectiveWaitMillis(waitUntil);
        try {
            if (waitMillis == 0L) {
                return resultFuture.get(); // wait indefinitely
            }
            return resultFuture.get(waitMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return finalizeAsTimeout();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return finalizeAsCancelled();
        } catch (ExecutionException e) {
            // The run loop catches its own exceptions and completes normally,
            // so this branch only fires for unexpected runtime errors.
            return finalizeAsFailure(e.getCause() != null ? e.getCause() : e);
        }
    }

    @Override
    public void cancel() {
        if (!status.compareAndSet(SubagentStatus.RUNNING, SubagentStatus.CANCELLED)
                && !status.compareAndSet(SubagentStatus.PENDING, SubagentStatus.CANCELLED)) {
            return; // already terminal
        }
        if (workFuture != null) workFuture.cancel(true);
        // Close workspace BEFORE completing the future so awaiters see the
        // cleaned-up state.
        closeWorkspaceQuietly();
        Map<String, Object> metrics = baseMetrics(System.currentTimeMillis() - startedAtMs);
        resultFuture.complete(SubagentResult.cancelled(spec.id(), metrics));
    }

    private SubagentResult finalizeAsTimeout() {
        if (status.compareAndSet(SubagentStatus.RUNNING, SubagentStatus.TIMED_OUT)
                || status.compareAndSet(SubagentStatus.PENDING, SubagentStatus.TIMED_OUT)) {
            if (workFuture != null) workFuture.cancel(true);
            closeWorkspaceQuietly();
            Map<String, Object> metrics = baseMetrics(System.currentTimeMillis() - startedAtMs);
            SubagentResult timed = SubagentResult.timedOut(spec.id(), metrics);
            resultFuture.complete(timed);
            return timed;
        }
        // Race: terminated while we were timing out — return the actual result.
        return resultFuture.getNow(SubagentResult.timedOut(spec.id(),
                baseMetrics(System.currentTimeMillis() - startedAtMs)));
    }

    private SubagentResult finalizeAsCancelled() {
        cancel();
        return resultFuture.getNow(SubagentResult.cancelled(spec.id(),
                baseMetrics(System.currentTimeMillis() - startedAtMs)));
    }

    private SubagentResult finalizeAsFailure(Throwable t) {
        if (status.compareAndSet(SubagentStatus.RUNNING, SubagentStatus.FAILED)
                || status.compareAndSet(SubagentStatus.PENDING, SubagentStatus.FAILED)) {
            closeWorkspaceQuietly();
            Map<String, Object> metrics = withErrorClass(
                    baseMetrics(System.currentTimeMillis() - startedAtMs), t);
            SubagentResult failed = SubagentResult.failure(spec.id(),
                    t.getClass().getSimpleName() + ": " + t.getMessage(), metrics);
            resultFuture.complete(failed);
            return failed;
        }
        return resultFuture.getNow(SubagentResult.failure(spec.id(), t.getMessage(),
                baseMetrics(System.currentTimeMillis() - startedAtMs)));
    }

    private long effectiveWaitMillis(Duration waitUntil) {
        long callerWait = waitUntil.isZero() ? Long.MAX_VALUE : waitUntil.toMillis();
        long specWait = spec.timeout().isZero() ? Long.MAX_VALUE : spec.timeout().toMillis();
        long min = Math.min(callerWait, specWait);
        return min == Long.MAX_VALUE ? 0L : Math.max(1L, min);
    }

    private Map<String, Object> baseMetrics(long durationMs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("durationMs", durationMs);
        m.put("type", spec.type());
        m.put("toolCount", spec.allowedToolNames().size());
        if (workspace != null) m.put("workspace", workspace.root().toString());
        return m;
    }

    private static Map<String, Object> withErrorClass(Map<String, Object> base, Throwable t) {
        Map<String, Object> next = new LinkedHashMap<>(base);
        next.put("errorClass", t.getClass().getName());
        return next;
    }

    private void closeWorkspaceQuietly() {
        if (workspace == null) return;
        try {
            workspace.close();
        } catch (RuntimeException e) {
            log.warn("workspace close failed for subagent {}: {}", spec.id(), e.getMessage());
        }
    }
}
