package ai.intelliswarm.swarmai.agent.mode;

import ai.intelliswarm.swarmai.agent.hook.HookAction;
import ai.intelliswarm.swarmai.agent.hook.HookResult;
import ai.intelliswarm.swarmai.agent.hook.LifecycleEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoalLockModeTest {

    @Test
    void prependsGoalDirectiveToUserPrompt() {
        GoalLockMode mode = new GoalLockMode("Keep producing morning notes until 6am ET");
        HookResult result = mode.onUserPromptSubmit(prompt("What's next?"));
        assertThat(result.action()).isEqualTo(HookAction.MODIFY);
        String rewritten = (String) result.modifiedPayload();
        assertThat(rewritten).contains("Keep producing morning notes until 6am ET");
        assertThat(rewritten).contains("[GOAL LOCK");
        assertThat(rewritten).contains("What's next?");
    }

    @Test
    void emptyGoalRejected() {
        assertThatThrownBy(() -> new GoalLockMode(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GoalLockMode("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullGoalRejected() {
        assertThatThrownBy(() -> new GoalLockMode(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void customSeparatorRespected() {
        GoalLockMode mode = new GoalLockMode("Stay on task", " | ");
        HookResult result = mode.onUserPromptSubmit(prompt("hi"));
        assertThat(((String) result.modifiedPayload())).contains(" | ");
    }

    @Test
    void onlyRespondsToUserPromptEvents() {
        // Stop event should pass through unchanged
        GoalLockMode mode = new GoalLockMode("test");
        HookResult result = mode.onStop(new LifecycleEvent.Stop(
                "corr1", "agent1", Instant.now(), "end_turn", Map.of()));
        assertThat(result.action()).isEqualTo(HookAction.PROCEED);
    }

    private static LifecycleEvent.UserPromptSubmit prompt(String text) {
        return new LifecycleEvent.UserPromptSubmit(
                "corr1", "agent1", Instant.now(), text, Map.of());
    }
}
