package ai.intelliswarm.swarmai.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract tests for SkillGenerator's parsing logic.
 *
 * These test the framework's side of the LLM contract: given various
 * LLM response formats (well-formed, malformed, edge cases), does
 * parseSkillDefinition() correctly extract a valid skill?
 *
 * No LLM calls — these test pure Java parsing against realistic
 * LLM output patterns observed during development.
 */
@DisplayName("SkillGenerator Contract — Response Parsing")
class SkillGeneratorContractTest {

    private SkillGenerator generator;

    @BeforeEach
    void setUp() {
        // No real ChatClient needed — we're testing parseSkillDefinition() directly
        generator = new SkillGenerator(null);
    }

    // ================================================================
    // WELL-FORMED SKILL.md PARSING
    // ================================================================

    @Nested
    @DisplayName("Well-formed SKILL.md Parsing")
    class WellFormedParsing {

        @Test
        @DisplayName("parses complete SKILL.md with all sections")
        void parsesCompleteSkillMd() {
            String response = """
                ---
                name: financial_ratio_calculator
                description: Calculate financial ratios (P/E, debt-to-equity, current ratio) from balance sheet data
                type: CODE
                triggerWhen: User needs financial ratio calculations from raw data
                avoidWhen: Data is already processed or ratios are pre-computed
                category: computation
                tags: [finance, ratios, analysis]
                ---

                # Financial Ratio Calculator

                ## Code
                ```groovy
                def data = params.get("data") ?: "{}"
                def parser = new groovy.json.JsonSlurper()
                def json = parser.parseText(data)
                def revenue = json.revenue ?: 0
                def netIncome = json.netIncome ?: 0
                def margin = revenue > 0 ? (netIncome / revenue * 100) : 0
                def result = "Net Margin: ${String.format('%.1f', margin)}%"
                result
                ```

                ## Test Cases
                ```groovy
                assert result != null
                assert result.contains("Margin")
                ```

                ## Integration Tests

                ### test_basic_calculation
                Verify basic ratio computation

                **Input:**
                ```yaml
                data: '{"revenue": 1000000, "netIncome": 150000}'
                ```

                **Assertions:**
                ```groovy
                assert output != null
                assert output.contains("Margin")
                assert output.contains("15.0")
                ```
                """;

            GeneratedSkill skill = generator.parseSkillDefinition(response, "fallback desc");

            assertNotNull(skill, "Should parse complete SKILL.md");
            assertEquals("financial_ratio_calculator", skill.getName());
            assertEquals(SkillType.CODE, skill.getSkillType());
            assertNotNull(skill.getCode(), "Should extract code block");
            assertTrue(skill.getCode().contains("JsonSlurper"), "Code should contain parsed content");
            assertNotNull(skill.getDescription());
            assertTrue(skill.getDescription().length() > 20, "Description should be substantial");
        }

        @Test
        @DisplayName("parses HYBRID skill with instructions and code")
        void parsesHybridSkill() {
            String response = """
                ---
                name: market_analyzer
                description: Analyze market data using both data processing and LLM reasoning
                type: HYBRID
                category: analysis
                tags: [market, hybrid]
                ---

                # Market Analyzer

                Analyze the provided market data. Focus on:
                - Year-over-year trends
                - Anomalies in pricing
                - Competitive positioning

                ## Code
                ```groovy
                def data = params.get("data") ?: "no data"
                def result = "Processed: " + data.length() + " chars of market data"
                result
                ```

                ## Test Cases
                ```groovy
                assert result != null
                ```
                """;

            GeneratedSkill skill = generator.parseSkillDefinition(response, "fallback");

            assertNotNull(skill);
            assertEquals(SkillType.HYBRID, skill.getSkillType());
            assertNotNull(skill.getCode());
            assertNotNull(skill.getInstructionBody(),
                "HYBRID skill should have instruction body extracted from markdown");
        }
    }

    // ================================================================
    // MALFORMED / EDGE CASE RESPONSES — Real LLM outputs are messy
    // ================================================================

    @Nested
    @DisplayName("Malformed and Edge Case LLM Responses")
    class MalformedResponses {

        @Test
        @DisplayName("null response returns null")
        void nullResponse() {
            assertNull(generator.parseSkillDefinition(null, "desc"));
        }

