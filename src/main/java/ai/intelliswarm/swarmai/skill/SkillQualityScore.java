package ai.intelliswarm.swarmai.skill;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Quality assessment of a generated skill.
 * Scores across multiple dimensions to inform promotion decisions.
 * Inspired by OpenClaw's SELF_CHECK.md rubric.
 */
public record SkillQualityScore(
    int documentationScore,     // 0-20: description quality, parameter docs
    int testCoverageScore,      // 0-20: test case count and quality
    int errorHandlingScore,     // 0-20: try/catch, null checks, edge cases
    int codeComplexityScore,    // 0-20: LOC, nesting depth, readability
    int outputFormatScore       // 0-20: consistent output, proper formatting
) {
    public int totalScore() {
        return documentationScore + testCoverageScore + errorHandlingScore
             + codeComplexityScore + outputFormatScore;
    }

    public String grade() {
        int total = totalScore();
        if (total >= 90) return "A";
        if (total >= 80) return "B";
        if (total >= 70) return "C";
        if (total >= 60) return "D";
        return "F";
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("documentation", documentationScore);
        map.put("testCoverage", testCoverageScore);
        map.put("errorHandling", errorHandlingScore);
        map.put("codeComplexity", codeComplexityScore);
        map.put("outputFormat", outputFormatScore);
        map.put("total", totalScore());
        map.put("grade", grade());
        return map;
    }

    /**
     * Assess a generated skill's quality based on its code and metadata.
     */
    public static SkillQualityScore assess(GeneratedSkill skill) {
        String code = skill.getCode();
        String desc = skill.getDescription();

        // 1. Documentation: description quality + parameter descriptions in schema
        int docScore = 0;
        if (desc != null && desc.length() > 20) docScore += 10;
        if (desc != null && desc.length() > 50) docScore += 5;
        Map<String, Object> schema = skill.getParameterSchema();
        if (schema != null && !schema.isEmpty()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) schema.get("properties");
            if (props != null && !props.isEmpty()) docScore += 5;
        }

        // 2. Test coverage: number and quality of test cases
        int testScore = 0;
        int testCount = skill.getTestCases() != null ? skill.getTestCases().size() : 0;
        if (testCount >= 1) testScore += 10;
        if (testCount >= 2) testScore += 5;
        // Check for meaningful assertions
        if (skill.getTestCases() != null) {
            for (String test : skill.getTestCases()) {
                if (test.contains("assert") && test.contains("result")) testScore += 5;
                break; // only check first
            }
        }

        // 3. Error handling: try/catch, null checks, default values
        int errorScore = 0;
        if (code.contains("try") && code.contains("catch")) errorScore += 10;
        if (code.contains("?:") || code.contains("!= null") || code.contains("?: \"")) errorScore += 5;
        if (code.contains("Error:") || code.contains("error")) errorScore += 5;
        errorScore = Math.min(20, errorScore);

        // 4. Code complexity: lower is better
        int complexityScore = 20;
        int lineCount = code.split("\n").length;
        if (lineCount > 50) complexityScore -= 5;
        if (lineCount > 100) complexityScore -= 5;
        // Nesting depth check
        int maxNesting = 0, currentNesting = 0;
        for (char c : code.toCharArray()) {
            if (c == '{') currentNesting++;
            if (c == '}') currentNesting--;
            maxNesting = Math.max(maxNesting, currentNesting);
        }
        if (maxNesting > 4) complexityScore -= 5;
        if (maxNesting > 6) complexityScore -= 5;
        complexityScore = Math.max(0, complexityScore);

        // 5. Output format: consistent result formatting
        int outputScore = 0;
        if (code.contains("result") || code.contains("return") || code.contains("output")) outputScore += 10;
        if (code.contains("\\n") || code.contains("StringBuilder") || code.contains("String.format")) outputScore += 5;
        if (code.contains("\"Error:") || code.contains("catch")) outputScore += 5; // error cases produce output
        outputScore = Math.min(20, outputScore);

        return new SkillQualityScore(docScore, testScore, errorScore, complexityScore, outputScore);
    }
}
