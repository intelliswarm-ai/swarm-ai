package ai.intelliswarm.swarmai.rl;

/**
 * State context for the convergence (stop/continue) decision.
 *
 * @param outputGrowthRate          ratio of current / previous output length (1.0 = no change)
 * @param gapRepetitionRate         fraction of current gaps that appeared in the previous iteration (0.0-1.0)
 * @param newSkillsThisIteration    number of new skills generated in the current iteration
 * @param skillReuseRate            fraction of skills that were reused vs generated (0.0-1.0)
 * @param iterationNumber           current iteration (1-based)
 * @param tokenBudgetRemainingPct   remaining token budget as a percentage (0.0-1.0), or 1.0 if no budget
 */
public record ConvergenceContext(
        double outputGrowthRate,
        double gapRepetitionRate,
        int newSkillsThisIteration,
        double skillReuseRate,
        int iterationNumber,
        double tokenBudgetRemainingPct
) {
    /**
     * Returns the state as a feature vector for bandit algorithms.
     */
    public double[] toFeatureVector() {
        return new double[]{
                outputGrowthRate,
                gapRepetitionRate,
                Math.min(1.0, newSkillsThisIteration / 5.0),
                skillReuseRate,
                Math.min(1.0, iterationNumber / 10.0),
                tokenBudgetRemainingPct
        };
    }

    public static int featureDimension() {
        return 6;
    }
}
