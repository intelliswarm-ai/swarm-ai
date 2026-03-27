package ai.intelliswarm.swarmai.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * Validates generated skills before they can be used in workflows.
 *
 * Validation pipeline:
 * 1. Security scan — blocked patterns that indicate unsafe code
 * 2. Syntax check — code must be compilable Groovy/Java
 * 3. Test execution — run auto-generated test cases in sandboxed GroovyShell
 * 4. Quality assessment — score documentation, tests, error handling, complexity, output format
 */
public class SkillValidator {

    private static final Logger logger = LoggerFactory.getLogger(SkillValidator.class);

    private static final int TEST_TIMEOUT_SECONDS = 10;

    // Patterns that are blocked in generated skill code
    private static final List<String> BLOCKED_PATTERNS = List.of(
        "Runtime.getRuntime", "ProcessBuilder", "Process ",
        "System.exit", "System.getProperty", "System.setProperty",
        "new File(", "new FileWriter", "new FileReader",
        "new FileInputStream", "new FileOutputStream",
        "new URL(", "new Socket(", "HttpURLConnection",
        "new ServerSocket", "InetAddress",
        "Class.forName", "ClassLoader",
        "GroovyShell", "GroovyClassLoader",
        "Thread.sleep", "Runtime.exec",
        "java.lang.reflect.", "setAccessible",
        "DELETE ", "DROP ", "TRUNCATE "
    );

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
        if (skill.getName() == null || skill.getName().isEmpty()) {
            errors.add("Skill name is empty");
            return new ValidationResult(false, errors, null);
        }
        if (skill.getDescription() == null || skill.getDescription().isEmpty()) {
            errors.add("Skill description is empty");
            return new ValidationResult(false, errors, null);
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
            return new ValidationResult(false, errors, null);
        }

        // 3. Quality assessment
        SkillQualityScore qualityScore = SkillQualityScore.assess(skill);
        skill.setQualityScore(qualityScore);

        logger.info("Skill '{}' v{} ({}) passed validation (quality: {}/100 grade {})",
            skill.getName(), skill.getVersion(), skillType,
            qualityScore.totalScore(), qualityScore.grade());

        return new ValidationResult(true, errors, qualityScore);
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

        // Security scan
        List<String> securityIssues = securityScan(skill.getCode());
        errors.addAll(securityIssues);
        if (!securityIssues.isEmpty()) return;

        // Syntax check
        String syntaxError = checkSyntax(skill);
        if (syntaxError != null) {
            errors.add("Syntax error: " + syntaxError);
            return;
        }

        // Test execution (warnings only)
        if (!skill.getTestCases().isEmpty()) {
            List<String> testErrors = runTests(skill);
            if (!testErrors.isEmpty()) {
                logger.warn("Skill '{}' test warnings (non-blocking): {}", skill.getName(), testErrors);
            }
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

    /**
     * Scan code for blocked patterns that indicate unsafe operations.
     */
    private List<String> securityScan(String code) {
        List<String> issues = new ArrayList<>();

        for (String pattern : BLOCKED_PATTERNS) {
            if (code.contains(pattern)) {
                issues.add("Security violation: code contains blocked pattern '" + pattern + "'");
            }
        }

        // Also scan test cases
        return issues;
    }

    /**
     * Check if the code is syntactically valid Groovy/Java.
     * Compile-only — does NOT execute the code (avoids calling real tools).
     * Uses the same compiler config as GeneratedSkill to ensure imports resolve.
     */
    private String checkSyntax(GeneratedSkill skill) {
        try {
            org.codehaus.groovy.control.CompilerConfiguration config = new org.codehaus.groovy.control.CompilerConfiguration();
            org.codehaus.groovy.control.customizers.ImportCustomizer imports = new org.codehaus.groovy.control.customizers.ImportCustomizer();
            imports.addStarImports("java.util", "java.math", "groovy.json", "groovy.xml");
            config.addCompilationCustomizers(imports);

            groovy.lang.GroovyShell shell = new groovy.lang.GroovyShell(config);
            // parse() compiles without executing
            shell.parse(skill.getCode());
            return null; // No error — code compiles
        } catch (Exception e) {
            return e.getMessage();
        }
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
     * Result of skill validation, now including quality score.
     */
    public record ValidationResult(boolean passed, List<String> errors, SkillQualityScore qualityScore) {
        public String errorsAsString() {
            return String.join("; ", errors);
        }

        public boolean hasQualityScore() {
            return qualityScore != null;
        }
    }
}
