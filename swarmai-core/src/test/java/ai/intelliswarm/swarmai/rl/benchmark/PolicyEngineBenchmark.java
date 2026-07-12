package ai.intelliswarm.swarmai.rl.benchmark;

import ai.intelliswarm.swarmai.rl.ConvergenceContext;
import ai.intelliswarm.swarmai.rl.Decision;
import ai.intelliswarm.swarmai.rl.HeuristicPolicy;
import ai.intelliswarm.swarmai.rl.LearningPolicy;
import ai.intelliswarm.swarmai.rl.Outcome;
import ai.intelliswarm.swarmai.rl.PolicyEngine;
import ai.intelliswarm.swarmai.rl.SkillDecision;
import ai.intelliswarm.swarmai.rl.SkillGenerationContext;
import ai.intelliswarm.swarmai.skill.SkillGapAnalyzer.Recommendation;
import ai.intelliswarm.swarmai.skill.SkillType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The comparison that decides whether the RL policy engine earns its complexity:
 * <b>bandit-driven skill selection vs. the fixed heuristic</b>, on the framework's own
 * decision (`PolicyEngine.shouldGenerateSkill`) — not bandit-vs-bandit.
 *
 * <h2>Experimental design (built to let RL lose)</h2>
 * The environment's reward function is defined from domain semantics BEFORE looking at the
 * heuristic's thresholds, and the heuristic is never tuned to it. Generating a skill has a
 * real cost; using an existing tool is cheap; skipping is free. Two regimes are run:
 *
 * <ul>
 *   <li><b>MATCHED</b> — skill generation is cheap (cost 0.35). The heuristic's built-in
 *       assumption ("a high gap score means generate") is broadly correct here. A learner
 *       that cannot at least match the heuristic in this regime is not worth shipping.</li>
 *   <li><b>SHIFTED</b> — skill generation is expensive (cost 0.75), as in a mature registry
 *       where most gaps are already covered. The right answer moves toward USE_EXISTING/SKIP.
 *       The heuristic's thresholds are fixed, so it cannot notice. This is where adaptivity
 *       should pay — if it pays anywhere.</li>
 * </ul>
 *
 * Three policies are compared against an oracle upper bound:
 * <ol>
 *   <li>{@link HeuristicPolicy} — the fixed thresholds.</li>
 *   <li><b>RL as shipped</b> — {@link LearningPolicy} wired exactly as
 *       {@code SelfImprovingProcess} wires it today, i.e. {@code recordOutcome} is NEVER
 *       called, because production never calls it.</li>
 *   <li><b>RL with the reward loop closed</b> — the same policy, fed the rewards it needs.</li>
 * </ol>
 *
 * 30 seeds per configuration; mean reward per decision with a 95% bootstrap CI.
 */
@DisplayName("BENCHMARK: RL policy vs fixed heuristic on skill-generation decisions")
class PolicyEngineBenchmark {

    private static final int SEEDS = 30;
    private static final int DECISIONS = 1000;
    private static final double NOISE = 0.05;

    /**
     * Ground-truth cost of generating a skill, and how much an existing tool is worth. The
     * policies never see any of this. {@code useExistingValue} is swept in the sensitivity
     * test because the headline numbers are sensitive to it and a reader is right to ask.
     */
    private record Regime(String name, double generateCost, double generateSimpleCost,
                          double useExistingValue) {
        Regime(String name, double generateCost, double generateSimpleCost) {
            this(name, generateCost, generateSimpleCost, 0.80);
        }
    }

    private static final Regime MATCHED = new Regime("MATCHED (generation cheap)", 0.35, 0.15);
    private static final Regime SHIFTED = new Regime("SHIFTED (generation expensive)", 0.75, 0.40);

    private static final Recommendation[] ACTIONS = {
            Recommendation.GENERATE, Recommendation.GENERATE_SIMPLE,
            Recommendation.USE_EXISTING, Recommendation.SKIP
    };

    // ================================================================
    // Environment — ground truth. Defined from domain semantics, not
    // reverse-engineered from any policy's thresholds.
    // ================================================================

