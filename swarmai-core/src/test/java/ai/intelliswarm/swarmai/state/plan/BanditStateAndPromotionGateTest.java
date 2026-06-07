package ai.intelliswarm.swarmai.state.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("BanditState snapshot/restore + BanditPromotionGate Tests")
class BanditStateAndPromotionGateTest {

    private static PlanEntry entry(String id, Risk risk) {
        return PlanEntry.builder()
                .id(id).proposedBy("tool:x").payload("op-" + id).risk(risk).build();
    }

    @Nested
    @DisplayName("BanditState round-trip")
    class StateRoundTrip {

        @Test
        @DisplayName("snapshot then restore preserves per-bucket Beta state")
        void snapshotRestoreRoundTrip() {
            BanditApprovalPolicy original = new BanditApprovalPolicy(
                    BanditApprovalPolicy.DEFAULT_BUCKET_KEY, 0);

            // Train on 30 observations across 2 buckets
            for (int i = 0; i < 20; i++) {
                original.recordOutcome(entry("a" + i, Risk.LOW), 1.0);
            }
            for (int i = 0; i < 10; i++) {
                original.recordOutcome(entry("b" + i, Risk.HIGH), -1.0);
            }

            BanditState snapshot = original.snapshot();

            BanditApprovalPolicy restored = new BanditApprovalPolicy(
                    BanditApprovalPolicy.DEFAULT_BUCKET_KEY, 0);
            restored.restore(snapshot);

            assertEquals(original.observationCount("tool:x:" + Risk.LOW),
                    restored.observationCount("tool:x:" + Risk.LOW));
            assertEquals(original.meanSuccessRate("tool:x:" + Risk.LOW),
                    restored.meanSuccessRate("tool:x:" + Risk.LOW), 1e-9);
            assertEquals(original.meanSuccessRate("tool:x:" + Risk.HIGH),
                    restored.meanSuccessRate("tool:x:" + Risk.HIGH), 1e-9);
        }

        @Test
        @DisplayName("snapshot of an empty bandit is an empty state")
        void emptyBanditEmptySnapshot() {
            BanditApprovalPolicy bandit = new BanditApprovalPolicy();
            BanditState snapshot = bandit.snapshot();
            assertTrue(snapshot.isEmpty());
        }

        @Test
        @DisplayName("BucketState rejects non-positive Beta parameters")
        void bucketStateRejectsBadParams() {
            assertThrows(IllegalArgumentException.class,
                    () -> new BanditState.BucketState(0.0, 1.0, 1.0, 1.0));
            assertThrows(IllegalArgumentException.class,
                    () -> new BanditState.BucketState(1.0, -0.5, 1.0, 1.0));
        }

        @Test
        @DisplayName("Builder produces an iterable BanditState")
        void builderRoundTrip() {
            BanditState state = BanditState.builder()
                    .bucket("k1", 5, 1, 1, 5)
                    .bucket("k2", 2, 8, 8, 2)
                    .build();
            assertEquals(2, state.bucketCount());
            assertNotNull(state.buckets().get("k1"));
            assertEquals(5.0 / 6.0, state.buckets().get("k1").meanAuto(), 1e-9);
        }
    }

    @Nested
    @DisplayName("BanditPromotionGate")
    class PromotionGate {

        @Test
        @DisplayName("rejects negative minObservations and out-of-range half-width")
        void invalidConfig() {
            BanditApprovalPolicy bandit = new BanditApprovalPolicy();
            assertThrows(IllegalArgumentException.class,
                    () -> new BanditPromotionGate(bandit, PlanApprovalPolicy.alwaysHuman(), -1, 0.1));
            assertThrows(IllegalArgumentException.class,
                    () -> new BanditPromotionGate(bandit, PlanApprovalPolicy.alwaysHuman(), 10, 0.0));
            assertThrows(IllegalArgumentException.class,
                    () -> new BanditPromotionGate(bandit, PlanApprovalPolicy.alwaysHuman(), 10, 1.0));
        }

