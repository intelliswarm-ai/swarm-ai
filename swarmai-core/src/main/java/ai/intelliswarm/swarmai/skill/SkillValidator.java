package ai.intelliswarm.swarmai.skill;

import ai.intelliswarm.swarmai.skill.runtime.GroovyInProcRuntime;
import ai.intelliswarm.swarmai.skill.runtime.KotlinScriptRuntime;
import ai.intelliswarm.swarmai.skill.runtime.RuntimeRegistry;
import ai.intelliswarm.swarmai.skill.runtime.SecurityReport;
import ai.intelliswarm.swarmai.skill.runtime.SkillRuntime;
import ai.intelliswarm.swarmai.skill.runtime.SkillSource;
import ai.intelliswarm.swarmai.skill.runtime.SyntaxReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * Validates generated skills before they can be used in workflows.
 *
 * Validation pipeline:
 * 1. Security scan — delegated to the SkillRuntime for the skill's language
 * 2. Syntax check — delegated to the SkillRuntime
 * 3. Test execution — run auto-generated test cases in sandboxed GroovyShell
 * 4. Quality assessment — score documentation, tests, error handling, complexity, output format
 */
public class SkillValidator {

    private static final Logger logger = LoggerFactory.getLogger(SkillValidator.class);

    private static final int TEST_TIMEOUT_SECONDS = 10;

    private final RuntimeRegistry runtimes;

    public SkillValidator() {
        this(defaultRegistry());
    }

    public SkillValidator(RuntimeRegistry runtimes) {
        this.runtimes = runtimes;
    }

    private static RuntimeRegistry defaultRegistry() {
        RuntimeRegistry registry = new RuntimeRegistry();
        registry.register(new GroovyInProcRuntime());
        tryRegisterKotlin(registry);
        return registry;
    }

    private static void tryRegisterKotlin(RuntimeRegistry registry) {
        try {
            registry.register(new KotlinScriptRuntime());
            logger.debug("Registered Kotlin script runtime for skill validation");
        } catch (IllegalStateException e) {
            logger.debug("Kotlin script runtime not available — skipping: {}", e.getMessage());
        } catch (Throwable t) {
            logger.debug("Kotlin script runtime failed to initialize — skipping: {}", t.getMessage());
        }
    }

    /**
     * Validate a generated skill for safety, correctness, and quality.
     * Adapts validation based on skill type:
     * - PROMPT: validates instruction body presence and structure
     * - CODE: security scan + syntax check + test execution
     * - HYBRID: validates both instruction body and code
     * - COMPOSITE: validates routing table and sub-skill references
     */
    public ValidationResult validate(GeneratedSkill skill) {
        SkillType skillType = skill.getSkillType();
        logger.info("Validating {} skill: {} v{}", skillType, skill.getName(), skill.getVersion());

        List<String> errors = new ArrayList<>();

        // 1. Basic checks (all types)
        if (skill.getName() == null || skill.getName().isEmpty() || skill.getName().equals("unnamed_skill")) {
            errors.add("Skill name is empty or auto-generated placeholder");
            return new ValidationResult(false, errors, null, null);
        }
        if (skill.getDescription() == null || skill.getDescription().isEmpty()) {
            errors.add("Skill description is empty");
            return new ValidationResult(false, errors, null, null);
        }

        // 2. Type-specific validation
        switch (skillType) {
            case PROMPT -> validatePromptSkill(skill, errors);
            case CODE -> validateCodeSkill(skill, errors);
            case HYBRID -> {
                validatePromptSkill(skill, errors);
                if (skill.getCode() != null && !skill.getCode().isBlank()) {
                    validateCodeSkill(skill, errors);
                }
            }
            case COMPOSITE -> validateCompositeSkill(skill, errors);
        }

        if (!errors.isEmpty()) {
            logger.warn("Skill '{}' failed validation: {}", skill.getName(), errors);
            return new ValidationResult(false, errors, null, null);
        }

        // 3. Integration tests — exercise the full execute() pipeline with real tools
        GeneratedSkill.IntegrationTestResults integrationResults = null;
        if (skill.getIntegrationTests() != null && !skill.getIntegrationTests().isEmpty()) {
            integrationResults = runIntegrationTests(skill);
            if (integrationResults.failed() > 0) {
                for (String failure : integrationResults.failureMessages()) {
                    errors.add("Integration test failed: " + failure);
                }
                logger.warn("Skill '{}' failed {} of {} integration tests",
                    skill.getName(), integrationResults.failed(), integrationResults.total());
                // Integration test failures are blocking — skill cannot be validated
                return new ValidationResult(false, errors, null, integrationResults);
            }
            logger.info("Skill '{}' passed all {} integration tests ({}ms)",
                skill.getName(), integrationResults.total(), integrationResults.totalDurationMs());
        }

        // 4. Quality assessment
        SkillQualityScore qualityScore = SkillQualityScore.assess(skill);
        skill.setQualityScore(qualityScore);

        logger.info("Skill '{}' v{} ({}) passed validation (quality: {}/100 grade {}, integration tests: {})",
            skill.getName(), skill.getVersion(), skillType,
            qualityScore.totalScore(), qualityScore.grade(),
            integrationResults != null ? integrationResults.passed() + "/" + integrationResults.total() + " passed" : "none");

        return new ValidationResult(true, errors, qualityScore, integrationResults);
    }

