package ai.intelliswarm.swarmai.skill;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structured review result that distinguishes quality issues from capability gaps.
 * Parsed from reviewer agent's structured output.
 */
public record ReviewResult(
    boolean approved,
    List<String> qualityIssues,
    List<String> capabilityGaps,
    List<String> nextCommands,
    String rawFeedback
) {
    /**
     * Parse a reviewer's text output into a structured ReviewResult.
     * Expected format:
     *   VERDICT: APPROVED or NEEDS_REFINEMENT
     *   QUALITY_ISSUES:
     *   - issue 1
     *   CAPABILITY_GAPS:
     *   - NO_TOOL: description
     */
    public static ReviewResult parse(String reviewText) {
        if (reviewText == null || reviewText.trim().isEmpty()) {
            return new ReviewResult(false, List.of(), List.of(), List.of(), "");
        }

        String upper = reviewText.substring(0, Math.min(300, reviewText.length())).toUpperCase();

        // Determine verdict
        boolean approved = upper.contains("APPROVED") && !upper.contains("NEEDS_REFINEMENT");

        // Extract quality issues
        List<String> qualityIssues = extractSection(reviewText, "QUALITY_ISSUES");

        // Extract capability gaps
        List<String> capabilityGaps = extractSection(reviewText, "CAPABILITY_GAPS");

        // Extract next commands for reviewer-driven command injection
        List<String> nextCommands = extractSection(reviewText, "NEXT_COMMANDS");

        return new ReviewResult(approved, qualityIssues, capabilityGaps, nextCommands, reviewText);
    }

    private static List<String> extractSection(String text, String sectionName) {
        List<String> items = new ArrayList<>();

        // Find section start
        int sectionStart = text.toUpperCase().indexOf(sectionName);
        if (sectionStart == -1) return items;

        // Find content after the section header
        int contentStart = text.indexOf('\n', sectionStart);
        if (contentStart == -1) return items;

        // Find next section or end
        String remaining = text.substring(contentStart);
        String[] lines = remaining.split("\n");

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                items.add(trimmed.substring(2).trim());
            } else if (trimmed.toUpperCase().startsWith("VERDICT:") ||
                       trimmed.toUpperCase().startsWith("QUALITY_ISSUES") ||
                       trimmed.toUpperCase().startsWith("CAPABILITY_GAPS") ||
                       trimmed.toUpperCase().startsWith("NEXT_COMMANDS")) {
                if (!trimmed.toUpperCase().startsWith(sectionName)) {
                    break; // Hit a different section
                }
            }
        }

        return items;
    }

    public boolean hasCapabilityGaps() {
        return capabilityGaps != null && !capabilityGaps.isEmpty();
    }

    public boolean hasQualityIssues() {
        return qualityIssues != null && !qualityIssues.isEmpty();
    }

    public boolean hasNextCommands() {
        return nextCommands != null && !nextCommands.isEmpty();
    }
}