        @Test
        @DisplayName("unseen bucket: gate falls back, isReady=false")
        void unseenBucketDefersToFallback() {
            BanditApprovalPolicy bandit = new BanditApprovalPolicy();
            BanditPromotionGate gate = new BanditPromotionGate(bandit, PlanApprovalPolicy.alwaysHuman());

            assertFalse(gate.isReady("tool:x:" + Risk.LOW));
            PlanApprovalPolicy.Decision d = gate.decide(EvolvingPlan.empty(),
                    entry("a", Risk.LOW));
            assertEquals(PlanApprovalPolicy.Decision.REQUIRES_HUMAN, d);
        }

        @Test
        @DisplayName("bucket below minObservations: defers to fallback")
        void belowMinObsDefersToFallback() {
            BanditApprovalPolicy bandit = new BanditApprovalPolicy(
                    BanditApprovalPolicy.DEFAULT_BUCKET_KEY, 0);
            // Drive only 5 observations — below default min=30
            for (int i = 0; i < 5; i++) {
                bandit.recordOutcome(entry("e" + i, Risk.LOW), 1.0);
            }
            BanditPromotionGate gate = new BanditPromotionGate(bandit, PlanApprovalPolicy.alwaysHuman());
            assertFalse(gate.isReady("tool:x:" + Risk.LOW));
        }

        @Test
        @DisplayName("bucket with abundant evidence becomes ready and starts using bandit")
        void abundantEvidencePromotes() {
            BanditApprovalPolicy bandit = new BanditApprovalPolicy(
                    BanditApprovalPolicy.DEFAULT_BUCKET_KEY, 0);
            // Drive 200 strongly-positive observations; CI will tighten well below 0.10.
            for (int i = 0; i < 200; i++) {
                bandit.recordOutcome(entry("e" + i, Risk.LOW), 1.0);
            }
            BanditPromotionGate gate = new BanditPromotionGate(bandit, PlanApprovalPolicy.alwaysHuman(),
                    /*minObs*/ 30, /*maxCiHalfWidth*/ 0.10);

            assertTrue(gate.isReady("tool:x:" + Risk.LOW));
            assertTrue(gate.readyBuckets().contains("tool:x:" + Risk.LOW));

            // Many trials — bandit should overwhelmingly say AUTO_APPROVE
            int autos = 0;
            for (int i = 0; i < 200; i++) {
                if (gate.decide(EvolvingPlan.empty(), entry("probe", Risk.LOW))
                        == PlanApprovalPolicy.Decision.AUTO_APPROVE) {
                    autos++;
                }
            }
            assertTrue(autos > 190,
                    "Expected ~all AUTO_APPROVE for confident-positive bucket; got " + autos + "/200");
        }

        @Test
        @DisplayName("readyBuckets reports a stable view of the snapshot")
        void readyBucketsView() {
            BanditApprovalPolicy bandit = new BanditApprovalPolicy(
                    BanditApprovalPolicy.DEFAULT_BUCKET_KEY, 0);
            for (int i = 0; i < 200; i++) {
                bandit.recordOutcome(entry("a" + i, Risk.LOW), 1.0);
            }
            for (int i = 0; i < 5; i++) {
                bandit.recordOutcome(entry("b" + i, Risk.MEDIUM), 1.0);
            }
            BanditPromotionGate gate = new BanditPromotionGate(bandit, PlanApprovalPolicy.alwaysHuman());
            var ready = gate.readyBuckets();
            assertTrue(ready.contains("tool:x:" + Risk.LOW));
            assertFalse(ready.contains("tool:x:" + Risk.MEDIUM));
        }

        @Test
        @DisplayName("halfWidth shrinks as evidence grows")
        void halfWidthShrinks() {
            // Beta(2, 2) → CI is wide; Beta(50, 50) → CI is narrow.
            double wide = BanditPromotionGate.halfWidth(2, 2);
            double narrow = BanditPromotionGate.halfWidth(50, 50);
            assertTrue(narrow < wide, "narrow=" + narrow + " wide=" + wide);
            // Sanity: Beta(50,50) std ≈ sqrt(50*50/(100*100*101)) = 0.0497, half = 0.0975
            assertTrue(narrow < 0.10);
        }
    }
}
