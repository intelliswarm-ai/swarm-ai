package ai.intelliswarm.swarmai.agent.mode;

import ai.intelliswarm.swarmai.agent.hook.HookResult;
import ai.intelliswarm.swarmai.agent.hook.LifecycleEvent;
import ai.intelliswarm.swarmai.agent.hook.LifecycleHook;
import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.Objects;

/**
 * Re-injects an agent's objective directive at every user-prompt turn so the
 * agent doesn't drift on long, multi-step tasks. Useful for:
 *
 * <ul>
 *   <li>Overnight cron tasks ("keep producing the morning note until done")</li>
 *   <li>Multi-step coding tasks ("don't stop until tests pass")</li>
 *   <li>Refactor loops where the agent might get distracted by side issues</li>
 * </ul>
 *
 * <p>Installed as a {@link LifecycleHook}; intercepts
 * {@link LifecycleEvent.UserPromptSubmit} and rewrites the payload to prepend
 * the locked goal as a system-style directive. Distinct from a runtime mode
 * because it's an additive behavior modifier rather than a process-strategy
 * choice — agents can be in plan / accept-edits / default permission mode AND
 * also have a goal-lock active.
 *
 * <p>Use sparingly: re-injecting on every turn costs tokens. For tasks that
 * naturally stay on track, leave it off.
 */
@PublicApi(since = "1.0.24")
public final class GoalLockMode implements LifecycleHook {

    private final String goal;
    private final String separator;

    public GoalLockMode(String goal) {
        this(goal, "\n\n---\n\n");
    }

    public GoalLockMode(String goal, String separator) {
        this.goal = Objects.requireNonNull(goal, "goal must not be null");
        this.separator = separator == null ? "\n\n" : separator;
        if (goal.isBlank()) {
            throw new IllegalArgumentException("goal must not be blank");
        }
    }

    public String goal() {
        return goal;
    }

    @Override
    public HookResult onUserPromptSubmit(LifecycleEvent.UserPromptSubmit event) {
        String directive = "[GOAL LOCK — re-injected each turn] " + goal;
        String rewritten = directive + separator + event.prompt();
        return HookResult.modify(rewritten);
    }
}
