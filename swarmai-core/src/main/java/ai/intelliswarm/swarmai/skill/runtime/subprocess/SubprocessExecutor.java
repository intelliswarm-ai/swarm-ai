package ai.intelliswarm.swarmai.skill.runtime.subprocess;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OS process lifecycle wrapper used by subprocess-based SkillRuntimes.
 *
 * Enforces wall-clock timeout with destroy-tree, caps stdout/stderr capture so a
 * runaway child cannot exhaust heap, and clears the environment by default so
 * inherited credentials do not leak into generated skills.
 *
 * This class is intentionally runtime-agnostic — it does not know about Python,
 * MCP, or SkillRuntime. Higher-level runtimes compose it.
 */
public final class SubprocessExecutor {

    private static final Logger logger = LoggerFactory.getLogger(SubprocessExecutor.class);

    private final ExecutorService streamReader = Executors.newCachedThreadPool(new DaemonFactory("skill-subprocess-io"));
    private final Set<Process> liveProcesses = ConcurrentHashMap.newKeySet();

    public SubprocessExecutor() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::destroyAllLive, "skill-subprocess-shutdown"));
    }

    public SubprocessResult execute(SubprocessRequest request) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(request.command());
        if (request.workingDirectory() != null) {
            pb.directory(request.workingDirectory().toFile());
        }
        pb.environment().clear();
        pb.environment().putAll(request.environment());
        pb.redirectErrorStream(false);

        Instant start = Instant.now();
        Process process = pb.start();
        liveProcesses.add(process);

        Future<byte[]> stdoutFuture = streamReader.submit(() -> drain(process.getInputStream(), request.stdoutCapBytes(), process));
        Future<byte[]> stderrFuture = streamReader.submit(() -> drain(process.getErrorStream(), request.stderrCapBytes(), null));

        if (request.stdin() != null) {
            try (OutputStream out = process.getOutputStream()) {
                out.write(request.stdin().getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                // Process may have exited before we finish writing — swallow and let waitFor decide.
                logger.debug("stdin write to subprocess failed: {}", e.getMessage());
            }
        } else {
            try { process.getOutputStream().close(); } catch (IOException ignore) { /* child already closed */ }
        }

        boolean exited;
        try {
            exited = process.waitFor(request.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            destroyTree(process);
            Thread.currentThread().interrupt();
            throw new IOException("Subprocess execution interrupted", e);
        }

        boolean timedOut = !exited;
        if (timedOut) {
            logger.warn("Subprocess {} exceeded timeout {}ms — destroying process tree",
                request.command().get(0), request.timeout().toMillis());
            destroyTree(process);
            try { process.waitFor(500, TimeUnit.MILLISECONDS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }

        int exitCode = process.exitValue();
        byte[] stdoutBytes = getQuietly(stdoutFuture);
        byte[] stderrBytes = getQuietly(stderrFuture);
        liveProcesses.remove(process);

        String stdout = new String(stdoutBytes, StandardCharsets.UTF_8);
        String stderr = new String(stderrBytes, StandardCharsets.UTF_8);
        boolean stdoutTruncated = stdoutBytes.length >= request.stdoutCapBytes();
        boolean stderrTruncated = stderrBytes.length >= request.stderrCapBytes();

        return new SubprocessResult(
            exitCode, stdout, stderr,
            Duration.between(start, Instant.now()),
            timedOut, stdoutTruncated, stderrTruncated);
    }

    public void shutdown() {
        destroyAllLive();
        streamReader.shutdownNow();
    }

    private void destroyAllLive() {
        for (Process p : liveProcesses) {
            destroyTree(p);
        }
    }

    private static void destroyTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static byte[] drain(InputStream in, long cap, Process killIfExceeded) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        long read = 0;
        try (in) {
            int n;
            while ((n = in.read(chunk)) != -1) {
                long remaining = cap - read;
                if (remaining <= 0) {
                    if (killIfExceeded != null) destroyTree(killIfExceeded);
                    break;
                }
                int toWrite = (int) Math.min(n, remaining);
                buf.write(chunk, 0, toWrite);
                read += toWrite;
                if (read >= cap) {
                    if (killIfExceeded != null) destroyTree(killIfExceeded);
                    break;
                }
            }
        } catch (IOException e) {
            logger.debug("Subprocess stream drain ended: {}", e.getMessage());
        }
        return buf.toByteArray();
    }

    private static byte[] getQuietly(Future<byte[]> future) {
        try {
            return future.get(1, TimeUnit.SECONDS);
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static final class DaemonFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger();

        DaemonFactory(String prefix) { this.prefix = prefix; }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}