    /**
     * True expected reward of each action in a given context.
     *
     * <p>Semantics: a generated skill pays off in proportion to how novel the gap is (nothing
     * covers it), how reusable it will be, and how clearly it was specified — minus what it
     * costs to build. Using an existing tool pays off in proportion to coverage. Skipping is a
     * small positive (you saved the effort).
     */
    private static double trueReward(Regime regime, SkillGenerationContext ctx, int action) {
        double novelty = ctx.noveltyScore();
        double coverage = 1.0 - novelty;
        double reuse = ctx.reuseScore();
        double clarity = ctx.clarityScore();

        return switch (action) {
            case 0 -> 0.90 * novelty * reuse * clarity - regime.generateCost();      // GENERATE
            case 1 -> 0.60 * novelty * clarity - regime.generateSimpleCost();        // GENERATE_SIMPLE
            case 2 -> regime.useExistingValue() * coverage;                          // USE_EXISTING
            case 3 -> 0.10;                                                           // SKIP
            default -> throw new IllegalArgumentException("bad action " + action);
        };
    }

    private static int oracleAction(Regime regime, SkillGenerationContext ctx) {
        int best = 0;
        double bestVal = Double.NEGATIVE_INFINITY;
        for (int a = 0; a < ACTIONS.length; a++) {
            double v = trueReward(regime, ctx, a);
            if (v > bestVal) {
                bestVal = v;
                best = a;
            }
        }
        return best;
    }

    private static SkillGenerationContext randomContext(Random rnd) {
        return new SkillGenerationContext(
                "gap-" + rnd.nextInt(1_000_000),
                rnd.nextDouble(),                    // clarity
                rnd.nextDouble(),                    // novelty
                rnd.nextDouble(),                    // skill novelty
                rnd.nextBoolean(),                   // complexity justifies
                rnd.nextDouble(),                    // reuse
                20 + rnd.nextInt(300),               // description length
                rnd.nextInt(20),                     // existing tools
                rnd.nextInt(50),                     // registry size
                rnd.nextBoolean() ? SkillType.CODE : SkillType.HYBRID);
    }

    private static int actionIndex(Recommendation r) {
        return switch (r) {
            case GENERATE -> 0;
            case GENERATE_SIMPLE -> 1;
            case USE_EXISTING -> 2;
            case SKIP -> 3;
        };
    }

    // ================================================================
    // Runner
    // ================================================================

    /** Mean reward per decision, and the fraction of decisions that matched the oracle. */
    private record Run(double meanReward, double optimalRate) {
    }

    /**
     * @param closeRewardLoop when false, reproduces production wiring: the policy is asked for
     *                        decisions but {@code recordOutcome} is never called.
     */
    private Run runPolicy(PolicyEngine policy, Regime regime, long seed, boolean closeRewardLoop) {
        Random rnd = new Random(seed);
        Random noise = new Random(seed * 31 + 7);
        double totalReward = 0;
        int optimal = 0;

        for (int t = 0; t < DECISIONS; t++) {
            SkillGenerationContext ctx = randomContext(rnd);

            SkillDecision decision = policy.shouldGenerateSkill(ctx);
            int action = actionIndex(decision.recommendation());

            double reward = trueReward(regime, ctx, action) + noise.nextGaussian() * NOISE;
            totalReward += reward;
            if (action == oracleAction(regime, ctx)) optimal++;

            if (closeRewardLoop) {
                policy.recordOutcome(
                        new Decision(decision.decisionId(), "skill_generation",
                                ctx.toFeatureVector(), action, java.time.Instant.now()),
                        Outcome.of(decision.decisionId(), reward));
            }
        }
        return new Run(totalReward / DECISIONS, optimal / (double) DECISIONS);
    }

    private Run runOracle(Regime regime, long seed) {
        Random rnd = new Random(seed);
        Random noise = new Random(seed * 31 + 7);
        double total = 0;
        for (int t = 0; t < DECISIONS; t++) {
            SkillGenerationContext ctx = randomContext(rnd);
            total += trueReward(regime, ctx, oracleAction(regime, ctx)) + noise.nextGaussian() * NOISE;
        }
        return new Run(total / DECISIONS, 1.0);
    }

