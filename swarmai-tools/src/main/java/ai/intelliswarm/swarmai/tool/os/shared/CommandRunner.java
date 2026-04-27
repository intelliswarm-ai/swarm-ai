package ai.intelliswarm.swarmai.tool.os.shared;

import java.time.Duration;
import java.util.List;

/**
 * Generic interface for executing a process and capturing its output. The OS-specific
 * implementations (Bash, PowerShell, future osascript runners, …) all conform to this so
 * the cross-platform tools can be wired against the abstraction.
 *
 * <p>Implementations MUST honor the timeout — kill the process and report
 * {@link Result#timedOut()} {@code = true} on overrun rather than blocking the caller.
 */
public interface CommandRunner {

    /**
     * Run a command line. Implementations decide how to interpret {@code argv}:
     * {@link BashRunner} treats the first element as the executable; {@code PowerShellRunner}
     * (Windows) wraps everything in {@code powershell.exe -NoProfile -Command "…"}.
     *
     * @param argv      the executable and its arguments — never null, never empty
     * @param timeout   max wall-clock time before we kill the process
     */
    Result run(List<String> argv, Duration timeout);

    /** Captured outcome of an external process. {@code stdout}/{@code stderr} are joined as text. */
    record Result(int exitCode, String stdout, String stderr, boolean timedOut) {
        public boolean ok() { return exitCode == 0 && !timedOut; }
    }
}
