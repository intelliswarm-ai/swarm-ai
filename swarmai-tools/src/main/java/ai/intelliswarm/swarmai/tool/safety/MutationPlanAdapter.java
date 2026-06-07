package ai.intelliswarm.swarmai.tool.safety;

import ai.intelliswarm.swarmai.state.plan.PlanEntry;
import ai.intelliswarm.swarmai.state.plan.Risk;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bridges a {@link MutationPlan} (tools-side domain object) to a
 * {@link PlanEntry} (core-side state-channel object). Keeps the conversion
 * logic in one place so guards, hooks, and trainers don't each invent it.
 *
 * <p>Mapping rules:
 * <ul>
 *   <li>{@link MutationPlan#toolName()} → {@code "tool:" + toolName} as the
 *       entry's {@code proposedBy} attribution.</li>
 *   <li>{@link MutationPlan#summary()} → entry metadata under {@code "summary"}.</li>
 *   <li>{@link MutationPlan#riskLevel()} → {@link Risk} via {@link #toRisk}.</li>
 *   <li>The full {@code MutationPlan} is the entry's {@code payload} — policies
 *       and viewers can downcast when they need the operations list.</li>
 *   <li>Op count and tool-supplied metadata are flattened into the entry's
 *       metadata for display without requiring a downcast.</li>
 * </ul>
 */
public final class MutationPlanAdapter {

    private MutationPlanAdapter() {
        // utility class
    }

    /** Build a fresh PROPOSED {@link PlanEntry} from a {@link MutationPlan}. */
    public static PlanEntry toEntry(MutationPlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("plan must not be null");
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("summary", plan.summary());
        meta.put("opCount", plan.opCount());
        if (!plan.metadata().isEmpty()) {
            meta.put("toolMetadata", plan.metadata());
        }
        return PlanEntry.builder()
                .proposedBy("tool:" + plan.toolName())
                .payload(plan)
                .payloadType(MutationPlan.class.getName())
                .risk(toRisk(plan.riskLevel()))
                .metadata(meta)
                .build();
    }

    /** Map the tools-side risk taxonomy to the core-side {@link Risk} enum. */
    public static Risk toRisk(MutationPlan.RiskLevel level) {
        if (level == null) return Risk.UNKNOWN;
        return switch (level) {
            case LOW -> Risk.LOW;
            case MEDIUM -> Risk.MEDIUM;
            case HIGH -> Risk.HIGH;
            case CRITICAL -> Risk.CRITICAL;
        };
    }
}
