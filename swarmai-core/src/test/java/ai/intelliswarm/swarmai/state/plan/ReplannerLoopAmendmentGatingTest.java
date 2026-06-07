package ai.intelliswarm.swarmai.state.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the amendment re-gating added to {@link ReplannerLoop}: amendment
 * entries should be subject to the same {@link PlanApprovalPolicy} that gates
 * tool calls, instead of being appended raw.
 */
@DisplayName("ReplannerLoop amendment re-gating")
class ReplannerLoopAmendmentGatingTest {

    private static PlanEntry proposed(String id, Risk risk) {
        return PlanEntry.builder()
                .id(id).proposedBy("replanner").payload("op-" + id).risk(risk)
                .metadata("summary", "do " + id)
                .build();
    }

    private static PlanEntry seedEntry(String id) {
        return PlanEntry.builder()
                .id(id).proposedBy("tool:x").payload("seed").risk(Risk.MEDIUM).build();
    }

    private static InMemoryPlanChannelAccessor seededChannel() {
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        channel.append(seedEntry("seed"));
        channel.markStatus("seed", PlanEntry.Status.APPROVED, "operator", "alice");
        return channel;
    }

    private static Replanner emitting(PlanEntry... newEntries) {
        return (plan, ctx) -> {
            Replanner.Amendments.Builder b = Replanner.Amendments.builder();
            for (PlanEntry e : newEntries) b.addEntry(e);
            return b.build();
        };
    }

    @Test
    @DisplayName("legacy: no amendmentPolicy → amendment stays PROPOSED")
    void legacyNoPolicyLeavesProposed() {
        InMemoryPlanChannelAccessor channel = seededChannel();
        Replanner r = emitting(proposed("amend-1", Risk.LOW));

        // No amendmentPolicy in the start signature — legacy path
        ReplannerLoop loop = ReplannerLoop.start(channel, r,
                ReplanTriggerPolicy.always(),
                () -> ReplanContext.of("goal"));

        // Trigger by appending an unrelated entry; the loop fires.
        channel.append(seedEntry("trigger"));

        PlanEntry amendment = channel.get().byId("amend-1").orElseThrow();
        assertEquals(PlanEntry.Status.PROPOSED, amendment.status(),
                "Without amendmentPolicy, amendments must stay PROPOSED");
        assertNull(amendment.decidedBy(),
                "decidedBy should be unset on a PROPOSED amendment");
        loop.close();
    }

    @Test
    @DisplayName("amendmentPolicy=alwaysAuto → amendment transitions to APPROVED with PLAN_POLICY attribution")
    void alwaysAutoApprovesAmendment() {
        InMemoryPlanChannelAccessor channel = seededChannel();
        Replanner r = emitting(proposed("amend-1", Risk.LOW));

        ReplannerLoop loop = ReplannerLoop.start(channel, r,
                ReplanTriggerPolicy.always(),
                () -> ReplanContext.of("goal"),
                PlanApprovalPolicy.alwaysAuto());

        channel.append(seedEntry("trigger"));

        PlanEntry amendment = channel.get().byId("amend-1").orElseThrow();
        assertEquals(PlanEntry.Status.APPROVED, amendment.status());
        assertEquals(ReplannerLoop.DECIDED_BY_POLICY, amendment.decidedBy(),
                "Auto-approved amendments must carry PLAN_POLICY attribution so the trainer counts them");
        loop.close();
    }

    @Test
    @DisplayName("amendmentPolicy=alwaysHuman → amendment stays PROPOSED")
    void alwaysHumanLeavesProposed() {
        InMemoryPlanChannelAccessor channel = seededChannel();
        Replanner r = emitting(proposed("amend-1", Risk.LOW));

        ReplannerLoop loop = ReplannerLoop.start(channel, r,
                ReplanTriggerPolicy.always(),
                () -> ReplanContext.of("goal"),
                PlanApprovalPolicy.alwaysHuman());

        channel.append(seedEntry("trigger"));

        PlanEntry amendment = channel.get().byId("amend-1").orElseThrow();
        assertEquals(PlanEntry.Status.PROPOSED, amendment.status(),
                "REQUIRES_HUMAN amendments must stay PROPOSED for operator review");
        loop.close();
    }