    private static double mean(List<Double> xs) {
        return xs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    /** 95% CI half-width via the normal approximation over seeds. */
    private static double ci95(List<Double> xs) {
        double m = mean(xs);
        double var = xs.stream().mapToDouble(x -> (x - m) * (x - m)).sum() / Math.max(1, xs.size() - 1);
        return 1.96 * Math.sqrt(var / xs.size());
    }

    private record Result(String label, double meanReward, double ci, double optimalRate) {
        String format() {
            return String.format("| %-34s | %6.3f +/- %.3f | %5.1f%% |",
                    label, meanReward, ci, optimalRate * 100);
        }
    }

    private List<Result> evaluate(Regime regime) {
        List<Double> oracleR = new ArrayList<>(), heurR = new ArrayList<>(),
                shippedR = new ArrayList<>(), closedR = new ArrayList<>();
        List<Double> oracleO = new ArrayList<>(), heurO = new ArrayList<>(),
                shippedO = new ArrayList<>(), closedO = new ArrayList<>();

        for (int s = 0; s < SEEDS; s++) {
            long seed = 1000 + s;

            Run oracle = runOracle(regime, seed);
            oracleR.add(oracle.meanReward());
            oracleO.add(oracle.optimalRate());

            Run heuristic = runPolicy(new HeuristicPolicy(), regime, seed, false);
            heurR.add(heuristic.meanReward());
            heurO.add(heuristic.optimalRate());

            // Exactly how SelfImprovingProcess wires it today: no recordOutcome, ever.
            Run shipped = runPolicy(new LearningPolicy(50, 1.0, 10_000), regime, seed, false);
            shippedR.add(shipped.meanReward());
            shippedO.add(shipped.optimalRate());

            Run closed = runPolicy(new LearningPolicy(50, 1.0, 10_000), regime, seed, true);
            closedR.add(closed.meanReward());
            closedO.add(closed.optimalRate());
        }

        return List.of(
                new Result("Oracle (upper bound)", mean(oracleR), ci95(oracleR), mean(oracleO)),
                new Result("HeuristicPolicy (fixed thresholds)", mean(heurR), ci95(heurR), mean(heurO)),
                new Result("RL, no rewards (defers to heuristic)", mean(shippedR), ci95(shippedR), mean(shippedO)),
                new Result("RL with reward loop closed", mean(closedR), ci95(closedR), mean(closedO)));
    }

    private void report(Regime regime, List<Result> results) {
        System.out.println();
        System.out.println("### " + regime.name());
        System.out.println("| Policy                             | Mean reward/decision | Optimal |");
        System.out.println("|------------------------------------|----------------------|---------|");
        results.forEach(r -> System.out.println(r.format()));
    }

    @Test
    @DisplayName("skill-generation decision quality: heuristic vs RL, matched and shifted regimes")
    void banditVsHeuristicOnSkillGeneration() {
        List<Result> matched = evaluate(MATCHED);
        List<Result> shifted = evaluate(SHIFTED);

        System.out.println("\n================ POLICY ENGINE BENCHMARK ================");
        System.out.printf("%d decisions x %d seeds, 95%% CI. Higher reward is better.%n", DECISIONS, SEEDS);
        report(MATCHED, matched);
        report(SHIFTED, shifted);
        System.out.println("\n========================================================\n");

        Result heuristicMatched = matched.get(1);
        Result unfedMatched = matched.get(2);
        Result closedMatched = matched.get(3);
        Result heuristicShifted = shifted.get(1);
        Result unfedShifted = shifted.get(2);
        Result closedShifted = shifted.get(3);

        // --- Claim 1: a policy that receives no rewards must be exactly as good as the
        // heuristic — never worse. This is the safe-by-default property.
        //
        // Before the fix it was FAR worse: production never called recordOutcome, so LinUCB kept
        // A=I and b=0, every UCB score tied, argmax collapsed onto action 0, and the policy
        // degenerated to "always GENERATE" (~1% optimal, negative mean reward). LearningPolicy
        // now gates on rewards RECEIVED rather than decisions MADE, so with an open reward loop
        // it defers to the heuristic forever instead of pretending to be trained.
        assertEquals(heuristicMatched.meanReward(), unfedMatched.meanReward(), 1e-9,
                "A policy that has received no rewards must fall back to the heuristic exactly. "
                        + "If it diverges, the reward-gated cold start has regressed and an "
                        + "untrained bandit is making live decisions.");
        assertEquals(heuristicShifted.meanReward(), unfedShifted.meanReward(), 1e-9,
                "Unfed policy must equal the heuristic in the SHIFTED regime too.");

        // --- Claim 2: feeding rewards must actually buy something. ---
        assertTrue(closedMatched.meanReward() > heuristicMatched.meanReward(),
                "Closing the reward loop must beat the fixed thresholds even in the MATCHED "
                        + "regime. closed=" + closedMatched.meanReward()
                        + " heuristic=" + heuristicMatched.meanReward());

        // --- Claim 3: where the environment shifts away from the heuristic's fixed
        // assumptions, learning should win by more. This is what justifies the RL at all. ---
        assertTrue(closedShifted.meanReward() > heuristicShifted.meanReward(),
                "RL with a closed reward loop failed to beat fixed thresholds even when the "
                        + "environment shifted away from those thresholds' assumptions. If this "
                        + "fails, the RL engine does not earn its complexity. closed="
                        + closedShifted.meanReward() + " heuristic=" + heuristicShifted.meanReward());

        // The advantage should be LARGER in the shifted regime — that is the whole thesis:
        // adaptivity pays exactly when the fixed assumptions stop holding.
        double gainMatched = closedMatched.meanReward() - heuristicMatched.meanReward();
        double gainShifted = closedShifted.meanReward() - heuristicShifted.meanReward();
        assertTrue(gainShifted > gainMatched,
                "RL's advantage should grow as the environment departs from the heuristic's "
                        + "assumptions. gainMatched=" + gainMatched + " gainShifted=" + gainShifted);
    }

    /**
     * The headline table depends on coefficients chosen by whoever wrote this environment, so a
     * reader is right to ask whether the conclusion is an artifact of those picks. This sweeps
     * the two most load-bearing knobs — the cost of generating a skill and the value of an
     * existing tool — across a wide grid, and checks the ORDERING holds everywhere rather than
     * at one flattering point. If the ordering flips somewhere, that is reported, not hidden.
     */
    @Test
    @DisplayName("sensitivity: the ranking must hold across the reward model's parameter grid")
    void conclusionIsRobustToRewardModelChoice() {
        double[] generateCosts = {0.20, 0.35, 0.50, 0.65, 0.80};
        double[] useExistingValues = {0.50, 0.65, 0.80, 0.95};

        List<String> rlLosesToHeuristic = new ArrayList<>();
        List<String> shippedBeatsHeuristic = new ArrayList<>();
        int cells = 0;

        System.out.println("\n### Sensitivity sweep (mean reward/decision, 8 seeds/cell)");
        System.out.println("| genCost | useExistingValue | heuristic | RL shipped | RL closed |");
        System.out.println("|---------|------------------|-----------|------------|-----------|");

        for (double gc : generateCosts) {
            for (double uev : useExistingValues) {
                cells++;
                Regime r = new Regime("sweep", gc, gc * 0.45, uev);

                List<Double> heur = new ArrayList<>(), shipped = new ArrayList<>(), closed = new ArrayList<>();
                for (int s = 0; s < 8; s++) {
                    long seed = 2000 + s;
                    heur.add(runPolicy(new HeuristicPolicy(), r, seed, false).meanReward());
                    shipped.add(runPolicy(new LearningPolicy(50, 1.0, 10_000), r, seed, false).meanReward());
                    closed.add(runPolicy(new LearningPolicy(50, 1.0, 10_000), r, seed, true).meanReward());
                }
                double h = mean(heur), sh = mean(shipped), cl = mean(closed);

                System.out.printf("| %7.2f | %16.2f | %9.3f | %10.3f | %9.3f |%n", gc, uev, h, sh, cl);

                if (cl <= h) {
                    rlLosesToHeuristic.add(String.format("genCost=%.2f useExisting=%.2f (closed=%.3f <= heuristic=%.3f)",
                            gc, uev, cl, h));
                }
                if (sh > h) {
                    shippedBeatsHeuristic.add(String.format("genCost=%.2f useExisting=%.2f", gc, uev));
                }
            }
        }

        System.out.printf("%nCells where RL (reward loop closed) FAILED to beat the heuristic: %d/%d%n",
                rlLosesToHeuristic.size(), cells);
        rlLosesToHeuristic.forEach(c -> System.out.println("  - " + c));
        System.out.printf("Cells where RL AS SHIPPED beat the heuristic: %d/%d%n",
                shippedBeatsHeuristic.size(), cells);

        // The as-shipped policy should never beat the heuristic anywhere — it does not learn.
        assertTrue(shippedBeatsHeuristic.isEmpty(),
                "As-shipped RL beat the heuristic in some cells: " + shippedBeatsHeuristic
                        + ". Re-check whether the reward loop has been wired.");

        // The claim "closing the reward loop makes RL beat fixed thresholds" must survive the
        // whole grid, not just the cells that flatter it. Allow no more than a couple of edge
        // cells (e.g. where generation is so cheap the fixed thresholds are already near-optimal).
        assertTrue(rlLosesToHeuristic.size() <= 2,
                "RL with a closed reward loop lost to the fixed heuristic in "
                        + rlLosesToHeuristic.size() + "/" + cells + " cells: " + rlLosesToHeuristic
                        + ". The RL advantage is therefore an artifact of the reward model, not a "
                        + "robust result — do NOT publish it as a headline.");
    }

    // ================================================================
    // The convergence decision — the actively dangerous one.
    // ================================================================

    /**
     * The convergence decision is the actively dangerous one: stopping early silently truncates
     * the user's workflow and destroys work that was about to pay off.
     *
     * <p>Before the fix, the learning policy halted a visibly-healthy run ~49% of the time. The
     * convergence bandit was non-contextual Thompson Sampling that never looked at
     * {@link ConvergenceContext} at all, and — never having been fed a reward — was still sampling
     * its Beta(1,1) prior, i.e. flipping a coin. This test pins that behaviour shut.
     */
    @Test
    @DisplayName("convergence: RL must never halt a run that is visibly still improving")
    void convergenceNeverStopsAProductiveRun() {
        // A context that unambiguously says KEEP GOING: output is growing fast, gaps are not
        // repeating, and new skills were just generated. No sane policy stops here.
        ConvergenceContext keepGoing = new ConvergenceContext(
                2.0,   // output doubled
                0.0,   // gaps are all new
                2,     // two new skills this iteration
                0.5, 1, 1.0);

        HeuristicPolicy heuristic = new HeuristicPolicy();
        int heuristicStops = 0;
        for (int i = 0; i < 1000; i++) {
            if (heuristic.shouldStopIteration(keepGoing)) heuristicStops++;
        }

        // (a) A policy driven past its decision-count cold start but never fed a reward — the
        // exact production wiring before the fix.
        LearningPolicy unfed = new LearningPolicy(50, 1.0, 10_000);
        Random rnd = new Random(42);
        for (int i = 0; i < 60; i++) {
            unfed.shouldGenerateSkill(randomContext(rnd));
        }
        int unfedStops = 0;
        for (int i = 0; i < 1000; i++) {
            if (unfed.shouldStopIteration(keepGoing)) unfedStops++;
        }

        // (b) A fully trained policy — the safety floor must still hold once it IS learning.
        LearningPolicy trained = new LearningPolicy(50, 1.0, 10_000);
        Random rnd2 = new Random(7);
        for (int i = 0; i < 60; i++) {
            trained.shouldGenerateSkill(randomContext(rnd2));
        }
        // Teach it that STOPPING is wonderful and CONTINUING is terrible — an adversarial reward
        // signal deliberately pushing it to halt everything.
        for (int i = 0; i < 50; i++) {
            trained.recordOutcome(
                    Decision.create("convergence", keepGoing.toFeatureVector(), 1), // STOP
                    Outcome.of("c", 1.0));
            trained.recordOutcome(
                    Decision.create("convergence", keepGoing.toFeatureVector(), 0), // CONTINUE
                    Outcome.of("c", -1.0));
        }
        int trainedStops = 0;
        for (int i = 0; i < 1000; i++) {
            if (trained.shouldStopIteration(keepGoing)) trainedStops++;
        }

        System.out.printf("%nConvergence on an unambiguous KEEP-GOING context (1000 calls):%n");
        System.out.printf("  HeuristicPolicy stopped:            %d/1000%n", heuristicStops);
        System.out.printf("  RL, no rewards received, stopped:   %d/1000%n", unfedStops);
        System.out.printf("  RL, trained to LOVE stopping:       %d/1000%n", trainedStops);

        assertEquals(0, heuristicStops,
                "the heuristic must never stop while output is growing and gaps are new");

        assertEquals(0, unfedStops,
                "REGRESSION: a policy that has received no rewards halted a healthy run "
                        + unfedStops + "/1000 times. It must defer to the heuristic instead of "
                        + "sampling an untrained prior.");

        // Even a model actively trained to stop must be overruled by the safety floor: a bandit
        // may explore on a stale run, never on a productive one.
        assertEquals(0, trainedStops,
                "SAFETY FLOOR BREACHED: a trained model halted a visibly-improving run "
                        + trainedStops + "/1000 times. Early stopping silently destroys the user's "
                        + "work; no reward signal may authorise it while output is still growing.");
    }
}
