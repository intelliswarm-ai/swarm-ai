package ai.intelliswarm.swarmai.state.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("BanditApprovalPolicy Tests")
class BanditApprovalPolicyTest {

    private static PlanEntry entry(String by, Risk risk) {
        return PlanEntry.builder().proposedBy(by).payload("op").risk(risk).build();
    }

    @Test
    @DisplayName("rejects negative warmup")
    void negativeWarmupRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new BanditApprovalPolicy(BanditApprovalPolicy.DEFAULT_BUCKET_KEY, -1));
    }

    @Test
    @DisplayName("cold buckets always require a human regardless of risk")
    void coldBucketsAlwaysHuman() {
        BanditApprovalPolicy policy = new BanditApprovalPolicy(
                BanditApprovalPolicy.DEFAULT_BUCKET_KEY, /*warmup*/ 5);
        EvolvingPlan plan = EvolvingPlan.empty();
        assertEquals(PlanApprovalPolicy.Decision.REQUIRES_HUMAN,
                policy.decide(plan, entry("tool:x", Risk.LOW)));
    }

    @Test
    @DisplayName("warmup is per-bucket — feeding bucket A doesn't unlock bucket B")
    void warmupIsPerBucket() {
        BanditApprovalPolicy policy = new BanditApprovalPolicy(
                BanditApprovalPolicy.DEFAULT_BUCKET_KEY, /*warmup*/ 3);

        // Saturate bucket "tool:x:LOW" with positive observations
        for (int i = 0; i < 10; i++) {
            policy.recordOutcome(entry("tool:x", Risk.LOW), 1.0);
        }
        // bucket "tool:y:LOW" is still cold
        assertEquals(PlanApprovalPolicy.Decision.REQUIRES_HUMAN,
                policy.decide(EvolvingPlan.empty(), entry("tool:y", Risk.LOW)));
    }

    @Test
    @DisplayName("after consistent positive evidence, bucket auto-approves")
    void positiveEvidenceUnlocksAutoApprove() {
        BanditApprovalPolicy policy = new BanditApprovalPolicy(
                BanditApprovalPolicy.DEFAULT_BUCKET_KEY, /*warmup*/ 5);

        PlanEntry probe = entry("tool:x", Risk.LOW);
        // 50 positive outcomes — Beta(51, 1) for action 0, Beta(1, 51) for action 1
        for (int i = 0; i < 50; i++) {
            policy.recordOutcome(probe, 1.0);
        }
        // Decision is sampled, but with these distributions action 0 dominates with overwhelming probability.
        // Run many trials and assert the vast majority are AUTO_APPROVE.
        int autos = 0;
        for (int i = 0; i < 200; i++) {
            if (policy.decide(EvolvingPlan.empty(), probe) == PlanApprovalPolicy.Decision.AUTO_APPROVE) {
                autos++;
            }
        }
        assertTrue(autos > 190,
                "Expected near-uniform AUTO_APPROVE after 50 positive observations, got " + autos + "/200");
    }

    @Test
    @DisplayName("after consistent negative evidence, bucket stays REQUIRES_HUMAN")
    void negativeEvidenceLocksOutAutoApprove() {
        BanditApprovalPolicy policy = new BanditApprovalPolicy(
                BanditApprovalPolicy.DEFAULT_BUCKET_KEY, /*warmup*/ 5);

        PlanEntry probe = entry("tool:x", Risk.LOW);
        for (int i = 0; i < 50; i++) {
            policy.recordOutcome(probe, -1.0);
        }
        int autos = 0;
        for (int i = 0; i < 200; i++) {
            if (policy.decide(EvolvingPlan.empty(), probe) == PlanApprovalPolicy.Decision.AUTO_APPROVE) {
                autos++;
            }
        }
        assertTrue(autos < 10,
                "Expected near-zero AUTO_APPROVE after 50 negative observations, got " + autos + "/200");
    }

    @Test
    @DisplayName("observationCount and meanSuccessRate report bucket state")
    void exposesBucketState() {
        BanditApprovalPolicy policy = new BanditApprovalPolicy(
                BanditApprovalPolicy.DEFAULT_BUCKET_KEY, /*warmup*/ 0);

        PlanEntry probe = entry("tool:x", Risk.MEDIUM);
        policy.recordOutcome(probe, 1.0);
        policy.recordOutcome(probe, 1.0);
        policy.recordOutcome(probe, 1.0);
        policy.recordOutcome(probe, -1.0);

        String key = "tool:x:" + Risk.MEDIUM;
        assertEquals(4L, policy.observationCount(key));
        // Beta(1,1) prior + 3 successes + 1 failure → α=4, β=2, mean = 4/6 ≈ 0.667
        assertTrue(policy.meanSuccessRate(key) > 0.6);
        assertTrue(policy.meanSuccessRate(key) < 0.75);
    }

    @Test
    @DisplayName("custom bucket key isolates learning by an arbitrary projection")
    void customBucketKey() {
        BanditApprovalPolicy policy = new BanditApprovalPolicy(
                e -> "ALL", /*warmup*/ 0);

        // Different proposers / risks all fall into the same bucket
        policy.recordOutcome(entry("tool:x", Risk.LOW), 1.0);
        policy.recordOutcome(entry("tool:y", Risk.HIGH), 1.0);
        policy.recordOutcome(entry("replanner", Risk.MEDIUM), 1.0);

        assertEquals(3L, policy.observationCount("ALL"));
    }

    @Test
    @DisplayName("any() composition lets a confident bandit bypass static rules")
    void anyCompositionBypass() {
        BanditApprovalPolicy bandit = new BanditApprovalPolicy(
                BanditApprovalPolicy.DEFAULT_BUCKET_KEY, /*warmup*/ 5);

        // Saturate bandit positively on a HIGH-risk bucket
        PlanEntry probe = entry("tool:x", Risk.HIGH);
        for (int i = 0; i < 100; i++) {
            policy_recordOutcome(bandit, probe, 1.0);
        }

        PlanApprovalPolicy combined = PlanApprovalPolicy.any(
                new RiskTieredApprovalPolicy(),  // would say REQUIRES_HUMAN for HIGH
                bandit                            // confident → AUTO_APPROVE
        );

        int autos = 0;
        for (int i = 0; i < 200; i++) {
            if (combined.decide(EvolvingPlan.empty(), probe) == PlanApprovalPolicy.Decision.AUTO_APPROVE) {
                autos++;
            }
        }
        assertTrue(autos > 190,
                "any() should let confident bandit bypass tier rule; got " + autos + "/200");
    }

    private static void policy_recordOutcome(BanditApprovalPolicy p, PlanEntry e, double r) {
        p.recordOutcome(e, r);
    }
}
