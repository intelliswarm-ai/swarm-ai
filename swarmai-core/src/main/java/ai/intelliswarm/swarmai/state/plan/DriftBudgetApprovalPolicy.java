package ai.intelliswarm.swarmai.state.plan;

import java.util.List;
import java.util.Objects;

/**
 * Approval policy that bounds <em>how many</em> proposed amendments may
 * auto-approve between operator approvals. Once the budget is consumed, the
 * next proposed entry routes to a human; the budget then resets at the next
 * APPROVED transition.
 *
 * <p>Drift is counted as the number of entries currently in
 * {@link PlanEntry.Status#PROPOSED} that appear <em>after</em> the most recent
 * APPROVED or EXECUTED entry in append order. The incoming entry, if accepted,
 * would be the {@code (drift + 1)}<sup>th</sup> — so we require
 * {@code drift < budget} for {@link Decision#AUTO_APPROVE}.
 *
 * <p>If the plan has no seed approval yet, the policy returns
 * {@link Decision#REQUIRES_HUMAN} regardless of budget — there's nothing to
 * "drift" from. Pair with {@link RiskTieredApprovalPolicy} via
 * {@link PlanApprovalPolicy#all} for the full intended behaviour.
 */
public final class DriftBudgetApprovalPolicy implements PlanApprovalPolicy {

    private final int budget;

    /**
     * @param budget the maximum number of PROPOSED entries that may auto-approve
     *               between operator approvals. Must be {@code >= 0}.
     */
    public DriftBudgetApprovalPolicy(int budget) {
        if (budget < 0) {
            throw new IllegalArgumentException("budget must be non-negative, was " + budget);
        }
        this.budget = budget;
    }

    public int budget() {
        return budget;
    }

    @Override
    public Decision decide(EvolvingPlan currentPlan, PlanEntry incoming) {
        Objects.requireNonNull(currentPlan, "currentPlan");
        Objects.requireNonNull(incoming, "incoming");

        List<PlanEntry> entries = currentPlan.entries();
        int lastApprovedIdx = -1;
        for (int i = entries.size() - 1; i >= 0; i--) {
            PlanEntry.Status s = entries.get(i).status();
            if (s == PlanEntry.Status.APPROVED || s == PlanEntry.Status.EXECUTED) {
                lastApprovedIdx = i;
                break;
            }
        }
        if (lastApprovedIdx < 0) {
            return Decision.REQUIRES_HUMAN;
        }

        int drift = 0;
        for (int i = lastApprovedIdx + 1; i < entries.size(); i++) {
            if (entries.get(i).status() == PlanEntry.Status.PROPOSED) {
                drift++;
            }
        }
        return drift < budget ? Decision.AUTO_APPROVE : Decision.REQUIRES_HUMAN;
    }
}
