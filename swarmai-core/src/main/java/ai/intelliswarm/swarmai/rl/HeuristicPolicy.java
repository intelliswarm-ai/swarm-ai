package ai.intelliswarm.swarmai.rl;

import ai.intelliswarm.swarmai.skill.SkillGapAnalyzer;
import ai.intelliswarm.swarmai.skill.SkillType;

/**
 * Default policy that reproduces the existing hardcoded decision logic.
 * This is a drop-in replacement that changes zero behavior — it simply extracts
 * the thresholds and formulas from SelfImprovingProcess, SkillGapAnalyzer,
 * and SkillRegistry into a single, swappable policy.
 *
 * <p>All thresholds match the values found in the original code:
 * <ul>
 *   <li>Skill generation: score >= 0.60 → GENERATE, >= 0.40 → GENERATE_SIMPLE, coverage >= 0.45 → USE_EXISTING</li>
 *   <li>Convergence: output must grow by 10%, stop after 3 stale iterations</li>
 *   <li>Selection weights: [relevance=0.5, effectiveness=0.3, quality=0.2]</li>
 * </ul>
 */
public class HeuristicPolicy implements PolicyEngine {

    // --- Skill generation thresholds (from SkillGapAnalyzer lines 120-133) ---
    private static final double GENERATE_THRESHOLD = 0.60;
    private static final double GENERATE_SIMPLE_THRESHOLD = 0.40;
    private static final double COVERAGE_THRESHOLD = 0.45;
    private static final double PROMPT_BLOCK_THRESHOLD = 0.70;

    // --- Scoring weights (from SkillGapAnalyzer lines 78-113) ---
    private static final double WEIGHT_CLARITY = 0.20;
    private static final double WEIGHT_NOVELTY = 0.30;
    private static final double WEIGHT_SKILL_NOVELTY = 0.20;
    private static final double WEIGHT_COMPLEXITY = 0.15;
    private static final double WEIGHT_REUSE = 0.15;

    // --- Convergence thresholds (from SelfImprovingProcess lines 876-899) ---
    private static final double OUTPUT_GROWTH_THRESHOLD = 1.1;
    private static final int MAX_STALE_ITERATIONS = 3;

    // --- Selection weights (from SkillRegistry line 174) ---
    private static final double[] SELECTION_WEIGHTS = {0.5, 0.3, 0.2};

    // Convergence state (tracked across calls within a single workflow run)
    private int staleIterationCount = 0;

    @Override
    public SkillDecision shouldGenerateSkill(SkillGenerationContext context) {
        // Compute the composite score using the same formula as SkillGapAnalyzer.analyze()
        double score = context.clarityScore() * WEIGHT_CLARITY
                + context.noveltyScore() * WEIGHT_NOVELTY
                + context.skillNoveltyScore() * WEIGHT_SKILL_NOVELTY
                + (context.complexityJustifies() ? WEIGHT_COMPLEXITY : 0.05)
                + context.reuseScore() * WEIGHT_REUSE;

        // Apply the same decision thresholds
        SkillGapAnalyzer.Recommendation recommendation;
        String reasoning;

        if (score >= GENERATE_THRESHOLD) {
            recommendation = SkillGapAnalyzer.Recommendation.GENERATE;
            reasoning = String.format("Score %.2f >= %.2f (GENERATE threshold)", score, GENERATE_THRESHOLD);
        } else if (score >= GENERATE_SIMPLE_THRESHOLD) {
            recommendation = SkillGapAnalyzer.Recommendation.GENERATE_SIMPLE;
            reasoning = String.format("Score %.2f >= %.2f (GENERATE_SIMPLE threshold)", score, GENERATE_SIMPLE_THRESHOLD);
        } else if ((1.0 - context.noveltyScore()) >= COVERAGE_THRESHOLD) {
            // noveltyScore = 1.0 - coverage, so coverage = 1.0 - noveltyScore
            recommendation = SkillGapAnalyzer.Recommendation.USE_EXISTING;
            reasoning = String.format("Existing tools cover %.0f%% (>= %.0f%%)",
                    (1.0 - context.noveltyScore()) * 100, COVERAGE_THRESHOLD * 100);
        } else {
            recommendation = SkillGapAnalyzer.Recommendation.SKIP;
            reasoning = String.format("Score %.2f below thresholds", score);
        }

        // Block PROMPT skills unless score is very high (from SkillGapAnalyzer line 133)
        if (context.recommendedType() == SkillType.PROMPT && score < PROMPT_BLOCK_THRESHOLD
                && recommendation != SkillGapAnalyzer.Recommendation.SKIP
                && recommendation != SkillGapAnalyzer.Recommendation.USE_EXISTING) {
            recommendation = SkillGapAnalyzer.Recommendation.SKIP;
            reasoning = String.format("PROMPT skill blocked: score %.2f < %.2f", score, PROMPT_BLOCK_THRESHOLD);
        }

        return SkillDecision.of(recommendation, score, reasoning);
    }

    @Override
    public boolean shouldStopIteration(ConvergenceContext context) {
        boolean outputGrew = context.outputGrowthRate() > OUTPUT_GROWTH_THRESHOLD;
        boolean gapsRepeated = context.gapRepetitionRate() > 0.99; // near-exact match
        boolean agentAdapted = context.newSkillsThisIteration() > 0;

        if (!outputGrew && gapsRepeated && !agentAdapted) {
            staleIterationCount++;
        } else {
            staleIterationCount = 0; // reset on progress
        }

        return staleIterationCount >= MAX_STALE_ITERATIONS;
    }

    @Override
    public double[] getSelectionWeights(SelectionContext context) {
        return SELECTION_WEIGHTS.clone();
    }

    @Override
    public void recordOutcome(Decision decision, Outcome outcome) {
        // No-op — heuristic policy does not learn
    }

    /**
     * Resets convergence tracking state. Call between workflow runs.
     */
    public void reset() {
        staleIterationCount = 0;
    }
}
