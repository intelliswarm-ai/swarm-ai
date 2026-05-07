package ai.intelliswarm.swarmai.state.plan;

import ai.intelliswarm.swarmai.rl.bandit.ThompsonSampling;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plugs coverage holes on the Path A surface — accessor methods that ship
 * but lack a direct test. Rolled into one file so the gaps are easy to
 * inventory and track later.
 */
@DisplayName("Path A coverage gaps")
class PathACoverageGapsTest {

    private static PlanEntry entry(String id, Risk risk) {
        return PlanEntry.builder()
                .id(id).proposedBy("tool:x").payload("op-" + id).risk(risk).build();
    }

    @Nested
    @DisplayName("BanditApprovalPolicy accessors")
    class BanditAccessors {

        @Test
        @DisplayName("bucketState(unseen) returns empty without allocating a bucket")
        void unseenBucketStateIsEmpty() {
            BanditApprovalPolicy bandit = new BanditApprovalPolicy(
                    BanditApprovalPolicy.DEFAULT_BUCKET_KEY, 0);
            assertTrue(bandit.bucketState("never-seen-key").isEmpty());
            // Confirm we didn't accidentally lazily allocate
            assertTrue(bandit.bucketKeys().isEmpty());
        }

        @Test
        @DisplayName("bucketKeys() reflects every bucket the bandit has been trained on")
        void bucketKeysCoverObservedSet() {
            BanditApprovalPolicy bandit = new BanditApprovalPolicy(
                    BanditApprovalPolicy.DEFAULT_BUCKET_KEY, 0);
            bandit.recordOutcome(entry("a", Risk.LOW), 1.0);
            bandit.recordOutcome(entry("b", Risk.HIGH), -1.0);
            bandit.recordOutcome(entry("c", Risk.LOW), 1.0); // same bucket as 'a'

            Set<String> keys = bandit.bucketKeys();
            assertEquals(2, keys.size(), "Expected 2 distinct buckets, got: " + keys);
            assertTrue(keys.contains("tool:x:" + Risk.LOW));
            assertTrue(keys.contains("tool:x:" + Risk.HIGH));
        }

        @Test
        @DisplayName("bucketKeys() returns an immutable view")
        void bucketKeysImmutable() {
            BanditApprovalPolicy bandit = new BanditApprovalPolicy();
            bandit.recordOutcome(entry("a", Risk.LOW), 1.0);
            assertThrows(UnsupportedOperationException.class,
                    () -> bandit.bucketKeys().add("tool:hack:LOW"));
        }

        @Test
        @DisplayName("keyFor(entry) is consistent with the function the bandit uses internally")
        void keyForMatchesInternal() {
            BanditApprovalPolicy bandit = new BanditApprovalPolicy(
                    e -> "ALL", 0);
            PlanEntry a = entry("a", Risk.LOW);
            PlanEntry b = entry("b", Risk.HIGH);
            assertEquals("ALL", bandit.keyFor(a));
            assertEquals("ALL", bandit.keyFor(b));

            // Default bucket key encodes proposedBy + risk
            BanditApprovalPolicy d = new BanditApprovalPolicy();
            assertEquals("tool:x:" + Risk.LOW, d.keyFor(a));
        }
    }

    @Nested
    @DisplayName("BanditState helpers")
    class BanditStateHelpers {

        @Test
        @DisplayName("BanditState.empty() is empty and has bucketCount=0")
        void emptyState() {
            BanditState s = BanditState.empty();
            assertTrue(s.isEmpty());
            assertEquals(0, s.bucketCount());
        }

        @Test
        @DisplayName("BucketState.observationCount equals updates beyond Beta(1,1) prior")
        void observationCountFormula() {
            // Beta(1,1) prior + 5 successes + 3 failures on auto arm → α=6, β=4 → obs=8
            BanditState.BucketState s = new BanditState.BucketState(6.0, 4.0, 4.0, 6.0);
            assertEquals(8L, s.observationCount());
            // Beta(1,1) untouched → obs=0
            assertEquals(0L, new BanditState.BucketState(1.0, 1.0, 1.0, 1.0).observationCount());
        }

        @Test
        @DisplayName("BucketState.meanAuto computes α/(α+β)")
        void meanAutoFormula() {
            BanditState.BucketState s = new BanditState.BucketState(9.0, 1.0, 1.0, 9.0);
            assertEquals(0.9, s.meanAuto(), 1e-12);
        }

