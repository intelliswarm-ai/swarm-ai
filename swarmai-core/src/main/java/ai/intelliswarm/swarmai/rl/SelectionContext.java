package ai.intelliswarm.swarmai.rl;

import java.util.List;

/**
 * State context for the skill selection weight decision.
 *
 * @param taskDescription  the task description to match skills against
 * @param candidates       candidate skills with their metadata
 */
public record SelectionContext(
        String taskDescription,
        List<SkillCandidate> candidates
) {
    /**
     * A candidate skill for selection.
     *
     * @param skillId       unique skill identifier
     * @param relevance     Jaccard similarity to task (0.0-1.0)
     * @param effectiveness success rate (successCount / usageCount, 0.0-1.0)
     * @param qualityScore  normalized quality score (0.0-1.0)
     * @param usageCount    total execution count
     */
    public record SkillCandidate(
            String skillId,
            double relevance,
            double effectiveness,
            double qualityScore,
            int usageCount
    ) {}
}
