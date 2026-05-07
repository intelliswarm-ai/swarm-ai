package ai.intelliswarm.swarmai.state.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("decidedBy semantics across PlanEntry / EvolvingPlan / Trainer")
class DecidedByTest {

    private static PlanEntry proposed(String id, Risk risk) {
        return PlanEntry.builder()
                .id(id).proposedBy("tool:x").payload("op-" + id).risk(risk).build();
    }

    @Nested
    @DisplayName("PlanEntry.decidedBy")
    class EntryField {

        @Test
        @DisplayName("a freshly built PROPOSED entry has null decidedBy")
        void freshEntryHasNullDecidedBy() {
            PlanEntry e = proposed("a", Risk.LOW);
            assertNull(e.decidedBy());
        }

        @Test
        @DisplayName("withStatus(status, reason) preserves the prior decidedBy")
        void threeArgWithStatusPreserves() {
            PlanEntry e = proposed("a", Risk.LOW)
                    .withStatus(PlanEntry.Status.APPROVED, "policy", "PLAN_POLICY");
            assertEquals("PLAN_POLICY", e.decidedBy());

            PlanEntry executed = e.withStatus(PlanEntry.Status.EXECUTED, "ran ok");
            // Carried forward
            assertEquals("PLAN_POLICY", executed.decidedBy());
        }

        @Test
        @DisplayName("withStatus(status, reason, decidedBy) overwrites attribution")
        void fourArgOverwrites() {
            PlanEntry e = proposed("a", Risk.LOW)
                    .withStatus(PlanEntry.Status.APPROVED, "policy", "PLAN_POLICY")
                    .withStatus(PlanEntry.Status.REJECTED, "operator override", "alice");
            assertEquals("alice", e.decidedBy());
            assertEquals(PlanEntry.Status.REJECTED, e.status());
        }

        @Test
        @DisplayName("withStatus(status, reason, null) clears attribution explicitly")
        void fourArgClearsToNull() {
            PlanEntry e = proposed("a", Risk.LOW)
                    .withStatus(PlanEntry.Status.APPROVED, "policy", "PLAN_POLICY")
                    .withStatus(PlanEntry.Status.SUPERSEDED, "replanner", null);
            assertNull(e.decidedBy());
        }

        @Test
        @DisplayName("Builder.decidedBy supports constructing entries with attribution upfront")
        void builderCarriesDecidedBy() {
            PlanEntry e = PlanEntry.builder()
                    .id("a").proposedBy("tool:x").payload("op")
                    .risk(Risk.LOW)
                    .status(PlanEntry.Status.APPROVED)
                    .decidedBy("alice")
                    .build();
            assertEquals("alice", e.decidedBy());
        }
    }

    @Nested
    @DisplayName("EvolvingPlan.markStatus 4-arg overload")
    class PlanMarkStatus {

        @Test
        @DisplayName("4-arg sets decidedBy on the entry")
        void fourArgPropagates() {
            EvolvingPlan p = EvolvingPlan.empty()
                    .with(proposed("a", Risk.LOW))
                    .markStatus("a", PlanEntry.Status.APPROVED, "ok", "PLAN_POLICY");
            assertEquals("PLAN_POLICY", p.byId("a").orElseThrow().decidedBy());
        }

        @Test
        @DisplayName("3-arg preserves the existing decidedBy through subsequent transitions")
        void threeArgPreserves() {
            EvolvingPlan p = EvolvingPlan.empty()
                    .with(proposed("a", Risk.LOW))
                    .markStatus("a", PlanEntry.Status.APPROVED, "ok", "PLAN_POLICY")
                    .markStatus("a", PlanEntry.Status.EXECUTED, "ran"); // 3-arg, preserves
            assertEquals("PLAN_POLICY", p.byId("a").orElseThrow().decidedBy());
        }
    }

    @Nested
    @DisplayName("BanditApprovalTrainer decidedBy filter")
    class TrainerFilter {

