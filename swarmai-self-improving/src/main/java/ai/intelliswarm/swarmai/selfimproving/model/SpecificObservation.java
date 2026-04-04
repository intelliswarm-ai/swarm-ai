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

    public enum ObservationType {
        FAILURE,
        EXPENSIVE_TASK,
        SUCCESSFUL_SKILL,
        CONVERGENCE_PATTERN,
        TOOL_SELECTION,
        PROMPT_EFFICIENCY,
        ANTI_PATTERN
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
}
