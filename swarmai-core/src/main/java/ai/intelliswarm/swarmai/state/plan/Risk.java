package ai.intelliswarm.swarmai.state.plan;

/**
 * Coarse-grained risk classification for a {@link PlanEntry}, used by approval
 * policies to tier auto-approval decisions. Mirrors the risk taxonomy used by
 * the tools-side {@code MutationPlan.RiskLevel}; a tools-side adapter copies
 * the level when wrapping a {@code MutationPlan} into a {@link PlanEntry}.
 *
 * <p>Kept in core (and not in {@code swarmai-tools}) so non-mutating entries
 * — reasoning steps, replanner amendments, operator notes — can also carry a
 * risk classification without depending on the tools module.
 */
public enum Risk {
    /** Risk not yet classified. Policies treat as the safest interpretation (require human). */
    UNKNOWN,
    /** Read-only or trivially reversible. */
    LOW,
    /** Reversible mutations within an allowlisted area. */
    MEDIUM,
    /** Hard-to-reverse mutations. */
    HIGH,
    /** Irreversible or system-impacting. */
    CRITICAL
}
