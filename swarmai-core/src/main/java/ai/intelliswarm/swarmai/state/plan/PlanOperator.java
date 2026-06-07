package ai.intelliswarm.swarmai.state.plan;

import java.util.Objects;

/**
 * Operator-facing API for taking action on plan entries: approve, reject,
 * supersede. Thin convenience layer over a {@link PlanChannelAccessor} so UIs,
 * REST endpoints, and CLI tools don't have to learn the channel's status
 * transition API directly.
 *
 * <p>Every action records the {@code actor} (who took it) and a {@code reason}
 * (free-form). Both flow into the entry's reason/metadata for the audit trail.
 */
public final class PlanOperator {

    private final PlanChannelAccessor accessor;

    public PlanOperator(PlanChannelAccessor accessor) {
        this.accessor = Objects.requireNonNull(accessor, "accessor");
    }

    /**
     * Mark a {@link PlanEntry.Status#PROPOSED} entry as APPROVED. Throws if
     * the entry is already terminal.
     *
     * @param entryId entry to approve
     * @param actor   approver identity ("alice", "policy", etc.)
     * @param reason  free-form rationale; may be empty
     */
    public PlanEntry approve(String entryId, String actor, String reason) {
        PlanEntry result = transition(entryId, PlanEntry.Status.APPROVED, actor, reason);
        return result;
    }

    /**
     * Mark an entry as REJECTED. Valid from PROPOSED or APPROVED — rejecting
     * an already-approved entry is the operator-override case.
     */
    public PlanEntry reject(String entryId, String actor, String reason) {
        return transition(entryId, PlanEntry.Status.REJECTED, actor, reason);
    }

    /**
     * Mark an entry as EXECUTED. Typically called by a lifecycle hook after a
     * tool has successfully completed the work the entry described.
     */
    public PlanEntry markExecuted(String entryId, String actor, String reason) {
        return transition(entryId, PlanEntry.Status.EXECUTED, actor, reason);
    }

    /**
     * Replace an entry with a fresh proposal: marks the old entry SUPERSEDED
     * and appends {@code replacement} (which should already have its
     * {@link PlanEntry#supersedes()} set).
     */
    public EvolvingPlan supersede(String entryId, PlanEntry replacement, String actor) {
        Objects.requireNonNull(entryId, "entryId");
        Objects.requireNonNull(replacement, "replacement");
        Objects.requireNonNull(actor, "actor");
        return accessor.update(p -> p.markStatus(entryId,
                        PlanEntry.Status.SUPERSEDED, "superseded by " + actor)
                .with(replacement));
    }

    private PlanEntry transition(String entryId,
                                  PlanEntry.Status target,
                                  String actor,
                                  String reason) {
        Objects.requireNonNull(entryId, "entryId");
        Objects.requireNonNull(actor, "actor");
        String reasonText = formatReason(actor, reason);

        EvolvingPlan after = accessor.update(p -> {
            PlanEntry current = p.byId(entryId).orElseThrow(
                    () -> new IllegalArgumentException("unknown PlanEntry id: " + entryId));
            if (current.isTerminal() && current.status() != target) {
                throw new IllegalStateException(
                        "PlanEntry " + entryId + " is already terminal in status " + current.status());
            }
            // Operator transitions are always attributed to the operator's actor id.
            return p.markStatus(entryId, target, reasonText, actor);
        });
        return after.byId(entryId).orElseThrow();
    }

    private static String formatReason(String actor, String reason) {
        if (reason == null || reason.isBlank()) return actor;
        return actor + ": " + reason;
    }
}