    private void validatePromptSkill(GeneratedSkill skill, List<String> errors) {
        String body = skill.getInstructionBody();
        if (body == null || body.isBlank()) {
            errors.add("PROMPT skill has no instruction body");
            return;
        }
        if (body.length() < 50) {
            errors.add("Instruction body too short (" + body.length() + " chars) — needs at least 50 chars of meaningful instructions");
        }
        // Check for structure markers (headings, lists, numbered steps)
        boolean hasStructure = body.contains("#") || body.contains("- ") ||
                               body.contains("1.") || body.contains("* ");
        if (!hasStructure && body.length() > 200) {
            // Long body without structure is a smell — warn but don't fail
            logger.warn("PROMPT skill '{}' has a long body ({} chars) without markdown structure",
                skill.getName(), body.length());
        }
    }

    private void validateCodeSkill(GeneratedSkill skill, List<String> errors) {
        if (skill.getCode() == null || skill.getCode().trim().isEmpty()) {
            errors.add("CODE skill has no code");
            return;
        }

        SkillSource source = sourceFor(skill);
        SkillRuntime runtime = runtimes.require(source.language());

        SecurityReport security = runtime.securityScan(source);
        if (!security.ok()) {
            errors.addAll(security.violations());
            return;
        }

        SyntaxReport syntax = runtime.syntaxCheck(source);
        if (!syntax.ok()) {
            errors.add("Syntax error: " + syntax.errorMessage());
            return;
        }

        // Test execution (warnings only)
        if (!skill.getTestCases().isEmpty()) {
            List<String> testErrors = runTests(skill);
            if (!testErrors.isEmpty()) {
                logger.warn("Skill '{}' test warnings (non-blocking): {}", skill.getName(), testErrors);
            }
        }

        // Require at least 2 test cases for CODE skills
        if (skill.getTestCases().size() < 2) {
            logger.warn("Skill '{}' has only {} test case(s) — minimum 2 required for CODE skills",
                skill.getName(), skill.getTestCases().size());
            // Non-blocking warning for now, but affects quality score
        }
    }

    private void validateCompositeSkill(GeneratedSkill skill, List<String> errors) {
        SkillDefinition def = skill.getDefinition();
        if (def.getRoutingTable().isEmpty() && skill.getSubSkills().isEmpty()) {
            // A composite skill with no routing and no sub-skills is just a prompt skill
            if (def.getInstructionBody() == null || def.getInstructionBody().isBlank()) {
                errors.add("COMPOSITE skill has no routing table, no sub-skills, and no instruction body");
            }
        }
    }

    private SkillSource sourceFor(GeneratedSkill skill) {
        return new SkillSource(SkillSource.GROOVY, skill.getCode(), skill.getTestCases());
    }

    /**
     * Run test cases in a sandboxed GroovyShell with timeout.
     */
    private List<String> runTests(GeneratedSkill skill) {
        List<String> errors = new ArrayList<>();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            for (int i = 0; i < skill.getTestCases().size(); i++) {
                String testCode = skill.getTestCases().get(i);
                int testNum = i + 1;

                try {
                    Future<String> future = executor.submit(() -> {
                        try {
                            Object result = skill.executeTest(testCode);
                            // If test returns false or throws, it failed
                            if (result instanceof Boolean && !(Boolean) result) {
                                return "Test returned false";
                            }
                            return null; // Test passed
                        } catch (Exception e) {
                            return e.getMessage();
                        }
                    });

                    String error = future.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    if (error != null) {
                        errors.add("Test " + testNum + " failed: " + error);
                    }

                } catch (TimeoutException e) {
                    errors.add("Test " + testNum + " timed out after " + TEST_TIMEOUT_SECONDS + "s");
                } catch (Exception e) {
                    errors.add("Test " + testNum + " error: " + e.getMessage());
                }
            }
        } finally {
            executor.shutdownNow();
        }

        return errors;
    }

    /**
     * Run integration tests through the full skill.execute() pipeline.
     * Uses a per-test timeout to prevent runaway tests.
     */
    private GeneratedSkill.IntegrationTestResults runIntegrationTests(GeneratedSkill skill) {
        logger.info("Running {} integration tests for skill '{}'",
            skill.getIntegrationTests().size(), skill.getName());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<GeneratedSkill.IntegrationTestResults> future = executor.submit(
                () -> skill.runIntegrationTests());
            // Total timeout: 30s per test or 120s max
            int timeoutSeconds = Math.min(
                skill.getIntegrationTests().size() * 30, 120);
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            logger.warn("Integration tests for '{}' timed out", skill.getName());
            return new GeneratedSkill.IntegrationTestResults(
                List.of(new GeneratedSkill.IntegrationTestResult(
                    "timeout", false, "Integration tests timed out", 0, List.of(), List.of())),
                0, 1, 1, 0);
        } catch (Exception e) {
            logger.warn("Integration tests for '{}' failed: {}", skill.getName(), e.getMessage());
            return new GeneratedSkill.IntegrationTestResults(
                List.of(new GeneratedSkill.IntegrationTestResult(
                    "error", false, "Error: " + e.getMessage(), 0, List.of(), List.of())),
                0, 1, 1, 0);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Result of skill validation including quality score and integration test results.
     */
    public record ValidationResult(
        boolean passed,
        List<String> errors,
        SkillQualityScore qualityScore,
        GeneratedSkill.IntegrationTestResults integrationTestResults
    ) {
        public String errorsAsString() {
            return String.join("; ", errors);
        }

        public boolean hasQualityScore() {
            return qualityScore != null;
        }

        public boolean hasIntegrationTestResults() {
            return integrationTestResults != null;
        }

        public int integrationTestsPassed() {
            return integrationTestResults != null ? integrationTestResults.passed() : 0;
        }

        public int integrationTestsFailed() {
            return integrationTestResults != null ? integrationTestResults.failed() : 0;
        }
    }
}
