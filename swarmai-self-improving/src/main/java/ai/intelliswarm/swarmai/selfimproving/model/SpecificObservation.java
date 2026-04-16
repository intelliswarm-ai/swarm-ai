package ai.intelliswarm.swarmai.selfimproving.model;

import java.time.Instant;
import java.util.Map;

/**
 * A raw observation from a specific workflow execution.
 * Contains domain-specific details that must be generalized
 * before becoming a framework improvement.
 */
public record SpecificObservation(
        String observationId,
        ObservationType type,
        WorkflowShape workflowShape,
        String description,
        Map<String, Object> evidence,
        double impact,
        Instant timestamp
) {

    /**
     * Routing classification for observations.
     *
     * INTERNAL: The framework already has the capability — apply as runtime
     * self-evolution (restructure swarm topology, adjust defaults). Never
     * report externally.
     *
     * EXTERNAL: The framework lacks a capability — report as a proposal to
     * intelliswarm.ai/contribute for framework code changes.
     */
    public enum ObservationRouting { INTERNAL, EXTERNAL }

    public enum ObservationType {
        // --- INTERNAL: self-evolution using existing capabilities ---
        EXPENSIVE_TASK(ObservationRouting.INTERNAL),
        CONVERGENCE_PATTERN(ObservationRouting.INTERNAL),
        TOOL_SELECTION(ObservationRouting.INTERNAL),
        PROMPT_EFFICIENCY(ObservationRouting.INTERNAL),
        PROCESS_SUITABILITY(ObservationRouting.INTERNAL),
        SUCCESSFUL_SKILL(ObservationRouting.INTERNAL),

        // --- EXTERNAL: require framework code changes ---
        FAILURE(ObservationRouting.EXTERNAL),
        ANTI_PATTERN(ObservationRouting.EXTERNAL),
        DECISION_QUALITY(ObservationRouting.EXTERNAL),
        COORDINATION_QUALITY(ObservationRouting.EXTERNAL);

        private final ObservationRouting routing;

        ObservationType(ObservationRouting routing) {
            this.routing = routing;
        }

        public ObservationRouting routing() { return routing; }
        public boolean isInternal() { return routing == ObservationRouting.INTERNAL; }
        public boolean isExternal() { return routing == ObservationRouting.EXTERNAL; }
    }

    public static SpecificObservation failure(WorkflowShape shape, String description, Map<String, Object> evidence) {
        return new SpecificObservation(
                java.util.UUID.randomUUID().toString(),
                ObservationType.FAILURE, shape, description, evidence, 1.0, Instant.now()
        );
    }

    public static SpecificObservation expensiveTask(WorkflowShape shape, String description, Map<String, Object> evidence, double impact) {
        return new SpecificObservation(
                java.util.UUID.randomUUID().toString(),
                ObservationType.EXPENSIVE_TASK, shape, description, evidence, impact, Instant.now()
        );
    }

    public static SpecificObservation convergencePattern(WorkflowShape shape, String description, Map<String, Object> evidence) {
        return new SpecificObservation(
                java.util.UUID.randomUUID().toString(),
                ObservationType.CONVERGENCE_PATTERN, shape, description, evidence, 0.7, Instant.now()
        );
    }

    public static SpecificObservation toolSelection(WorkflowShape shape, String description, Map<String, Object> evidence) {
        return new SpecificObservation(
                java.util.UUID.randomUUID().toString(),
                ObservationType.TOOL_SELECTION, shape, description, evidence, 0.5, Instant.now()
        );
    }

    public static SpecificObservation antiPattern(WorkflowShape shape, String description, Map<String, Object> evidence) {
        return new SpecificObservation(
                java.util.UUID.randomUUID().toString(),
                ObservationType.ANTI_PATTERN, shape, description, evidence, 0.9, Instant.now()
        );
    }

    public static SpecificObservation decisionQuality(WorkflowShape shape, String description, Map<String, Object> evidence) {
        return new SpecificObservation(
                java.util.UUID.randomUUID().toString(),
                ObservationType.DECISION_QUALITY, shape, description, evidence, 0.8, Instant.now()
        );
    }

    public static SpecificObservation processSuitability(WorkflowShape shape, String description, Map<String, Object> evidence) {
        return new SpecificObservation(
                java.util.UUID.randomUUID().toString(),
                ObservationType.PROCESS_SUITABILITY, shape, description, evidence, 0.7, Instant.now()
        );
    }

    public static SpecificObservation coordinationQuality(WorkflowShape shape, String description, Map<String, Object> evidence) {
        return new SpecificObservation(
                java.util.UUID.randomUUID().toString(),
                ObservationType.COORDINATION_QUALITY, shape, description, evidence, 0.6, Instant.now()
        );
    }
}
