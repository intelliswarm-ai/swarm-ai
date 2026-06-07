package ai.intelliswarm.swarmai.tool.windows.internal;

import java.time.Duration;

/**
 * Thin abstraction over invoking PowerShell for the Windows tools.
 * The default implementation shells out to {@code powershell.exe}; tests
 * substitute a mock so they can run on any OS without spawning processes.
 */
public interface PowerShellRunner {

    /**
     * Run a single PowerShell command (passed as the {@code -Command} argument).
     * Returns the captured exit code, stdout, and stderr.
     *
     * @throws PowerShellException if the process could not be started or timed out
     */
    Result run(String command, Duration timeout);

    /** Outcome of one PowerShell invocation. */
    record Result(int exitCode, String stdout, String stderr) {
        public boolean ok() { return exitCode == 0; }
    }
}
