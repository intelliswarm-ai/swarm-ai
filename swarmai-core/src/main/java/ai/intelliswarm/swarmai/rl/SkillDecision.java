package ai.intelliswarm.swarmai.rl;

import ai.intelliswarm.swarmai.skill.SkillGapAnalyzer;

import java.util.UUID;

/**
 * The result of a skill generation decision, including a unique ID for reward tracking.
 *
 * @param decisionId     unique identifier for correlating with delayed rewards
 * @param recommendation the action chosen (GENERATE, GENERATE_SIMPLE, USE_EXISTING, SKIP)
 * @param confidence     the policy's confidence in this decision (0.0-1.0)
 * @param reasoning      human-readable explanation of why this decision was made
 */
public record SkillDecision(
        String decisionId,
        SkillGapAnalyzer.Recommendation recommendation,
        double confidence,
        String reasoning
) {
    /**
     * Creates a decision with an auto-generated ID.
     */
    public static SkillDecision of(SkillGapAnalyzer.Recommendation recommendation,
                                   double confidence, String reasoning) {
        return new SkillDecision(UUID.randomUUID().toString(), recommendation, confidence, reasoning);
    }

    /**
     * Returns true if the decision recommends generating a skill.
     */
    public boolean shouldGenerate() {
        return recommendation == SkillGapAnalyzer.Recommendation.GENERATE ||
               recommendation == SkillGapAnalyzer.Recommendation.GENERATE_SIMPLE;
    }
}