        @Test
        @DisplayName("trainer (default filter) ignores transitions decided by humans")
        void humansAreSkipped() {
            InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
            BanditApprovalPolicy bandit = new BanditApprovalPolicy(
                    BanditApprovalPolicy.DEFAULT_BUCKET_KEY, /*warmup*/ 0);
            BanditApprovalTrainer.attach(channel, bandit); // default filter = PLAN_POLICY

            for (int i = 0; i < 20; i++) {
                channel.append(proposed("e" + i, Risk.LOW));
                channel.markStatus("e" + i, PlanEntry.Status.APPROVED, "human approved", "alice");
                channel.markStatus("e" + i, PlanEntry.Status.EXECUTED, "ran");
            }

            String key = "tool:x:" + Risk.LOW;
            assertEquals(0, bandit.observationCount(key),
                    "Bandit should not learn from human-decided transitions when filter is on");
        }

        @Test
        @DisplayName("trainer (default filter) trains on PLAN_POLICY-decided transitions")
        void policyTransitionsAreScored() {
            InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
            BanditApprovalPolicy bandit = new BanditApprovalPolicy(
                    BanditApprovalPolicy.DEFAULT_BUCKET_KEY, /*warmup*/ 0);
            BanditApprovalTrainer.attach(channel, bandit);

            for (int i = 0; i < 20; i++) {
                channel.append(proposed("e" + i, Risk.LOW));
                channel.markStatus("e" + i, PlanEntry.Status.APPROVED,
                        "policy", BanditApprovalTrainer.DEFAULT_SCORED_DECIDED_BY);
                channel.markStatus("e" + i, PlanEntry.Status.EXECUTED, "ran");
            }

            String key = "tool:x:" + Risk.LOW;
            assertTrue(bandit.observationCount(key) >= 20,
                    "Bandit should train on every PLAN_POLICY transition; got " + bandit.observationCount(key));
        }

        @Test
        @DisplayName("attachUnfiltered trains on every transition regardless of decidedBy")
        void unfilteredTrainsOnEverything() {
            InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
            BanditApprovalPolicy bandit = new BanditApprovalPolicy(
                    BanditApprovalPolicy.DEFAULT_BUCKET_KEY, /*warmup*/ 0);
            BanditApprovalTrainer.attachUnfiltered(channel, bandit);

            // Mix human and policy decisions
            for (int i = 0; i < 10; i++) {
                channel.append(proposed("h" + i, Risk.LOW));
                channel.markStatus("h" + i, PlanEntry.Status.APPROVED, "human", "alice");
                channel.markStatus("h" + i, PlanEntry.Status.EXECUTED, "ran");
            }
            for (int i = 0; i < 10; i++) {
                channel.append(proposed("p" + i, Risk.LOW));
                channel.markStatus("p" + i, PlanEntry.Status.APPROVED, "policy", "PLAN_POLICY");
                channel.markStatus("p" + i, PlanEntry.Status.EXECUTED, "ran");
            }

            String key = "tool:x:" + Risk.LOW;
            assertTrue(bandit.observationCount(key) >= 20,
                    "Unfiltered trainer should count all 20; got " + bandit.observationCount(key));
        }

        @Test
        @DisplayName("mixed scenario: bandit sees only its own decisions, ignoring human-routed ones")
        void mixedScenarioIsolatesBanditSignal() {
            InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
            BanditApprovalPolicy bandit = new BanditApprovalPolicy(
                    BanditApprovalPolicy.DEFAULT_BUCKET_KEY, /*warmup*/ 0);
            BanditApprovalTrainer.attach(channel, bandit);

            // 30 policy decisions that all FAILED — bandit should learn this is bad.
            for (int i = 0; i < 30; i++) {
                channel.append(proposed("p" + i, Risk.LOW));
                channel.markStatus("p" + i, PlanEntry.Status.APPROVED, "policy",
                        BanditApprovalTrainer.DEFAULT_SCORED_DECIDED_BY);
                channel.markStatus("p" + i, PlanEntry.Status.REJECTED, "execution failed");
            }
            // 30 human decisions that all SUCCEEDED — must NOT improve the bandit's view.
            for (int i = 0; i < 30; i++) {
                channel.append(proposed("h" + i, Risk.LOW));
                channel.markStatus("h" + i, PlanEntry.Status.APPROVED, "human", "alice");
                channel.markStatus("h" + i, PlanEntry.Status.EXECUTED, "ran");
            }

            String key = "tool:x:" + Risk.LOW;
            assertEquals(30, bandit.observationCount(key),
                    "Only the 30 policy transitions should have been counted");
            assertTrue(bandit.meanSuccessRate(key) < 0.15,
                    "Bandit should see only failures, mean below 0.15; got " + bandit.meanSuccessRate(key));
        }
    }
}
