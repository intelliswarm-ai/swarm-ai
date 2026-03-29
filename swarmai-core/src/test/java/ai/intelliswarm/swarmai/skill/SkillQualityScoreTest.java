package ai.intelliswarm.swarmai.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("SkillQualityScore Tests")
class SkillQualityScoreTest {

    @Test
    @DisplayName("assess() handles PROMPT skills without code — scores instruction body quality")
    void assess_promptSkillWithoutCode_doesNotThrowAndScoresInstructionBody() {
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

        // PROMPT skills score on instruction body structure, not code
        // Body has heading (#) and lists (- ), so complexity = 10 + 5 + 5 = 20
        assertEquals(20, score.codeComplexityScore());
        // No constraints or error/fallback terms in body
        assertEquals(0, score.errorHandlingScore());
    }

    @Test
    @DisplayName("assess() handles PROMPT skills with null code — no NPE")
    void assess_promptSkillWithNullCode_doesNotThrow() {
        SkillDefinition definition = new SkillDefinition();
        definition.setName("null_code_skill");
        definition.setDescription("Skill with null code field to verify NPE protection.");
        definition.setType(SkillType.PROMPT);
        // No instruction body, no code — minimal skill
        GeneratedSkill skill = new GeneratedSkill(definition);

        SkillQualityScore score = assertDoesNotThrow(() -> SkillQualityScore.assess(skill));
        assertEquals(0, score.codeComplexityScore());
        assertEquals(0, score.errorHandlingScore());
        assertEquals(0, score.outputFormatScore());
    }
}