        @Test
        @DisplayName("empty response returns null")
        void emptyResponse() {
            assertNull(generator.parseSkillDefinition("", "desc"));
        }

        @Test
        @DisplayName("whitespace-only response returns null")
        void whitespaceResponse() {
            assertNull(generator.parseSkillDefinition("   \n\t\n   ", "desc"));
        }

        @Test
        @DisplayName("response with no frontmatter still extracts code")
        void noFrontmatter() {
            String response = """
                Here's a skill for you:

                ## Code
                ```groovy
                def result = params.get("input").toUpperCase()
                result
                ```

                ## Test Cases
                ```groovy
                assert result != null
                ```
                """;

            GeneratedSkill skill = generator.parseSkillDefinition(response, "uppercase converter");

            // Should still extract something useful, even without frontmatter
            if (skill != null) {
                assertNotNull(skill.getCode(),
                    "Should extract code even without frontmatter");
            }
        }

        @Test
        @DisplayName("response wrapped in extra code fences (common LLM mistake)")
        void wrappedInCodeFences() {
            String response = """
                ```
                ---
                name: csv_parser
                description: Parse CSV data and extract key columns
                type: CODE
                category: data-io
                tags: [csv, parser]
                ---

                ## Code
                ```groovy
                def result = "parsed"
                result
                ```

                ## Test Cases
                ```groovy
                assert result != null
                ```
                ```
                """;

            GeneratedSkill skill = generator.parseSkillDefinition(response, "csv parser");

            assertNotNull(skill,
                "Should handle response wrapped in extra code fences — a common LLM output pattern");
            assertEquals("csv_parser", skill.getName());
        }

        @Test
        @DisplayName("LLM prefixes response with explanation text")
        void prefixedWithExplanation() {
            String response = """
                Sure! I'll create a skill for parsing financial data. Here's the SKILL.md:

                ---
                name: financial_parser
                description: Parse financial data from JSON API responses and extract key metrics
                type: CODE
                category: data-io
                tags: [finance, json, parser]
                ---

                ## Code
                ```groovy
                def result = "parsed financial data"
                result
                ```
                """;

            GeneratedSkill skill = generator.parseSkillDefinition(response, "financial parser");

            assertNotNull(skill,
                "Should skip LLM preamble and find the SKILL.md content");
            assertEquals("financial_parser", skill.getName());
        }

        @Test
        @DisplayName("skill with missing name gets generated name")
        void missingName() {
            String response = """
                ---
                description: A skill without a name
                type: CODE
                ---

                ## Code
                ```groovy
                def result = "works"
                result
                ```
                """;

            GeneratedSkill skill = generator.parseSkillDefinition(response, "unnamed skill");

            assertNotNull(skill);
            assertNotNull(skill.getName(), "Missing name should get a generated fallback");
            assertFalse(skill.getName().isBlank());
        }

        @Test
        @DisplayName("skill with missing description uses fallback")
        void missingDescription() {
            String response = """
                ---
                name: mystery_skill
                type: CODE
                ---

                ## Code
                ```groovy
                def result = "mystery"
                result
                ```
                """;

            GeneratedSkill skill = generator.parseSkillDefinition(response, "the fallback description");

            assertNotNull(skill);
            assertEquals("the fallback description", skill.getDescription(),
                "Missing description should use fallback");
        }

        @Test
        @DisplayName("CODE skill with no code block returns null")
        void codeSkillWithNoCode() {
            String response = """
                ---
                name: empty_code
                description: A CODE skill with no code
                type: CODE
                ---

                # Empty Code Skill

                This skill has instructions but no code.
                """;

            GeneratedSkill skill = generator.parseSkillDefinition(response, "empty");

            // CODE skill with no code should either return null or fall back to PROMPT
            if (skill != null) {
                // If it falls back to PROMPT, that's acceptable
                assertEquals(SkillType.PROMPT, skill.getSkillType(),
                    "CODE skill with no code should fall back to PROMPT or return null");
            }
        }
    }

    // ================================================================
    // TYPE INFERENCE — When LLM doesn't specify type
    // ================================================================

    @Nested
    @DisplayName("Skill Type Inference")
    class TypeInference {

