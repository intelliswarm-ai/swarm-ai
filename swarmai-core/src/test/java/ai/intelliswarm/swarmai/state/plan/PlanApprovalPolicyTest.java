package ai.intelliswarm.swarmai.state.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("PlanApprovalPolicy Tests")
class PlanApprovalPolicyTest {

    private static PlanEntry proposed(String id, Risk risk) {
        return PlanEntry.builder()
                .id(id).proposedBy("tool:x").payload("op-" + id).risk(risk).build();
    }

    private static EvolvingPlan seeded(PlanEntry... extra) {
        EvolvingPlan p = EvolvingPlan.empty()
                .with(proposed("seed", Risk.MEDIUM))
                .markStatus("seed", PlanEntry.Status.APPROVED, "operator approved seed");
        for (PlanEntry e : extra) {
            p = p.with(e);
        }
        return p;
    }

    @Nested
    @DisplayName("RiskTieredApprovalPolicy")
    class RiskTiered {

        private final RiskTieredApprovalPolicy policy = new RiskTieredApprovalPolicy();

        @Test
        @DisplayName("requires human when no entry has been approved yet (no seed)")
        void noSeedAlwaysHuman() {
            EvolvingPlan unseeded = EvolvingPlan.empty().with(proposed("a", Risk.LOW));
            assertEquals(PlanApprovalPolicy.Decision.REQUIRES_HUMAN,
                    policy.decide(unseeded, proposed("b", Risk.LOW)));
        }

        @Test
        @DisplayName("LOW and MEDIUM auto-approve once the plan has a seed approval")
        void seededLowMediumAutoApprove() {
            assertEquals(PlanApprovalPolicy.Decision.AUTO_APPROVE,
                    policy.decide(seeded(), proposed("low", Risk.LOW)));
            assertEquals(PlanApprovalPolicy.Decision.AUTO_APPROVE,
                    policy.decide(seeded(), proposed("med", Risk.MEDIUM)));
        }

        @Test
        @DisplayName("HIGH and CRITICAL require human even after seed approval")
        void seededHighCriticalNeedHuman() {
            assertEquals(PlanApprovalPolicy.Decision.REQUIRES_HUMAN,
                    policy.decide(seeded(), proposed("hi", Risk.HIGH)));
            assertEquals(PlanApprovalPolicy.Decision.REQUIRES_HUMAN,
                    policy.decide(seeded(), proposed("crit", Risk.CRITICAL)));
        }

        @Test
        @DisplayName("UNKNOWN risk requires human even after seed approval")
        void unknownRiskRequiresHuman() {
            assertEquals(PlanApprovalPolicy.Decision.REQUIRES_HUMAN,
                    policy.decide(seeded(), proposed("u", Risk.UNKNOWN)));
        }

        @Test
        @DisplayName("EXECUTED entries also count as a seed approval")
        void executedCountsAsSeed() {
            EvolvingPlan executed = EvolvingPlan.empty()
                    .with(proposed("seed", Risk.LOW))
                    .markStatus("seed", PlanEntry.Status.EXECUTED, "");
            assertEquals(PlanApprovalPolicy.Decision.AUTO_APPROVE,
                    policy.decide(executed, proposed("next", Risk.LOW)));
        }

        @Test
        @DisplayName("custom tier set narrows what auto-approves")
        void customTiers() {
            RiskTieredApprovalPolicy lowOnly =
                    new RiskTieredApprovalPolicy(EnumSet.of(Risk.LOW));
            assertEquals(PlanApprovalPolicy.Decision.AUTO_APPROVE,
                    lowOnly.decide(seeded(), proposed("a", Risk.LOW)));
            assertEquals(PlanApprovalPolicy.Decision.REQUIRES_HUMAN,
                    lowOnly.decide(seeded(), proposed("b", Risk.MEDIUM)));
        }
    }

    @Nested
    @DisplayName("DriftBudgetApprovalPolicy")
    class DriftBudget {

