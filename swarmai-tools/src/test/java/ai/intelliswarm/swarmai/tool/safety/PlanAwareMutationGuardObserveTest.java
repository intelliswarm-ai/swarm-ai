package ai.intelliswarm.swarmai.tool.safety;

import ai.intelliswarm.swarmai.state.plan.BanditApprovalPolicy;
import ai.intelliswarm.swarmai.state.plan.BanditApprovalTrainer;
import ai.intelliswarm.swarmai.state.plan.EvolvingPlan;
import ai.intelliswarm.swarmai.state.plan.InMemoryPlanChannelAccessor;
import ai.intelliswarm.swarmai.state.plan.PlanApprovalPolicy;
import ai.intelliswarm.swarmai.state.plan.PlanEntry;
import ai.intelliswarm.swarmai.state.plan.Risk;
import ai.intelliswarm.swarmai.state.plan.RiskTieredApprovalPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PlanAwareMutationGuard observe() Tests")
class PlanAwareMutationGuardObserveTest {

    private static MutationPlan plan(MutationPlan.RiskLevel risk) {
        return MutationPlan.builder()
                .toolName("os_filesystem")
                .summary("create file")
                .riskLevel(risk)
                .op("write", "/tmp/x")
                .metadata("apply", true)
                .build();
    }

    private static MutationGuard alwaysApproveHuman(String approver) {
        return p -> MutationGuard.Decision.approve(approver, "operator approved");
    }

    private static InMemoryPlanChannelAccessor seededChannel() {
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        channel.append(PlanEntry.builder()
                .id("seed").proposedBy("tool:os_filesystem")
                .payload(plan(MutationPlan.RiskLevel.MEDIUM))
                .risk(Risk.MEDIUM).build());
        channel.markStatus("seed", PlanEntry.Status.APPROVED, "");
        return channel;
    }

    @Test
    @DisplayName("Decision returned by check() carries the entry id as its correlationId")
    void decisionCarriesCorrelationId() {
        InMemoryPlanChannelAccessor channel = seededChannel();
        PlanAwareMutationGuard guard = new PlanAwareMutationGuard(
                alwaysApproveHuman("alice"), channel, new RiskTieredApprovalPolicy());

        MutationGuard.Decision d = guard.check(plan(MutationPlan.RiskLevel.LOW));

        assertTrue(d.approved());
        assertNotNull(d.correlationId());
        // The correlationId matches the just-appended entry's id.
        EvolvingPlan p = channel.get();
        PlanEntry latest = p.entries().get(p.size() - 1);
        assertEquals(latest.id(), d.correlationId());
    }

    @Test
    @DisplayName("observe(success=true) transitions APPROVED → EXECUTED")
    void observeSuccessMarksExecuted() {
        InMemoryPlanChannelAccessor channel = seededChannel();
        PlanAwareMutationGuard guard = new PlanAwareMutationGuard(
                alwaysApproveHuman("alice"), channel, new RiskTieredApprovalPolicy());

        MutationGuard.Decision d = guard.check(plan(MutationPlan.RiskLevel.LOW));
        guard.observe(d.correlationId(), true, "wrote 12 bytes");

        PlanEntry final_ = channel.get().byId(d.correlationId()).orElseThrow();
        assertEquals(PlanEntry.Status.EXECUTED, final_.status());
        assertTrue(final_.reason().contains("wrote 12 bytes"));
    }

    @Test
    @DisplayName("observe(success=false) transitions APPROVED → REJECTED with failure prefix")
    void observeFailureMarksRejected() {
        InMemoryPlanChannelAccessor channel = seededChannel();
        PlanAwareMutationGuard guard = new PlanAwareMutationGuard(
                alwaysApproveHuman("alice"), channel, new RiskTieredApprovalPolicy());

        MutationGuard.Decision d = guard.check(plan(MutationPlan.RiskLevel.LOW));
        guard.observe(d.correlationId(), false, "disk full");

        PlanEntry final_ = channel.get().byId(d.correlationId()).orElseThrow();
        assertEquals(PlanEntry.Status.REJECTED, final_.status());
        assertTrue(final_.reason().contains("execution failed"));
        assertTrue(final_.reason().contains("disk full"));
    }

    @Test
    @DisplayName("observe with null correlationId is a silent no-op")
    void observeNullIsNoOp() {
        InMemoryPlanChannelAccessor channel = seededChannel();
        PlanAwareMutationGuard guard = new PlanAwareMutationGuard(
                alwaysApproveHuman("alice"), channel, new RiskTieredApprovalPolicy());
        guard.observe(null, true, null); // must not throw
    }

