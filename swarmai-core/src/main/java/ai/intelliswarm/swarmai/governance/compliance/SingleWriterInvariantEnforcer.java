package ai.intelliswarm.swarmai.governance.compliance;

import ai.intelliswarm.swarmai.agent.hook.HookResult;
import ai.intelliswarm.swarmai.agent.hook.LifecycleEvent;
import ai.intelliswarm.swarmai.agent.hook.LifecycleHook;
import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Enforces the "single-writer" invariant from Anthropic's financial-services
 * repo: <em>at most one agent in any given workflow may hold write permission</em>.
 * This is the simplest enforceable rule that prevents two subagents from
 * writing to the same Excel model or the same draft IC memo.
 *
 * <p>Tracks the writer via {@link LifecycleEvent.FileChanged}: the first
 * agent to write under a correlation id becomes the recorded writer. If
 * another agent emits a write event for the same correlation id, the hook
 * blocks.
 *
 * <p>A workflow that wants to hand off the writer role explicitly can call
 * {@link #yieldWriter(String, String)} to release the slot — this is the
 * planned hook for the "orchestrator → writer → orchestrator" handoff
 * pattern from Phase 7.
 */
@PublicApi(since = "1.0.24")
public final class SingleWriterInvariantEnforcer implements LifecycleHook {

    private final ConcurrentMap<String, String> writerByCorrelationId = new ConcurrentHashMap<>();

    /** Visible for tests + diagnostics. */
    public String currentWriter(String correlationId) {
        return writerByCorrelationId.get(correlationId);
    }

    /**
     * Release the writer slot for {@code correlationId}. Returns the previous
     * writer (or {@code null}) so callers can audit the handoff.
     */
    public String yieldWriter(String correlationId, String releasedBy) {
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        String previous = writerByCorrelationId.get(correlationId);
        if (previous != null && previous.equals(releasedBy)) {
            return writerByCorrelationId.remove(correlationId);
        }
        return previous;
    }

    @Override
    public HookResult onFileChanged(LifecycleEvent.FileChanged event) {
        String correlationId = event.correlationId();
        String agentId = event.agentId();
        if (correlationId == null || agentId == null) {
            return HookResult.proceed();
        }
        // CREATED / MODIFIED / RENAMED count as writes; DELETED also (it changes state).
        // First writer wins; subsequent writers from a different agent are blocked.
        String[] rejecter = new String[]{null};
        writerByCorrelationId.compute(correlationId, (k, prev) -> {
            if (prev == null) return agentId;
            if (prev.equals(agentId)) return prev; // same writer, fine
            rejecter[0] = prev;
            return prev;
        });
        if (rejecter[0] != null) {
            return HookResult.block(
                    "Single-writer invariant: agent " + agentId
                            + " attempted to write while " + rejecter[0]
                            + " holds the writer slot (correlationId=" + correlationId + ", path="
                            + event.path() + ")");
        }
        return HookResult.proceed();
    }
}