        @Test
        @DisplayName("infers CODE when only code is present")
        void infersCodeType() {
            String response = """
                ---
                name: inferred_code
                description: Skill with code but no explicit type
                ---

                ## Code
                ```groovy
                def result = "computed"
                result
                ```
                """;

            GeneratedSkill skill = generator.parseSkillDefinition(response, "test");

            assertNotNull(skill);
            assertEquals(SkillType.CODE, skill.getSkillType(),
                "Should infer CODE when code block is present without explicit type");
        }

        @Test
        @DisplayName("infers HYBRID when both code and instructions are present")
        void infersHybridType() {
            String response = """
                ---
                name: inferred_hybrid
                description: Skill with both code and instructions but no explicit type
                ---

                # Analysis Framework

                Follow these steps to analyze the data:
                1. Extract key metrics
                2. Compare against benchmarks

                ## Code
                ```groovy
                def result = "data processed"
                result
                ```
                """;

            GeneratedSkill skill = generator.parseSkillDefinition(response, "test");

            assertNotNull(skill);
            // Diagnostic: check both the definition and the GeneratedSkill
            SkillDefinition def = skill.getDefinition();
            String defInstr = def != null ? def.getInstructionBody() : null;
            String defCode = def != null ? def.getCode() : null;
            SkillType defType = def != null ? def.getType() : null;
            assertEquals(SkillType.HYBRID, skill.getSkillType(),
                "Should infer HYBRID when both instructions and code are present. " +
                "def.instructionBody=" + (defInstr != null ? defInstr.length() + " chars" : "null") +
                ", def.code=" + (defCode != null ? defCode.length() + " chars" : "null") +
                ", def.type=" + defType +
                ", skill.getSkillType()=" + skill.getSkillType() +
                ". BUG: The definition has both instruction+code but type is " + defType);
        }
    }

    // ================================================================
    // OLD FORMAT FALLBACK — Legacy LLM outputs
    // ================================================================

    @Nested
    @DisplayName("Legacy Format Fallback")
    class LegacyFormat {

        @Test
        @DisplayName("extracts fields from NAME:/DESCRIPTION:/CODE: format")
        void parsesLegacyFormat() {
            String response = """
                NAME: legacy_skill
                DESCRIPTION: A skill in the old format
                CODE:
                ```groovy
                def result = "legacy"
                result
                ```
                TEST_CASES:
                ```groovy
                assert result != null
                ```
                """;

            GeneratedSkill skill = generator.parseSkillDefinition(response, "legacy fallback");

            assertNotNull(skill, "Should parse legacy NAME:/CODE: format");
            // The exact parsing depends on whether fromSkillMd or field extraction wins
            assertNotNull(skill.getName());
        }
    }

    // ================================================================
    // TARGET LANGUAGE TAGGING
    // ================================================================

    @Nested
    @DisplayName("Target language tagging")
    class TargetLanguageTagging {

        private static final String MINIMAL_CODE_SKILL = """
            ---
            name: tiny_skill
            description: Smoke-test skill used to verify language tagging
            type: CODE
            ---

            # Tiny Skill

            Just enough body to parse.

            ```groovy
            return params.get("x")
            ```
            """;

        @Test
        @DisplayName("default generator tags parsed skills with language=groovy")
        void defaultGeneratorTagsGroovy() {
            SkillGenerator defaultGen = new SkillGenerator(null);

            GeneratedSkill skill = defaultGen.parseSkillDefinition(MINIMAL_CODE_SKILL, "tiny");

            assertNotNull(skill);
            assertEquals("groovy", skill.getLanguage());
        }

        @Test
        @DisplayName("kotlin-script generator tags parsed skills with language=kotlin-script")
        void kotlinGeneratorTagsKotlin() {
            SkillGenerator kotlinGen = new SkillGenerator(null, "kotlin-script");

            GeneratedSkill skill = kotlinGen.parseSkillDefinition(MINIMAL_CODE_SKILL, "tiny");

            assertNotNull(skill);
            assertEquals("kotlin-script", skill.getLanguage());
        }

        @Test
        @DisplayName("blank target language falls back to groovy")
        void blankTargetLanguageFallsBackToGroovy() {
            SkillGenerator blank = new SkillGenerator(null, "  ");

            GeneratedSkill skill = blank.parseSkillDefinition(MINIMAL_CODE_SKILL, "tiny");

            assertEquals("groovy", skill.getLanguage());
        }
    }
}