    @Test
    @DisplayName("observe with unknown id is a silent no-op (didn't come from this guard)")
    void observeUnknownIdIsNoOp() {
        InMemoryPlanChannelAccessor channel = seededChannel();
        PlanAwareMutationGuard guard = new PlanAwareMutationGuard(
                alwaysApproveHuman("alice"), channel, new RiskTieredApprovalPolicy());
        guard.observe("not-a-real-id", true, null);
        // Channel is unchanged
        EvolvingPlan p = channel.get();
        assertEquals(1, p.size()); // just the seed
    }

    @Test
    @DisplayName("double observe is idempotent — second call is ignored")
    void doubleObserveIsIdempotent() {
        InMemoryPlanChannelAccessor channel = seededChannel();
        PlanAwareMutationGuard guard = new PlanAwareMutationGuard(
                alwaysApproveHuman("alice"), channel, new RiskTieredApprovalPolicy());

        MutationGuard.Decision d = guard.check(plan(MutationPlan.RiskLevel.LOW));
        guard.observe(d.correlationId(), true, "first");
        guard.observe(d.correlationId(), false, "second"); // should NOT flip EXECUTED → REJECTED

        PlanEntry final_ = channel.get().byId(d.correlationId()).orElseThrow();
        assertEquals(PlanEntry.Status.EXECUTED, final_.status());
        assertTrue(final_.reason().contains("first"));
    }


    @Test
    @DisplayName("late successful observe does not overwrite a terminal operator rejection")
    void observeDoesNotOverwriteTerminalDecision() {
        InMemoryPlanChannelAccessor channel = seededChannel();
        PlanAwareMutationGuard guard = new PlanAwareMutationGuard(
                alwaysApproveHuman("alice"), channel, new RiskTieredApprovalPolicy());

        MutationGuard.Decision d = guard.check(plan(MutationPlan.RiskLevel.LOW));
        channel.markStatus(d.correlationId(), PlanEntry.Status.REJECTED, "operator override", "bob");

        guard.observe(d.correlationId(), true, "late tool success");

        PlanEntry final_ = channel.get().byId(d.correlationId()).orElseThrow();
        assertEquals(PlanEntry.Status.REJECTED, final_.status());
        assertEquals("operator override", final_.reason());
        assertEquals("bob", final_.decidedBy());
    }
    @Test
    @DisplayName("end-to-end: bandit gets +1 reward when an auto-approved entry executes successfully")
    void banditLearnsFromSuccessfulExecution() {
        InMemoryPlanChannelAccessor channel = seededChannel();
        BanditApprovalPolicy bandit = new BanditApprovalPolicy(
                BanditApprovalPolicy.DEFAULT_BUCKET_KEY, /*warmup*/ 0);
        BanditApprovalTrainer.attach(channel, bandit);

        PlanAwareMutationGuard guard = new PlanAwareMutationGuard(
                alwaysApproveHuman("alice"), channel, new RiskTieredApprovalPolicy());

        // 30 successful executions of LOW-risk auto-approvals
        for (int i = 0; i < 30; i++) {
            MutationGuard.Decision d = guard.check(plan(MutationPlan.RiskLevel.LOW));
            assertEquals(PlanAwareMutationGuard.DECIDED_BY_POLICY, d.decidedBy());
            guard.observe(d.correlationId(), true, "ran ok");
        }

        String key = "tool:os_filesystem:" + Risk.LOW;
        assertTrue(bandit.observationCount(key) >= 30);
        assertTrue(bandit.meanSuccessRate(key) > 0.85,
                "Expected high success rate after 30 EXECUTED transitions; got " + bandit.meanSuccessRate(key));
    }

    @Test
    @DisplayName("end-to-end: bandit gets -1 reward when an auto-approved entry's execution fails")
    void banditLearnsFromExecutionFailure() {
        InMemoryPlanChannelAccessor channel = seededChannel();
        BanditApprovalPolicy bandit = new BanditApprovalPolicy(
                BanditApprovalPolicy.DEFAULT_BUCKET_KEY, /*warmup*/ 0);
        BanditApprovalTrainer.attach(channel, bandit);

        PlanAwareMutationGuard guard = new PlanAwareMutationGuard(
                alwaysApproveHuman("alice"), channel, new RiskTieredApprovalPolicy());

        for (int i = 0; i < 30; i++) {
            MutationGuard.Decision d = guard.check(plan(MutationPlan.RiskLevel.LOW));
            guard.observe(d.correlationId(), false, "io error");
        }

        String key = "tool:os_filesystem:" + Risk.LOW;
        assertTrue(bandit.observationCount(key) >= 30);
        assertTrue(bandit.meanSuccessRate(key) < 0.15,
                "Expected low success rate after 30 failures; got " + bandit.meanSuccessRate(key));
    }
}
