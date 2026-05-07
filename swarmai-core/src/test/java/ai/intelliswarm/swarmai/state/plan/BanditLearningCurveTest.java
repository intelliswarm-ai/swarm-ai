package ai.intelliswarm.swarmai.state.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Visible demonstration that the bandit + promotion gate actually learn.
 *
 * <p>Two buckets with known underlying success probabilities (0.9 "good" and
 * 0.1 "bad"). The test feeds 600 simulated outcomes and prints a learning
 * curve at intervals — the bandit's mean estimate per bucket, the 95% CI
 * half-width, the gate's verdict, and the empirical rate at which the gate
 * auto-approves a probe entry from each bucket.
 *
 * <p>End assertions verify the bandit converged toward truth: the good
 * bucket's mean is in {@code [0.80, 1.0]}; the bad bucket's mean is in
 * {@code [0.0, 0.20]}; the gate ends in a state where the good bucket is
 * ready and the bad bucket also is (its CI is narrow even at low mean).
 *
 * <p>To see the curve run with surefire's {@code useFile=false}:
 * <pre>
 *   ./mvnw -pl swarmai-core test -Dtest=BanditLearningCurveTest -Dsurefire.useFile=false
 * </pre>
 */
@DisplayName("Bandit Learning Curve Demo")
class BanditLearningCurveTest {

    private static final double GOOD_RATE = 0.9;
    private static final double BAD_RATE = 0.1;
    private static final int TOTAL_ITERATIONS = 600;
    private static final int SNAPSHOT_EVERY = 50;
    private static final long SEED = 42L;

    private static final String GOOD_KEY = "tool:deploy:" + Risk.LOW;
    private static final String BAD_KEY = "tool:deploy:" + Risk.HIGH;

    @Test
    @DisplayName("Bandit converges per-bucket mean toward underlying truth; gate promotes ready buckets")
    void learningCurveConverges() {
        Random rng = new Random(SEED);

        BanditApprovalPolicy bandit = new BanditApprovalPolicy(
                BanditApprovalPolicy.DEFAULT_BUCKET_KEY, /*warmup*/ 0);
        BanditPromotionGate gate = new BanditPromotionGate(
                bandit,
                PlanApprovalPolicy.alwaysHuman(),
                /*minObs*/ 30,
                /*maxCiHalfWidth*/ 0.10);

        printHeader();

        for (int i = 1; i <= TOTAL_ITERATIONS; i++) {
            // Alternate buckets so both grow at similar rates.
            boolean isGoodBucket = (i % 2 == 0);
            double underlying = isGoodBucket ? GOOD_RATE : BAD_RATE;
            Risk risk = isGoodBucket ? Risk.LOW : Risk.HIGH;

            double draw = rng.nextDouble();
            double reward = draw < underlying ? 1.0 : -1.0;

            PlanEntry e = PlanEntry.builder()
                    .id("iter-" + i)
                    .proposedBy("tool:deploy")
                    .payload("op-" + i)
                    .risk(risk)
                    .build();
            bandit.recordOutcome(e, reward);

            if (i % SNAPSHOT_EVERY == 0) {
                printRow(i, bandit, gate, rng);
            }
        }

        printFooter(bandit, gate);

        // Convergence assertions
        double goodMean = bandit.meanSuccessRate(GOOD_KEY);
        double badMean = bandit.meanSuccessRate(BAD_KEY);
        assertTrue(goodMean > 0.80,
                "Good-bucket mean should converge above 0.80; got " + goodMean);
        assertTrue(badMean < 0.20,
                "Bad-bucket mean should converge below 0.20; got " + badMean);

        // Promotion assertions
        assertTrue(gate.isReady(GOOD_KEY),
                "Good bucket should have graduated by 600 iterations");
        assertTrue(gate.isReady(BAD_KEY),
                "Bad bucket should have graduated by 600 iterations");

        // The PROMOTED gate's verdict differentiates: good auto-approves frequently,
        // bad rarely does. The randomness of Thompson sampling means we use a wide
        // band but the asymmetry is unmistakable.
        int goodAutos = countAutoApproves(gate, Risk.LOW, /*trials*/ 100, rng);
        int badAutos = countAutoApproves(gate, Risk.HIGH, /*trials*/ 100, rng);
        assertTrue(goodAutos >= 80,
                "Good bucket should auto-approve frequently after promotion; got " + goodAutos + "/100");
        assertTrue(badAutos <= 20,
                "Bad bucket should rarely auto-approve after promotion; got " + badAutos + "/100");
        assertTrue(goodAutos > badAutos + 50,
                "Asymmetry should be sharp; good=" + goodAutos + " bad=" + badAutos);
    }

