package ai.intelliswarm.swarmai.tool.windows.internal;

/** Raised when a PowerShell invocation cannot be started or does not finish in time. */
public class PowerShellException extends RuntimeException {
    public PowerShellException(String message) {
        super(message);
    }

    public PowerShellException(String message, Throwable cause) {
        super(message, cause);
    }
}
