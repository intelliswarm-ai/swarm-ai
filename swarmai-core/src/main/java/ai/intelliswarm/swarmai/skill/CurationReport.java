package ai.intelliswarm.swarmai.skill;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Summary report produced by the Skill Curator after a curation run.
 * Contains metrics, ranked groups, and can render as Markdown.
 */
public record CurationReport(
    int totalAssessed,
    int totalPassed,
    int totalFailed,
    int totalPublished,
    int totalArchived,
    int groupsIdentified,
    Map<String, List<SkillAssessment>> rankedGroups,
    LocalDateTime curatedAt
) {
    public String toMarkdown() {
        StringBuilder md = new StringBuilder();
        md.append("# Skill Curation Report\n\n");
        md.append("**Date:** ").append(curatedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append("\n\n");
        md.append("| Metric | Value |\n|--------|-------|\n");
        md.append("| Total Assessed | ").append(totalAssessed).append(" |\n");
        md.append("| Passed (>=60) | ").append(totalPassed).append(" |\n");
        md.append("| Failed (<60) | ").append(totalFailed).append(" |\n");
        md.append("| Published to Repo | ").append(totalPublished).append(" |\n");
        md.append("| Archived | ").append(totalArchived).append(" |\n");
        md.append("| Groups Identified | ").append(groupsIdentified).append(" |\n\n");

        for (var entry : rankedGroups.entrySet()) {
            md.append("## Group: ").append(entry.getKey()).append("\n\n");
            md.append("| Rank | Skill | Score | Grade | Execution | Effectiveness | Quality | Tests | Unique | Status |\n");
            md.append("|------|-------|-------|-------|-----------|---------------|---------|-------|--------|--------|\n");
            for (SkillAssessment sa : entry.getValue()) {
                md.append("| ").append(sa.rankInGroup())
                  .append(" | ").append(sa.skill().getName())
                  .append(" | ").append(sa.totalScore())
                  .append(" | ").append(sa.grade())
                  .append(" | ").append(sa.executionScore()).append("/25")
                  .append(" | ").append(sa.effectivenessScore()).append("/25")
                  .append(" | ").append(sa.codeQualityScore()).append("/20")
                  .append(" | ").append(sa.testCoverageScore()).append("/15")
                  .append(" | ").append(sa.uniquenessScore()).append("/15")
                  .append(" | ").append(sa.passesCurationBar() ? "PASS" : "FAIL")
                  .append(" |\n");
            }
            md.append("\n");
        }
        return md.toString();
    }
}
