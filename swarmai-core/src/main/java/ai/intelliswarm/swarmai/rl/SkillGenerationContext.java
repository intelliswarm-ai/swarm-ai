package ai.intelliswarm.swarmai.rl;

import ai.intelliswarm.swarmai.skill.SkillType;

/**
 * State context for the skill generation decision.
 * Captures the feature vector derived from gap analysis sub-scores.
 *
 * @param gapDescription       the raw gap description text
 * @param clarityScore         gap description quality (0.0-1.0)
 * @param noveltyScore         1.0 - max tool coverage (0.0-1.0)
 * @param skillNoveltyScore    1.0 - max similar skill similarity (0.0-1.0)
 * @param complexityJustifies  whether gap complexity justifies a skill
 * @param reuseScore           reusability potential (0.0-1.0)
 * @param gapDescriptionLength character length of the gap description
 * @param existingToolCount    number of tools available to the agent
 * @param registrySize         number of skills in the registry
 * @param recommendedType      skill type recommended by complexity assessment
 */
public record SkillGenerationContext(
        String gapDescription,
        double clarityScore,
        double noveltyScore,
        double skillNoveltyScore,
        boolean complexityJustifies,
        double reuseScore,
        int gapDescriptionLength,
        int existingToolCount,
        int registrySize,
        SkillType recommendedType
) {
    /**
     * Returns the state as a feature vector for bandit algorithms.
     * Dimensions: [clarity, novelty, skillNovelty, complexity, reuse, normalizedLength, normalizedTools, normalizedRegistry]
     */
    public double[] toFeatureVector() {
        return new double[]{
                clarityScore,
                noveltyScore,
                skillNoveltyScore,
                complexityJustifies ? 1.0 : 0.0,
                reuseScore,
                Math.min(1.0, gapDescriptionLength / 200.0),
                Math.min(1.0, existingToolCount / 20.0),
                Math.min(1.0, registrySize / 50.0)
        };
    }

    /**
     * Number of features in the state vector.
     */
    public static int featureDimension() {
        return 8;
    }
}
