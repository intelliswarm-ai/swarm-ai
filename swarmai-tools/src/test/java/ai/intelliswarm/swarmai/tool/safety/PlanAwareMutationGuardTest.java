package ai.intelliswarm.swarmai.tool.safety;

import ai.intelliswarm.swarmai.state.plan.BanditApprovalPolicy;
import ai.intelliswarm.swarmai.state.plan.BanditApprovalTrainer;
import ai.intelliswarm.swarmai.state.plan.DriftBudgetApprovalPolicy;
import ai.intelliswarm.swarmai.state.plan.EvolvingPlan;
import ai.intelliswarm.swarmai.state.plan.InMemoryPlanChannelAccessor;
import ai.intelliswarm.swarmai.state.plan.PlanApprovalPolicy;
import ai.intelliswarm.swarmai.state.plan.PlanEntry;
import ai.intelliswarm.swarmai.state.plan.PlanOperator;
import ai.intelliswarm.swarmai.state.plan.RiskTieredApprovalPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PlanAwareMutationGuard Tests")
class PlanAwareMutationGuardTest {

    private static MutationPlan plan(String summary, MutationPlan.RiskLevel risk) {
        return MutationPlan.builder()
                .toolName("os_filesystem")
                .summary(summary)
                .riskLevel(risk)
                .op("write", "/tmp/x")
                .metadata("apply", true)
                .build();
    }

    /** Stub human gate that always responds the same way. */
    private static MutationGuard alwaysApproveHuman(String approver) {
        return p -> MutationGuard.Decision.approve(approver, "operator approved");
    }

    private static MutationGuard alwaysRejectHuman(String denier, String reason) {
        return p -> MutationGuard.Decision.reject(denier, reason);
    }

    @Test
    @DisplayName("first call (no seed approval) routes to human delegate; channel records PROPOSED → APPROVED")
    void firstCallRoutesToHuman() {
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        PlanApprovalPolicy policy = PlanApprovalPolicy.all(
                new RiskTieredApprovalPolicy(),
                new DriftBudgetApprovalPolicy(5));

        PlanAwareMutationGuard guard = new PlanAwareMutationGuard(
                alwaysApproveHuman("alice"), channel, policy);

        MutationGuard.Decision decision = guard.check(plan("create file", MutationPlan.RiskLevel.LOW));

        assertTrue(decision.approved());
        assertEquals("alice", decision.decidedBy());

        EvolvingPlan p = channel.get();
        assertEquals(1, p.size());
        assertEquals(PlanEntry.Status.APPROVED, p.entries().get(0).status());
        assertTrue(p.entries().get(0).reason().contains("alice"));
    }

    @Test
    @DisplayName("after seed approval, LOW-risk amendments auto-approve via policy and DON'T hit the human")
    void seededLowRiskAutoApproves() {
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        // Pre-seed: an existing APPROVED entry so RiskTier sees a seed
        channel.append(PlanEntry.builder()
                .id("seed").proposedBy("tool:os_filesystem")
                .payload(plan("seed", MutationPlan.RiskLevel.MEDIUM))
                .risk(ai.intelliswarm.swarmai.state.plan.Risk.MEDIUM)
                .build());
        channel.markStatus("seed", PlanEntry.Status.APPROVED, "operator seeded");

        PlanApprovalPolicy policy = PlanApprovalPolicy.all(
                new RiskTieredApprovalPolicy(),
                new DriftBudgetApprovalPolicy(5));

        // Human gate should NOT be called for an auto-approve.
        boolean[] humanCalled = {false};
        MutationGuard human = p -> {
            humanCalled[0] = true;
            return MutationGuard.Decision.reject("UNREACHED", "should not be invoked");
        };

        PlanAwareMutationGuard guard = new PlanAwareMutationGuard(human, channel, policy);
        MutationGuard.Decision decision = guard.check(plan("low-risk write", MutationPlan.RiskLevel.LOW));

        assertTrue(decision.approved());
        assertEquals(PlanAwareMutationGuard.DECIDED_BY_POLICY, decision.decidedBy());
        assertFalse(humanCalled[0], "Human gate must not be consulted on auto-approve");

        // Channel reflects the auto-approval
        EvolvingPlan p = channel.get();
        assertEquals(2, p.size()); // seed + new entry
        PlanEntry latest = p.entries().get(1);
        assertEquals(PlanEntry.Status.APPROVED, latest.status());
        assertEquals("policy auto-approved", latest.reason());
    }

