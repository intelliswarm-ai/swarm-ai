package ai.intelliswarm.swarmai.state.plan;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Decides, for a single incoming {@link PlanEntry}, whether it can auto-approve
 * given the current {@link EvolvingPlan}, or whether it must be routed to a
 * human approver. Stateless by design — every implementation derives its
 * answer from the plan's contents.
 *
 * <p>Composition: use {@link #all(PlanApprovalPolicy...)} to AND multiple
 * policies (most conservative wins — any {@code REQUIRES_HUMAN} dominates).
 * Combining a {@link RiskTieredApprovalPolicy} with a
 * {@link DriftBudgetApprovalPolicy} is the recommended starting configuration:
 * tier handles "what", budget handles "how often".
 */
@FunctionalInterface
public interface PlanApprovalPolicy {

    enum Decision {
        /** Policy permits this entry to transition to {@link PlanEntry.Status#APPROVED} without human review. */
        AUTO_APPROVE,
        /** Policy requires human approval before this entry can transition out of PROPOSED. */
        REQUIRES_HUMAN
    }

    /**
     * @param currentPlan the plan as it stands; never null, may be empty
     * @param incoming    the entry being decided on; expected to be in
     *                    {@link PlanEntry.Status#PROPOSED}
     * @return the policy's verdict
     */
    Decision decide(EvolvingPlan currentPlan, PlanEntry incoming);

    /**
     * Conservative AND: if any composed policy returns {@code REQUIRES_HUMAN},
     * the result is {@code REQUIRES_HUMAN}; otherwise {@code AUTO_APPROVE}.
     */
    static PlanApprovalPolicy all(PlanApprovalPolicy... policies) {
        Objects.requireNonNull(policies, "policies");
        List<PlanApprovalPolicy> list = Arrays.asList(policies);
        return (plan, incoming) -> {
            for (PlanApprovalPolicy p : list) {
                if (p.decide(plan, incoming) == Decision.REQUIRES_HUMAN) {
                    return Decision.REQUIRES_HUMAN;
                }
            }
            return Decision.AUTO_APPROVE;
        };
    }

    /**
     * Permissive OR: if <em>any</em> composed policy returns
     * {@code AUTO_APPROVE}, the result is {@code AUTO_APPROVE}; only when every
     * policy demands a human is the result {@code REQUIRES_HUMAN}. Useful for
     * letting a confident learned policy bypass static rules — e.g.
     * {@code any(all(tier, budget), bandit)} so a high-confidence bandit can
     * stand in for the operator.
     */
    static PlanApprovalPolicy any(PlanApprovalPolicy... policies) {
        Objects.requireNonNull(policies, "policies");
        List<PlanApprovalPolicy> list = Arrays.asList(policies);
        return (plan, incoming) -> {
            for (PlanApprovalPolicy p : list) {
                if (p.decide(plan, incoming) == Decision.AUTO_APPROVE) {
                    return Decision.AUTO_APPROVE;
                }
            }
            return Decision.REQUIRES_HUMAN;
        };
    }

    /** Always demands human review. Useful as a fallback or in test fixtures. */
    static PlanApprovalPolicy alwaysHuman() {
        return (plan, incoming) -> Decision.REQUIRES_HUMAN;
    }

    /** Always auto-approves. Useful in test fixtures; do not use in production. */
    static PlanApprovalPolicy alwaysAuto() {
        return (plan, incoming) -> Decision.AUTO_APPROVE;
    }
}
