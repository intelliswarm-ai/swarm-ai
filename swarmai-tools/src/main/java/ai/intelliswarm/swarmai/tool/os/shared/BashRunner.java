package ai.intelliswarm.swarmai.tool.os.shared;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * POSIX command runner — used on macOS and Linux for {@code ps}, {@code kill},
 * {@code open}, {@code osascript}, {@code wmctrl}, {@code screencapture}, and so on.
 *
 * <p>Stays deliberately thin: just exec + capture stdout/stderr + honor timeout. No
 * shell expansion (each {@code argv} element is passed as a separate process arg) so
 * tools don't accidentally inherit shell-injection risk by passing user input as a
 * single command string.
 */
public class BashRunner implements CommandRunner {

    private static final Logger log = LoggerFactory.getLogger(BashRunner.class);

    @Override
    public Result run(List<String> argv, Duration timeout) {
        if (argv == null || argv.isEmpty()) {
            throw new IllegalArgumentException("argv must not be empty");
        }
        ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(false);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            log.warn("BashRunner could not start {}: {}", argv.get(0), e.getMessage());
            return new Result(-1, "", "could not start: " + e.getMessage(), false);
        }
        long timeoutMs = timeout == null ? 30_000 : Math.max(1, timeout.toMillis());
        boolean done;
        try {
            done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            return new Result(-1, "", "interrupted", false);
        }
        if (!done) {
            p.destroyForcibly();
            return new Result(-1, "", "timed out after " + timeoutMs + "ms", true);
        }
        String stdout = readAll(p.getInputStream());
        String stderr = readAll(p.getErrorStream());
        return new Result(p.exitValue(), stdout, stderr, false);
    }

    private static String readAll(java.io.InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
