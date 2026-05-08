package ai.intelliswarm.swarmai.state.plan;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;
import java.util.Objects;

/**
 * Payload record for a self-organisation task — what an agent puts on its own
 * todo list while working a problem. Distinct from
 * {@link ai.intelliswarm.swarmai.tool.safety.MutationPlan} (which gates
 * mutating tool calls); TaskItems are bookkeeping, not side-effecting.
 *
 * <p>TaskItem is immutable. To revise a task's description after creation,
 * supersede the old entry with a new one carrying the revised TaskItem —
 * preserving the audit trail.
 *
 * <p>Status lifecycle reuses {@link PlanEntry.Status}:
 * <ul>
 *   <li>{@link PlanEntry.Status#PROPOSED} — pending, not yet started</li>
 *   <li>{@link PlanEntry.Status#APPROVED} — in progress (agent is working it)</li>
 *   <li>{@link PlanEntry.Status#EXECUTED} — completed</li>
 *   <li>{@link PlanEntry.Status#REJECTED} — cancelled</li>
 *   <li>{@link PlanEntry.Status#SUPERSEDED} — replaced by a revised task</li>
 * </ul>
 *
 * @param description short imperative summary, e.g. {@code "Run the test suite"}
 * @param activeForm  present-continuous form for in-progress display, e.g.
 *                    {@code "Running the test suite"}; may be null (renderer
 *                    falls back to description)
 * @param parentTaskId optional parent task id for hierarchical breakdowns;
 *                     null for top-level tasks
 * @param metadata    free-form key/value pairs (priority, owner, blocked-by, etc.)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskItem(
        String description,
        String activeForm,
        String parentTaskId,
        Map<String, Object> metadata) {

    public TaskItem {
        Objects.requireNonNull(description, "description");
        if (description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
        // activeForm and parentTaskId may be null
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    /** Convenience: construct with just a description. */
    public static TaskItem of(String description) {
        return new TaskItem(description, null, null, Map.of());
    }

    /** Convenience: construct with description + active form. */
    public static TaskItem of(String description, String activeForm) {
        return new TaskItem(description, activeForm, null, Map.of());
    }

    /** Effective active-form label — the explicit one if set, else the description. */
    @JsonIgnore
    public String effectiveActiveForm() {
        return activeForm != null && !activeForm.isBlank() ? activeForm : description;
    }

    /** True when this task is a sub-task of another. */
    @JsonIgnore
    public boolean hasParent() {
        return parentTaskId != null && !parentTaskId.isBlank();
    }
}
