package ai.intelliswarm.swarmai.agent.subagent.coding;

import java.time.Duration;
import java.util.Objects;

/**
 * Captured output from a coding-agent CLI process. Fed to a {@link SubagentOutputParser}
 * so each CLI can map exit-code + stdout/stderr into a {@code SubagentResult}
 * appropriate for that tool (Codex's JSON streams differ from Claude Code's,
 * etc.).
 *
 * @param exitCode  process exit code
 * @param stdout    everything the process wrote to stdout, decoded as UTF-8
 * @param stderr    everything the process wrote to stderr, decoded as UTF-8
 * @param elapsed   wall-clock duration from process start to exit
 */
public record ProcessOutput(int exitCode, String stdout, String stderr, Duration elapsed) {

    public ProcessOutput {
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
        Objects.requireNonNull(elapsed, "elapsed");
    }

    public boolean ok() { return exitCode == 0; }
}