    @Test
    @DisplayName("composed RiskTier+DriftBudget on a seeded plan auto-approves a LOW-risk amendment")
    void riskTieredApprovesLowRiskAmendmentOnSeededPlan() {
        InMemoryPlanChannelAccessor channel = seededChannel();
        Replanner r = emitting(proposed("amend-1", Risk.LOW));
        PlanApprovalPolicy policy = PlanApprovalPolicy.all(
                new RiskTieredApprovalPolicy(),
                new DriftBudgetApprovalPolicy(5));

        ReplannerLoop loop = ReplannerLoop.start(channel, r,
                ReplanTriggerPolicy.always(),
                () -> ReplanContext.of("goal"),
                policy);

        channel.append(seedEntry("trigger"));

        PlanEntry amendment = channel.get().byId("amend-1").orElseThrow();
        assertEquals(PlanEntry.Status.APPROVED, amendment.status());
        assertEquals(ReplannerLoop.DECIDED_BY_POLICY, amendment.decidedBy());
        loop.close();
    }

    @Test
    @DisplayName("RiskTier vetoes HIGH-risk amendments → leaves PROPOSED awaiting human")
    void riskTieredVetoesHighRiskAmendment() {
        InMemoryPlanChannelAccessor channel = seededChannel();
        Replanner r = emitting(proposed("amend-1", Risk.HIGH));
        PlanApprovalPolicy policy = PlanApprovalPolicy.all(
                new RiskTieredApprovalPolicy(),
                new DriftBudgetApprovalPolicy(5));

        ReplannerLoop loop = ReplannerLoop.start(channel, r,
                ReplanTriggerPolicy.always(),
                () -> ReplanContext.of("goal"),
                policy);

        channel.append(seedEntry("trigger"));

        PlanEntry amendment = channel.get().byId("amend-1").orElseThrow();
        assertEquals(PlanEntry.Status.PROPOSED, amendment.status(),
                "HIGH-risk amendments must stay PROPOSED; human gate is the right next step");
        loop.close();
    }

    @Test
    @DisplayName("supersession replacement is gated identically; old entry still SUPERSEDED")
    void supersessionReplacementIsGated() {
        InMemoryPlanChannelAccessor channel = seededChannel();
        // Pre-existing entry to be superseded
        channel.append(proposed("old-1", Risk.LOW));

        PlanEntry replacement = PlanEntry.builder()
                .id("new-1").proposedBy("replanner").payload("better")
                .risk(Risk.LOW).supersedes("old-1")
                .build();

        Replanner r = (plan, ctx) -> Replanner.Amendments.builder()
                .supersede("old-1", replacement).build();

        ReplannerLoop loop = ReplannerLoop.start(channel, r,
                ReplanTriggerPolicy.always(),
                () -> ReplanContext.of("goal"),
                PlanApprovalPolicy.alwaysAuto());

        // Trigger
        channel.append(seedEntry("trigger"));

        PlanEntry oldEntry = channel.get().byId("old-1").orElseThrow();
        PlanEntry newEntry = channel.get().byId("new-1").orElseThrow();

        assertEquals(PlanEntry.Status.SUPERSEDED, oldEntry.status());
        assertEquals(ReplannerLoop.DECIDED_BY_REPLANNER, oldEntry.decidedBy(),
                "Supersede transition must still attribute to REPLANNER");
        assertEquals(PlanEntry.Status.APPROVED, newEntry.status(),
                "Replacement must go through the same amendment gate as fresh entries");
        assertEquals(ReplannerLoop.DECIDED_BY_POLICY, newEntry.decidedBy());
        loop.close();
    }

