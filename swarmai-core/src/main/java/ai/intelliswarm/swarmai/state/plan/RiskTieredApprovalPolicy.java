package ai.intelliswarm.swarmai.state.plan;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Approval policy that auto-approves only entries whose {@link Risk} is in a
 * configured allowed set, <em>and</em> only after the plan has at least one
 * previously approved (or executed) entry — i.e. the operator has consented to
 * something at least once. Until that "seed approval" exists, every entry
 * routes to a human regardless of its risk.
 *
 * <p>Default tiers — {@code LOW} and {@code MEDIUM} — auto-approve once
 * seeded; {@code HIGH}, {@code CRITICAL}, and {@code UNKNOWN} always require
 * human review.
 *
 * <p>Rationale for {@code UNKNOWN} → human: a missing risk classification is a
 * strong signal that the originating tool / replanner didn't assert the risk
 * is bounded, and the safe default is to surface it.
 */
public final class RiskTieredApprovalPolicy implements PlanApprovalPolicy {

    /** Default auto-approvable tiers: {@link Risk#LOW} and {@link Risk#MEDIUM}. */
    public static final Set<Risk> DEFAULT_AUTO_TIERS =
            EnumSet.of(Risk.LOW, Risk.MEDIUM);

    private final Set<Risk> autoApprovableTiers;

    public RiskTieredApprovalPolicy() {
        this(DEFAULT_AUTO_TIERS);
    }

    /**
     * @param autoApprovableTiers the risk levels that may auto-approve once the
     *                            plan has a seed approval. Defensive copy taken.
     */
    public RiskTieredApprovalPolicy(Set<Risk> autoApprovableTiers) {
        Objects.requireNonNull(autoApprovableTiers, "autoApprovableTiers");
        this.autoApprovableTiers = EnumSet.copyOf(autoApprovableTiers);
    }

    public Set<Risk> autoApprovableTiers() {
        return EnumSet.copyOf(autoApprovableTiers);
    }

    @Override
    public Decision decide(EvolvingPlan currentPlan, PlanEntry incoming) {
        Objects.requireNonNull(currentPlan, "currentPlan");
        Objects.requireNonNull(incoming, "incoming");
        if (!hasSeedApproval(currentPlan)) {
            return Decision.REQUIRES_HUMAN;
        }
        if (autoApprovableTiers.contains(incoming.risk())) {
            return Decision.AUTO_APPROVE;
        }
        return Decision.REQUIRES_HUMAN;
    }

    /** True once any entry has reached APPROVED or EXECUTED status. */
    private static boolean hasSeedApproval(EvolvingPlan plan) {
        for (PlanEntry e : plan.entries()) {
            if (e.status() == PlanEntry.Status.APPROVED
                    || e.status() == PlanEntry.Status.EXECUTED) {
                return true;
            }
        }
        return false;
    }
}