        @Test
        @DisplayName("rejects negative budgets")
        void negativeBudgetRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> new DriftBudgetApprovalPolicy(-1));
        }

        @Test
        @DisplayName("requires human when no entry has been approved yet")
        void noSeedAlwaysHuman() {
            DriftBudgetApprovalPolicy policy = new DriftBudgetApprovalPolicy(5);
            EvolvingPlan unseeded = EvolvingPlan.empty().with(proposed("a", Risk.LOW));
            assertEquals(PlanApprovalPolicy.Decision.REQUIRES_HUMAN,
                    policy.decide(unseeded, proposed("b", Risk.LOW)));
        }

        @Test
        @DisplayName("auto-approves while drift count < budget")
        void belowBudgetAutoApprove() {
            DriftBudgetApprovalPolicy policy = new DriftBudgetApprovalPolicy(3);
            EvolvingPlan p = seeded(
                    proposed("d1", Risk.LOW),
                    proposed("d2", Risk.LOW));
            // 2 PROPOSED entries since the seed approval; budget=3 → next is the 3rd, allowed
            assertEquals(PlanApprovalPolicy.Decision.AUTO_APPROVE,
                    policy.decide(p, proposed("d3", Risk.LOW)));
        }

        @Test
        @DisplayName("requires human once drift count >= budget")
        void atOrAboveBudgetRequiresHuman() {
            DriftBudgetApprovalPolicy policy = new DriftBudgetApprovalPolicy(2);
            EvolvingPlan p = seeded(
                    proposed("d1", Risk.LOW),
                    proposed("d2", Risk.LOW));
            // 2 PROPOSED since seed; budget=2 → next would be the 3rd, exceeds
            assertEquals(PlanApprovalPolicy.Decision.REQUIRES_HUMAN,
                    policy.decide(p, proposed("d3", Risk.LOW)));
        }

        @Test
        @DisplayName("budget resets after a fresh APPROVED entry")
        void budgetResetsOnFreshApproval() {
            DriftBudgetApprovalPolicy policy = new DriftBudgetApprovalPolicy(2);
            EvolvingPlan p = seeded(
                    proposed("d1", Risk.LOW),
                    proposed("d2", Risk.LOW))
                    // operator approves a follow-up — drift counter resets after this entry
                    .with(proposed("checkpoint", Risk.LOW))
                    .markStatus("checkpoint", PlanEntry.Status.APPROVED, "operator nodded");

            assertEquals(PlanApprovalPolicy.Decision.AUTO_APPROVE,
                    policy.decide(p, proposed("d3", Risk.LOW)));
        }

        @Test
        @DisplayName("only PROPOSED entries count toward drift; rejected/superseded do not")
        void onlyProposedCountsAsDrift() {
            DriftBudgetApprovalPolicy policy = new DriftBudgetApprovalPolicy(2);
            EvolvingPlan p = seeded(
                    proposed("d1", Risk.LOW),
                    proposed("d2", Risk.LOW),
                    proposed("d3", Risk.LOW))
                    .markStatus("d1", PlanEntry.Status.REJECTED, "")
                    .markStatus("d2", PlanEntry.Status.SUPERSEDED, "");
            // Only d3 still counts as drift; budget=2 → 1 < 2, allowed
            assertEquals(PlanApprovalPolicy.Decision.AUTO_APPROVE,
                    policy.decide(p, proposed("d4", Risk.LOW)));
        }

        @Test
        @DisplayName("budget=0 disables auto-approval entirely")
        void zeroBudgetDisablesAuto() {
            DriftBudgetApprovalPolicy policy = new DriftBudgetApprovalPolicy(0);
            assertEquals(PlanApprovalPolicy.Decision.REQUIRES_HUMAN,
                    policy.decide(seeded(), proposed("d1", Risk.LOW)));
        }
    }

    @Nested
    @DisplayName("Composition (PlanApprovalPolicy.all)")
    class Composition {

        @Test
        @DisplayName("AND composition: any REQUIRES_HUMAN dominates")
        void anyHumanDominates() {
            PlanApprovalPolicy combined = PlanApprovalPolicy.all(
                    new RiskTieredApprovalPolicy(),
                    new DriftBudgetApprovalPolicy(5));
            // HIGH risk → tier says human; budget would say auto. AND → human.
            assertEquals(PlanApprovalPolicy.Decision.REQUIRES_HUMAN,
                    combined.decide(seeded(), proposed("h", Risk.HIGH)));
        }

        @Test
        @DisplayName("AND composition: both AUTO_APPROVE → AUTO_APPROVE")
        void bothAutoApprove() {
            PlanApprovalPolicy combined = PlanApprovalPolicy.all(
                    new RiskTieredApprovalPolicy(),
                    new DriftBudgetApprovalPolicy(5));
            assertEquals(PlanApprovalPolicy.Decision.AUTO_APPROVE,
                    combined.decide(seeded(), proposed("low", Risk.LOW)));
        }

        @Test
        @DisplayName("AND composition: budget exhausted blocks even safe-tier amendments")
        void budgetBlocksSafeTier() {
            PlanApprovalPolicy combined = PlanApprovalPolicy.all(
                    new RiskTieredApprovalPolicy(),
                    new DriftBudgetApprovalPolicy(1));
            EvolvingPlan p = seeded(proposed("d1", Risk.LOW));
            assertEquals(PlanApprovalPolicy.Decision.REQUIRES_HUMAN,
                    combined.decide(p, proposed("d2", Risk.LOW)));
        }
    }
}
