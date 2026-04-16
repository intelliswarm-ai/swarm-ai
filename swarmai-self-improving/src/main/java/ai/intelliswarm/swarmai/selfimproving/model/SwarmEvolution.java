package ai.intelliswarm.swarmai.selfimproving.model;

import java.time.Instant;
import java.util.Map;

/**
 * Captures a self-evolution event where the swarm restructures itself
 * using existing framework capabilities.
 *
 * <p>Unlike proposals (which require framework code changes), evolutions
 * are optimizations the framework can apply at runtime: switching process
 * types, adjusting convergence defaults, updating tool routing hints.
 *
 * <p>Evolutions are persisted to H2 for cross-JVM learning and rendered
 * in Studio as an architecture timeline showing how the swarm optimizes
 * itself over successive runs.
 */
public record SwarmEvolution(
        String evolutionId,
        String swarmId,
        EvolutionType type,
        TopologySnapshot before,
        TopologySnapshot after,
        String reason,
        String observationType,
        double confidence,
        Instant timestamp
) {

    public enum EvolutionType {
        /** Switch process type (e.g., SEQUENTIAL → PARALLEL) */
        PROCESS_TYPE_CHANGE,
        /** Adjust maxIterations for convergence */
        CONVERGENCE_ADJUSTMENT,
        /** Update tool routing hints for better first-pick accuracy */
        TOOL_ROUTING_UPDATE,
        /** Optimize token allocation across tasks */
        TOKEN_BUDGET_REBALANCE,
        /** Promote a validated skill to built-in */
        SKILL_PROMOTION
    }

    /**
     * Snapshot of swarm topology at a point in time.
     */
    public record TopologySnapshot(
            String processType,
            int taskCount,
            int maxDependencyDepth,
            int agentCount,
            Map<String, Object> configuration
    ) {
        public static TopologySnapshot from(WorkflowShape shape, Map<String, Object> extra) {
            return new TopologySnapshot(
                    shape != null ? shape.processType() : "UNKNOWN",
                    shape != null ? shape.taskCount() : 0,
                    shape != null ? shape.maxDependencyDepth() : 0,
                    shape != null ? shape.agentCount() : 0,
                    extra != null ? extra : Map.of()
            );
        }
    }

    public static SwarmEvolution create(
            String swarmId,
            EvolutionType type,
            TopologySnapshot before,
            TopologySnapshot after,
            String reason,
            String observationType,
            double confidence) {
        return new SwarmEvolution(
                java.util.UUID.randomUUID().toString(),
                swarmId, type, before, after, reason,
                observationType, confidence, Instant.now()
        );
    }
}