    @Test
    @DisplayName("HIGH-risk routes to human even when seed approval exists; rejection is recorded as REJECTED")
    void highRiskAlwaysHitsHumanAndRejection() {
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        // Seed
        channel.append(PlanEntry.builder()
                .id("seed").proposedBy("tool:os_filesystem")
                .payload(plan("seed", MutationPlan.RiskLevel.MEDIUM))
                .risk(ai.intelliswarm.swarmai.state.plan.Risk.MEDIUM)
                .build());
        channel.markStatus("seed", PlanEntry.Status.APPROVED, "");

        PlanApprovalPolicy policy = new RiskTieredApprovalPolicy();
        MutationGuard guard = new PlanAwareMutationGuard(
                alwaysRejectHuman("bob", "too risky"), channel, policy);

        MutationGuard.Decision d = guard.check(plan("delete root", MutationPlan.RiskLevel.HIGH));

        assertFalse(d.approved());
        assertEquals("bob", d.decidedBy());

        PlanEntry latest = channel.get().entries().get(channel.get().size() - 1);
        assertEquals(PlanEntry.Status.REJECTED, latest.status());
        assertTrue(latest.reason().contains("bob"));
    }

    @Test
    @DisplayName("entry payload preserves the MutationPlan and adapter copies risk + summary metadata")
    void entryPayloadRoundTrips() {
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        PlanAwareMutationGuard guard = new PlanAwareMutationGuard(
                alwaysApproveHuman("alice"),
                channel,
                PlanApprovalPolicy.alwaysHuman());

        MutationPlan original = plan("create dir", MutationPlan.RiskLevel.MEDIUM);
        guard.check(original);

        PlanEntry entry = channel.get().entries().get(0);
        assertNotNull(entry.payload());
        assertTrue(entry.payload() instanceof MutationPlan);
        assertEquals(MutationPlan.class.getName(), entry.payloadType());
        assertEquals(ai.intelliswarm.swarmai.state.plan.Risk.MEDIUM, entry.risk());

        Map<String, Object> meta = entry.metadata();
        assertEquals("create dir", meta.get("summary"));
        assertEquals(1, meta.get("opCount"));
    }

    @Test
    @DisplayName("end-to-end: bandit trainer learns from operator override of a policy auto-approval")
    void endToEndBanditLearnsFromOverride() {
        // 1. Set up channel + bandit + trainer
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        BanditApprovalPolicy bandit = new BanditApprovalPolicy(
                BanditApprovalPolicy.DEFAULT_BUCKET_KEY, /*warmup*/ 0);
        BanditApprovalTrainer.attach(channel, bandit);

        // 2. Seed: one APPROVED entry so the tier policy will permit auto-approve
        channel.append(PlanEntry.builder()
                .id("seed").proposedBy("tool:os_filesystem")
                .payload(plan("seed", MutationPlan.RiskLevel.MEDIUM))
                .risk(ai.intelliswarm.swarmai.state.plan.Risk.MEDIUM)
                .build());
        channel.markStatus("seed", PlanEntry.Status.APPROVED, "");

        // 3. Build the guard (no budget cap, just risk tier)
        PlanAwareMutationGuard guard = new PlanAwareMutationGuard(
                alwaysApproveHuman("alice"),
                channel,
                new RiskTieredApprovalPolicy());

        PlanOperator operator = new PlanOperator(channel);

        // 4. Drive 30 LOW-risk auto-approvals, operator overrides each one
        for (int i = 0; i < 30; i++) {
            MutationGuard.Decision d = guard.check(plan("op-" + i, MutationPlan.RiskLevel.LOW));
            assertEquals(PlanAwareMutationGuard.DECIDED_BY_POLICY, d.decidedBy());

            // Find the just-appended entry (last one) and override.
            PlanEntry latest = channel.get().entries().get(channel.get().size() - 1);
            operator.reject(latest.id(), "alice", "second thoughts");
        }

        // 5. Bandit should have learned: low success rate for this bucket
        String key = "tool:os_filesystem:" + ai.intelliswarm.swarmai.state.plan.Risk.LOW;
        assertTrue(bandit.observationCount(key) >= 30,
                "Trainer should have logged 30+ outcomes; got " + bandit.observationCount(key));
        assertTrue(bandit.meanSuccessRate(key) < 0.3,
                "Bandit should have low success rate after 30 overrides; got " + bandit.meanSuccessRate(key));
    }
}
