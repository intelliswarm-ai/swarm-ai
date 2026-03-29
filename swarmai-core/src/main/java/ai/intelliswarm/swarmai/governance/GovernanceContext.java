package ai.intelliswarm.swarmai.governance;

import java.util.Map;

/**
 * Captures the workflow state at the moment an approval gate is triggered.
 * Passed to the gate handler so approvers have full context for their decision.
 *
 * @param swarmId          the swarm that triggered the gate
 * @param taskId           the task associated with the gate trigger (may be null for non-task gates)
 * @param tenantId         the tenant that owns this workflow (for multi-tenant filtering)
 * @param currentIteration the current iteration number (relevant for iterative/self-improving processes)
 * @param metadata         additional workflow state and context data
 */
public record GovernanceContext(
        String swarmId,
        String taskId,
        String tenantId,
        int currentIteration,
        Map<String, Object> metadata
) {

    /**
     * Compact constructor that defensively copies metadata.
     */
    public GovernanceContext {
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    /**
     * Convenience factory for creating a context with minimal information.
     */
    public static GovernanceContext of(String swarmId, String taskId, String tenantId) {
        return new GovernanceContext(swarmId, taskId, tenantId, 0, Map.of());
    }

    /**
     * Convenience factory with iteration.
     */
    public static GovernanceContext of(String swarmId, String taskId, String tenantId, int iteration) {
        return new GovernanceContext(swarmId, taskId, tenantId, iteration, Map.of());
    }
}