    @Test
    @DisplayName("the loop's reentrancy guard still prevents recursion when gating triggers more transitions")
    void reentrancyGuardCoversAmendmentGatingTransitions() {
        InMemoryPlanChannelAccessor channel = seededChannel();
        AtomicInteger replannerCalls = new AtomicInteger(0);
        Replanner r = (plan, ctx) -> {
            replannerCalls.incrementAndGet();
            // Emit one amendment per call
            return Replanner.Amendments.builder()
                    .addEntry(proposed("amend-" + replannerCalls.get(), Risk.LOW))
                    .build();
        };

        ReplannerLoop loop = ReplannerLoop.start(channel, r,
                ReplanTriggerPolicy.always(),
                () -> ReplanContext.of("goal"),
                PlanApprovalPolicy.alwaysAuto());

        // Single external trigger
        channel.append(seedEntry("trigger"));

        // Without the reentrancy guard, the gate's APPROVED transition would
        // re-fire the trigger and we'd recurse. With it, the replanner is
        // called exactly once per external transition.
        assertEquals(1, replannerCalls.get(),
                "Replanner should be called once per external transition; got " + replannerCalls.get());
        loop.close();
    }

    @Test
    @DisplayName("end-to-end: bandit trainer picks up auto-approved amendment as training signal")
    void banditTrainerObservesAutoApprovedAmendment() {
        InMemoryPlanChannelAccessor channel = seededChannel();

        BanditApprovalPolicy bandit = new BanditApprovalPolicy(
                BanditApprovalPolicy.DEFAULT_BUCKET_KEY, 0);
        BanditApprovalTrainer.attach(channel, bandit);

        Replanner r = emitting(proposed("amend-1", Risk.LOW));

        ReplannerLoop loop = ReplannerLoop.start(channel, r,
                ReplanTriggerPolicy.always(),
                () -> ReplanContext.of("goal"),
                PlanApprovalPolicy.alwaysAuto());

        channel.append(seedEntry("trigger"));
        // Now mark the amendment as EXECUTED — this transition (APPROVED → EXECUTED)
        // is what the trainer will count, because the prior decidedBy was PLAN_POLICY.
        channel.markStatus("amend-1", PlanEntry.Status.EXECUTED, "ran ok");

        String key = "replanner:" + Risk.LOW;
        assertTrue(bandit.observationCount(key) >= 1,
                "Bandit should have observed at least one PLAN_POLICY → EXECUTED transition; got "
                        + bandit.observationCount(key));
        loop.close();
    }

    @Test
    @DisplayName("when amendments fail the policy and stay PROPOSED, the bandit does not falsely train on them")
    void noBanditPoisoningFromHumanRoutedAmendment() {
        InMemoryPlanChannelAccessor channel = seededChannel();
        BanditApprovalPolicy bandit = new BanditApprovalPolicy(
                BanditApprovalPolicy.DEFAULT_BUCKET_KEY, 0);
        BanditApprovalTrainer.attach(channel, bandit);

        Replanner r = emitting(proposed("amend-1", Risk.HIGH));

        ReplannerLoop loop = ReplannerLoop.start(channel, r,
                ReplanTriggerPolicy.always(),
                () -> ReplanContext.of("goal"),
                new RiskTieredApprovalPolicy());  // HIGH always routes to human

        channel.append(seedEntry("trigger"));

        // amend-1 stays PROPOSED. If alice approves and the entry executes,
        // the prior decidedBy would be "alice" — trainer's filter must skip that.
        channel.markStatus("amend-1", PlanEntry.Status.APPROVED, "alice", "alice");
        channel.markStatus("amend-1", PlanEntry.Status.EXECUTED, "ran");

        String key = "replanner:" + Risk.HIGH;
        // Bandit's bucket — alice's transitions don't count
        assertEquals(0, bandit.observationCount(key),
                "Bandit must NOT learn from human-decided transitions on amendments");
        loop.close();
    }
}