        @Test
        @DisplayName("BanditState constructor copies the buckets map (defensive)")
        void constructorDefensiveCopy() {
            java.util.Map<String, BanditState.BucketState> mutable = new java.util.HashMap<>();
            mutable.put("k", new BanditState.BucketState(2, 1, 1, 2));
            BanditState s = new BanditState(mutable);
            mutable.put("hacked", new BanditState.BucketState(99, 1, 1, 99));
            assertEquals(1, s.bucketCount(),
                    "BanditState must defensively copy its buckets map");
        }

        @Test
        @DisplayName("Builder is order-preserving (LinkedHashMap)")
        void builderPreservesOrder() {
            BanditState s = BanditState.builder()
                    .bucket("z", 2, 1, 1, 2)
                    .bucket("a", 1, 2, 2, 1)
                    .bucket("m", 5, 5, 5, 5)
                    .build();
            assertEquals(java.util.List.of("z", "a", "m"),
                    java.util.List.copyOf(s.buckets().keySet()));
        }
    }

    @Nested
    @DisplayName("ThompsonSampling.setParameters edge cases")
    class SetParametersEdges {

        @Test
        @DisplayName("action out of range is silently ignored (consistent with update())")
        void outOfRangeActionIgnored() {
            ThompsonSampling ts = new ThompsonSampling(2);
            ts.setParameters(99, 5.0, 5.0); // no-op
            // State unchanged
            assertEquals(0.5, ts.getMean(0), 1e-12);
            assertEquals(0.5, ts.getMean(1), 1e-12);
        }

        @Test
        @DisplayName("non-positive alpha or beta throws (Beta is undefined for these)")
        void nonPositiveRejected() {
            ThompsonSampling ts = new ThompsonSampling(2);
            assertThrows(IllegalArgumentException.class, () -> ts.setParameters(0, 0.0, 1.0));
            assertThrows(IllegalArgumentException.class, () -> ts.setParameters(0, 1.0, 0.0));
            assertThrows(IllegalArgumentException.class, () -> ts.setParameters(0, -1.0, 1.0));
        }

        @Test
        @DisplayName("setParameters then getParameters round-trips")
        void roundTripsThroughGetParameters() {
            ThompsonSampling ts = new ThompsonSampling(2);
            ts.setParameters(0, 17.5, 3.25);
            double[] got = ts.getParameters(0);
            assertEquals(17.5, got[0], 1e-12);
            assertEquals(3.25, got[1], 1e-12);
        }
    }

    @Nested
    @DisplayName("BanditPromotionGate accessors")
    class GateAccessors {

        @Test
        @DisplayName("config accessors return what the constructor was given")
        void accessorsReportConfig() {
            BanditApprovalPolicy bandit = new BanditApprovalPolicy();
            BanditPromotionGate gate = new BanditPromotionGate(bandit,
                    PlanApprovalPolicy.alwaysHuman(), 42, 0.05);
            assertEquals(42, gate.minObservations());
            assertEquals(0.05, gate.maxCiHalfWidth(), 1e-12);
        }

        @Test
        @DisplayName("readyBuckets returns an immutable copy")
        void readyBucketsImmutable() {
            BanditApprovalPolicy bandit = new BanditApprovalPolicy(
                    BanditApprovalPolicy.DEFAULT_BUCKET_KEY, 0);
            for (int i = 0; i < 200; i++) {
                bandit.recordOutcome(entry("a" + i, Risk.LOW), 1.0);
            }
            BanditPromotionGate gate = new BanditPromotionGate(bandit, PlanApprovalPolicy.alwaysHuman());
            Set<String> ready = gate.readyBuckets();
            assertThrows(UnsupportedOperationException.class, () -> ready.add("hack"));
        }

        @Test
        @DisplayName("default-construction uses documented defaults")
        void defaultConfig() {
            BanditApprovalPolicy bandit = new BanditApprovalPolicy();
            BanditPromotionGate gate = new BanditPromotionGate(bandit, PlanApprovalPolicy.alwaysHuman());
            assertEquals(BanditPromotionGate.DEFAULT_MIN_OBSERVATIONS, gate.minObservations());
            assertEquals(BanditPromotionGate.DEFAULT_MAX_CI_HALF_WIDTH, gate.maxCiHalfWidth(), 1e-12);
        }
    }

    // Tests for MutationGuard.observe() default no-op and Decision.withCorrelationId
    // live in swarmai-tools/src/test/.../MutationGuardCoverageGapsTest.java
    // since MutationGuard is in the swarmai-tools module.
}
