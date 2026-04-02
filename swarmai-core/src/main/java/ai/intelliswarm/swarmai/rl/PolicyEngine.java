package ai.intelliswarm.swarmai.rl;

/**
 * Central decision interface for the self-improving workflow loop.
 * Replaces hardcoded thresholds with pluggable policies that can learn from experience.
 *
 * <p>Two implementations:
 * <ul>
 *   <li>{@link HeuristicPolicy} — reproduces the current hardcoded logic (default, backward-compatible)</li>
 *   <li>{@code LearningPolicy} — uses contextual bandits to learn optimal decisions over time</li>
 * </ul>
 *
 * <p>Usage in SelfImprovingProcess:
 * <pre>{@code
 * PolicyEngine policy = policyEngine != null ? policyEngine : new HeuristicPolicy();
 *
 * // Instead of hardcoded gap scoring
 * SkillDecision decision = policy.shouldGenerateSkill(context);
 *
 * // Instead of hardcoded convergence detection
 * boolean stop = policy.shouldStopIteration(convergenceContext);
 *
 * // Instead of hardcoded [0.5, 0.3, 0.2] weights
 * double[] weights = policy.getSelectionWeights(selectionContext);
 * }</pre>
 */
public interface PolicyEngine {

    /**
     * Decides whether to generate a skill for a capability gap.
     *
     * @param context the gap analysis features (clarity, novelty, coverage, etc.)
     * @return a decision with recommendation (GENERATE, GENERATE_SIMPLE, USE_EXISTING, SKIP)
     */
    SkillDecision shouldGenerateSkill(SkillGenerationContext context);

    /**
     * Decides whether to stop the self-improving iteration loop.
     *
     * @param context convergence signals (output growth, gap repetition, iteration count, etc.)
     * @return true if the loop should stop
     */
    boolean shouldStopIteration(ConvergenceContext context);

    /**
     * Returns the weights for skill selection scoring.
     * The weights correspond to [relevance, effectiveness, quality] and should sum to ~1.0.
     *
     * @param context the task and candidate skills
     * @return weight vector of length 3
     */
    double[] getSelectionWeights(SelectionContext context);

    /**
     * Records the outcome of a prior decision for policy learning.
     * No-op for heuristic policies. Learning policies use this to update their models.
     *
     * @param decision the decision that was made
     * @param outcome  the observed outcome with reward signal
     */
    void recordOutcome(Decision decision, Outcome outcome);
}
