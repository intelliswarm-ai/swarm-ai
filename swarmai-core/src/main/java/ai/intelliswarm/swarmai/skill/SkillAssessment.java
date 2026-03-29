package ai.intelliswarm.swarmai.skill;

import java.util.*;

/**
 * Result of the Skill Curator's assessment of a single generated skill.
 *
 * Scores across 5 dimensions (total 0-100):
 * - executionScore (0-25): compilation + test execution + output check
 * - effectivenessScore (0-25): historical successCount/usageCount
 * - codeQualityScore (0-20): from SkillQualityScore dimensions
 * - testCoverageScore (0-15): test count + assertion quality
 * - uniquenessScore (0-15): how different from peers in same group
 */
public record SkillAssessment(
    GeneratedSkill skill,
    int executionScore,        // 0-25: compilation + test execution + output check
    int effectivenessScore,    // 0-25: historical successCount/usageCount
    int codeQualityScore,      // 0-20: from SkillQualityScore dimensions
    int testCoverageScore,     // 0-15: test count + assertion quality
    int uniquenessScore,       // 0-15: how different from peers in same group
    int totalScore,            // 0-100 sum
    String grade,              // A(>=90)/B(>=80)/C(>=70)/D(>=60)/F(<60)
    boolean passesCurationBar, // totalScore >= 60
    String groupName,
    int rankInGroup,
    List<String> assessmentNotes
) {
    public static String gradeFromScore(int score) {
        if (score >= 90) return "A";
        if (score >= 80) return "B";
        if (score >= 70) return "C";
        if (score >= 60) return "D";
        return "F";
    }
}
