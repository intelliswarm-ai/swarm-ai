package ai.intelliswarm.swarmai.governance.compliance;

import ai.intelliswarm.swarmai.agent.hook.HookResult;
import ai.intelliswarm.swarmai.agent.hook.LifecycleEvent;
import ai.intelliswarm.swarmai.agent.hook.LifecycleHook;
import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Enforces a maximum agent-delegation depth — borrowed from Anthropic's
 * financial-services repo, which restricts orchestration to one level
 * (orchestrator → workers, no recursive subagents) to keep workflows
 * inspectable and prevent runaway fan-out.
 *
 * <p>Tracks depth via {@link LifecycleEvent#correlationId()}: every time a
 * {@link LifecycleEvent.SubagentStart} fires under a correlation id, depth
 * for that id increments; {@link LifecycleEvent.SubagentStop} decrements.
 * If depth would exceed {@link #maxDepth}, the hook returns
 * {@link HookResult#block(String)}.
 *
 * <p>Default {@code maxDepth = 1} matches the FS pattern (one delegation step
 * permitted). Construct with a larger value for workflows that genuinely need
 * deeper trees.
 */
@PublicApi(since = "1.0.24")
public final class DelegationDepthPolicy implements LifecycleHook {

    private final int maxDepth;
    private final ConcurrentMap<String, Integer> depth = new ConcurrentHashMap<>();

    public DelegationDepthPolicy() {
        this(1);
    }

    public DelegationDepthPolicy(int maxDepth) {
        if (maxDepth < 0) {
            throw new IllegalArgumentException("maxDepth must be ≥ 0");
        }
        this.maxDepth = maxDepth;
    }

    public int maxDepth() {
        return maxDepth;
    }

    public int currentDepth(String correlationId) {
        return depth.getOrDefault(correlationId, 0);
    }

    @Override
    public HookResult onSubagentStart(LifecycleEvent.SubagentStart event) {
        Objects.requireNonNull(event.correlationId(), "correlationId required for delegation tracking");
        // Atomic increment with a guard: if the result would exceed maxDepth, refuse.
        int[] reject = new int[]{-1};
        depth.compute(event.correlationId(), (k, prev) -> {
            int now = prev == null ? 0 : prev;
            if (now >= maxDepth) {
                reject[0] = now;
                return prev;
            }
            return now + 1;
        });
        if (reject[0] >= 0) {
            return HookResult.block(
                    "Delegation depth " + (reject[0] + 1) + " exceeds policy max " + maxDepth
                            + " (correlationId=" + event.correlationId() + ")");
        }
        return HookResult.proceed();
    }

    @Override
    public HookResult onSubagentStop(LifecycleEvent.SubagentStop event) {
        depth.compute(event.correlationId(), (k, prev) -> {
            if (prev == null || prev <= 1) return null;
            return prev - 1;
        });
        return HookResult.proceed();
    }
}
