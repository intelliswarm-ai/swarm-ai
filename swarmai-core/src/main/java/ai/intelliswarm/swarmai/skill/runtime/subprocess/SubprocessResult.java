package ai.intelliswarm.swarmai.skill.runtime.subprocess;

import java.time.Duration;

public record SubprocessResult(
    int exitCode,
    String stdout,
    String stderr,
    Duration wallDuration,
    boolean timedOut,
    boolean stdoutTruncated,
    boolean stderrTruncated
) {
    public boolean success() {
        return !timedOut && exitCode == 0;
    }
}
