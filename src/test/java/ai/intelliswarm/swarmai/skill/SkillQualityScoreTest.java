package ai.intelliswarm.swarmai.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("SkillQualityScore Tests")
class SkillQualityScoreTest {

    @Test
    @DisplayName("assess() handles PROMPT skills that do not include code")
    void assess_promptSkillWithoutCode_doesNotThrowAndUsesZeroCodeScores() {
        SkillDefinition definition = new SkillDefinition();
        definition.setName("prompt_only_skill");
        definition.setDescription("A prompt-only skill definition with enough detail for documentation scoring.");
        definition.setType(SkillType.PROMPT);
        definition.setInstructionBody("""
            # Prompt skill
            - Analyze the user request
            - Return a concise answer
            """);

        GeneratedSkill skill = new GeneratedSkill(definition);
        skill.setOutputSchema(Map.of("properties", Map.of("summary", Map.of("type", "string"))));

        SkillQualityScore score = assertDoesNotThrow(() -> SkillQualityScore.assess(skill));

        assertEquals(0, score.errorHandlingScore());
        assertEquals(0, score.codeComplexityScore());
        assertEquals(0, score.outputFormatScore());
    }
}
