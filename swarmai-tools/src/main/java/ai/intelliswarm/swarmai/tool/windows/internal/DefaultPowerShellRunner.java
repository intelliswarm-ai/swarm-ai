package ai.intelliswarm.swarmai.tool.windows.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Real {@link PowerShellRunner} that shells out to {@code powershell.exe}
 * (or, if {@code pwsh} is on PATH and configured, PowerShell Core).
 *
 * <p>Always invoked with {@code -NoProfile -NonInteractive -Command} so that
 * user profile scripts cannot interfere and the process never blocks waiting
 * on stdin. The {@code command} string is passed as a single PowerShell
 * statement — callers are responsible for quoting their arguments using
 * PowerShell escaping rules.
 */
public class DefaultPowerShellRunner implements PowerShellRunner {

    private final String executable;

    public DefaultPowerShellRunner() {
        this("powershell.exe");
    }

    public DefaultPowerShellRunner(String executable) {
        this.executable = Objects.requireNonNull(executable, "executable");
    }

    @Override
    public Result run(String command, Duration timeout) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(timeout, "timeout");

        ProcessBuilder pb = new ProcessBuilder(List.of(
                executable, "-NoProfile", "-NonInteractive", "-Command", command));
        pb.redirectErrorStream(false);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new PowerShellException(
                    "Failed to start PowerShell ('" + executable + "'): " + e.getMessage(), e);
        }

        try {
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new PowerShellException(
                        "PowerShell command timed out after " + timeout + ": " + summarise(command));
            }
            String stdout = readAll(process.getInputStream());
            String stderr = readAll(process.getErrorStream());
            return new Result(process.exitValue(), stdout, stderr);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new PowerShellException("Interrupted while waiting for PowerShell", e);
        }
    }

    private static String readAll(InputStream in) {
        try {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String summarise(String command) {
        if (command.length() <= 120) return command;
        return command.substring(0, 117) + "...";
    }
}
