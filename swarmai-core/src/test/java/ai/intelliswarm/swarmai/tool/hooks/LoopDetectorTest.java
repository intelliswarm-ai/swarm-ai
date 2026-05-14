package ai.intelliswarm.swarmai.tool.hooks;

import ai.intelliswarm.swarmai.tool.base.ToolHookContext;
import ai.intelliswarm.swarmai.tool.base.ToolHookResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoopDetectorTest {

    @Test
    void allowsFirstFewIdenticalFailures() {
        LoopDetector detector = new LoopDetector(3, 6, 50);
        // 1st & 2nd failure: ALLOW
        runFailure(detector, "agent1", "Bash", Map.of("command", "ls /missing"));
        runFailure(detector, "agent1", "Bash", Map.of("command", "ls /missing"));
        ToolHookResult third = detector.beforeToolUse(
                ToolHookContext.before("Bash", Map.of("command", "ls /missing"), "agent1", "wf"));
        // Third invocation sees 2 prior failures → still ALLOW (warnAt=3)
        assertThat(third.action()).isEqualTo(ToolHookResult.Action.ALLOW);
    }

    @Test
    void warnsAtThreshold() {
        LoopDetector detector = new LoopDetector(3, 6, 50);
        // 3 failures recorded — next beforeToolUse sees them → WARN
        for (int i = 0; i < 3; i++) {
            runFailure(detector, "agent1", "Bash", Map.of("command", "ls /missing"));
        }
        ToolHookResult result = detector.beforeToolUse(
                ToolHookContext.before("Bash", Map.of("command", "ls /missing"), "agent1", "wf"));
        assertThat(result.action()).isEqualTo(ToolHookResult.Action.WARN);
        assertThat(result.message()).contains("3 identical-args failures");
    }

    @Test
    void blocksAtUpperThreshold() {
        LoopDetector detector = new LoopDetector(3, 6, 50);
        for (int i = 0; i < 6; i++) {
            runFailure(detector, "agent1", "Bash", Map.of("command", "ls /missing"));
        }
        ToolHookResult result = detector.beforeToolUse(
                ToolHookContext.before("Bash", Map.of("command", "ls /missing"), "agent1", "wf"));
        assertThat(result.action()).isEqualTo(ToolHookResult.Action.DENY);
    }

    @Test
    void differentAgentsDontPolluteEachOther() {
        LoopDetector detector = new LoopDetector(3, 6, 50);
        for (int i = 0; i < 6; i++) {
            runFailure(detector, "agent1", "Bash", Map.of("command", "ls /missing"));
        }
        // agent2 has clean history
        ToolHookResult result = detector.beforeToolUse(
                ToolHookContext.before("Bash", Map.of("command", "ls /missing"), "agent2", "wf"));
        assertThat(result.action()).isEqualTo(ToolHookResult.Action.ALLOW);
    }

    @Test
    void differentArgsResetCounter() {
        LoopDetector detector = new LoopDetector(3, 6, 50);
        for (int i = 0; i < 5; i++) {
            runFailure(detector, "agent1", "Bash", Map.of("command", "ls /a"));
        }
        ToolHookResult result = detector.beforeToolUse(
                ToolHookContext.before("Bash", Map.of("command", "ls /b"), "agent1", "wf"));
        // Different args → no failure history for that key
        assertThat(result.action()).isEqualTo(ToolHookResult.Action.ALLOW);
    }

    @Test
    void identicalSuccessfulOutputAlsoLooping() {
        // With warnAt=3, blockAt=6 the detector blocks when it sees ≥ blockAt-1 = 5
        // *additional* same-output entries after the first. So we need 6 prior history
        // records of identical output to trip the same-result-loop guard.
        LoopDetector detector = new LoopDetector(3, 6, 50);
        for (int i = 0; i < 6; i++) {
            ToolHookContext after = ToolHookContext.after(
                    "Read", Map.of("path", "/x"), "same-content", 10L, "agent1", "wf");
            detector.afterToolUse(after);
        }
        ToolHookResult result = detector.beforeToolUse(
                ToolHookContext.before("Read", Map.of("path", "/x"), "agent1", "wf"));
        assertThat(result.action()).isEqualTo(ToolHookResult.Action.DENY);
        assertThat(result.message()).contains("identical output");
    }

    @Test
    void historyBoundedByLimit() {
        LoopDetector detector = new LoopDetector(3, 6, 10);
        for (int i = 0; i < 25; i++) {
            runFailure(detector, "agent1", "Tool", Map.of("i", i));
        }
        assertThat(detector.historySize("agent1")).isEqualTo(10);
    }

    @Test
    void rejectsInvalidConfig() {
        assertThatThrownBy(() -> new LoopDetector(0, 6, 50)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoopDetector(3, 3, 50)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoopDetector(3, 6, 4)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- helpers ----------

    private static void runFailure(LoopDetector detector, String agentId, String tool, Map<String, Object> args) {
        ToolHookContext after = ToolHookContext.error(
                tool, args, 5L, new RuntimeException("boom"), agentId, "wf");
        detector.afterToolUse(after);
    }
}