    // ============== Output ==============

    private static void printHeader() {
        nl();
        line("======================================================================");
        line("  Bandit Learning Curve — 600 iterations, two buckets");
        line("======================================================================");
        line("  good bucket = " + GOOD_KEY + " (underlying P(success) = " + GOOD_RATE + ")");
        line("  bad  bucket = " + BAD_KEY + "  (underlying P(success) = " + BAD_RATE + ")");
        nl();
        line(String.format("%5s | %s | %s",
                "iter",
                "  good: mean   ci½    obs  ready  auto%/100",
                "  bad:  mean   ci½    obs  ready  auto%/100"));
        line("------+--------------------------------------------+--------------------------------------------");
    }

    private static void printRow(int iter,
                                  BanditApprovalPolicy bandit,
                                  BanditPromotionGate gate,
                                  Random rng) {
        var goodState = bandit.bucketState(GOOD_KEY).orElse(null);
        var badState = bandit.bucketState(BAD_KEY).orElse(null);

        line(String.format("%5d | %s | %s",
                iter,
                formatBucket(goodState, gate, GOOD_KEY, Risk.LOW, rng),
                formatBucket(badState, gate, BAD_KEY, Risk.HIGH, rng)));
    }

    private static String formatBucket(BanditState.BucketState state,
                                        BanditPromotionGate gate,
                                        String key,
                                        Risk risk,
                                        Random rng) {
        if (state == null) return "  -";
        double halfWidth = BanditPromotionGate.halfWidth(state.alphaAuto(), state.betaAuto());
        boolean ready = gate.isReady(key);
        int autos = countAutoApproves(gate, risk, 100, rng);
        return String.format("  %.3f  %.3f  %5d  %-5s  %3d",
                state.meanAuto(), halfWidth, state.observationCount(),
                ready ? "yes" : "no", autos);
    }

    private static int countAutoApproves(BanditPromotionGate gate, Risk risk, int trials, Random rng) {
        // Use a stable probe id per call; the PolicyDecision is sampled internally so the
        // count varies with Thompson Sampling's randomness — that's the whole point.
        int autos = 0;
        for (int i = 0; i < trials; i++) {
            PlanEntry probe = PlanEntry.builder()
                    .id("probe-" + rng.nextLong())
                    .proposedBy("tool:deploy")
                    .payload("probe")
                    .risk(risk)
                    .build();
            if (gate.decide(EvolvingPlan.empty(), probe) == PlanApprovalPolicy.Decision.AUTO_APPROVE) {
                autos++;
            }
        }
        return autos;
    }

    private static void printFooter(BanditApprovalPolicy bandit, BanditPromotionGate gate) {
        nl();
        line("Final state:");
        var goodState = bandit.bucketState(GOOD_KEY).orElseThrow();
        var badState = bandit.bucketState(BAD_KEY).orElseThrow();
        line(String.format("  good bucket: mean=%.3f  ci½=%.3f  obs=%d  ready=%s",
                goodState.meanAuto(),
                BanditPromotionGate.halfWidth(goodState.alphaAuto(), goodState.betaAuto()),
                goodState.observationCount(),
                gate.isReady(GOOD_KEY)));
        line(String.format("  bad  bucket: mean=%.3f  ci½=%.3f  obs=%d  ready=%s",
                badState.meanAuto(),
                BanditPromotionGate.halfWidth(badState.alphaAuto(), badState.betaAuto()),
                badState.observationCount(),
                gate.isReady(BAD_KEY)));
        nl();
        line("Promoted buckets: " + gate.readyBuckets());
    }

    private static void line(String s) { System.out.println(s); }
    private static void nl() { System.out.println(); }
}
