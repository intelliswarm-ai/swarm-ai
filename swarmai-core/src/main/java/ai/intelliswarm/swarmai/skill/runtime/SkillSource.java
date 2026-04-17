package ai.intelliswarm.swarmai.skill.runtime;

import java.util.List;

public record SkillSource(
    String language,
    String code,
    List<String> testCases
) {
    public static final String GROOVY = "groovy";
    public static final String KOTLIN_SCRIPT = "kotlin-script";

    public SkillSource {
        if (language == null || language.isBlank()) {
            throw new IllegalArgumentException("language is required");
        }
        if (code == null) {
            throw new IllegalArgumentException("code is required");
        }
        testCases = testCases == null ? List.of() : List.copyOf(testCases);
    }
}
