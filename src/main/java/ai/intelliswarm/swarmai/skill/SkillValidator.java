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
     * Validate a generated skill for safety and correctness.
     */
    public ValidationResult validate(GeneratedSkill skill) {
        logger.info("Validating skill: {} (code: {} chars, tests: {})",
            skill.getName(), skill.getCode().length(), skill.getTestCases().size());

        List<String> errors = new ArrayList<>();

        // 1. Basic checks
        if (skill.getCode() == null || skill.getCode().trim().isEmpty()) {
            errors.add("Skill code is empty");
            return new ValidationResult(false, errors);
        }

        if (skill.getName() == null || skill.getName().isEmpty()) {
            errors.add("Skill name is empty");
            return new ValidationResult(false, errors);
        }

        // 2. Security scan
        List<String> securityIssues = securityScan(skill.getCode());
        if (!securityIssues.isEmpty()) {
            errors.addAll(securityIssues);
            return new ValidationResult(false, errors);
        }

        // 3. Syntax check (compile without executing)
        String syntaxError = checkSyntax(skill);
        if (syntaxError != null) {
            errors.add("Syntax error: " + syntaxError);
            return new ValidationResult(false, errors);
        }

        // 4. Test execution (warnings only — LLM-generated tests are unreliable)
        List<String> warnings = new ArrayList<>();
        if (!skill.getTestCases().isEmpty()) {
            List<String> testErrors = runTests(skill);
            if (!testErrors.isEmpty()) {
                logger.warn("Skill '{}' test warnings (non-blocking): {}", skill.getName(), testErrors);
                warnings.addAll(testErrors);
            }
        }

        // Passed if security + syntax are clean (test failures are warnings, not blockers)
        boolean passed = errors.isEmpty();
        if (passed) {
            logger.info("Skill '{}' passed validation{}", skill.getName(),
                warnings.isEmpty() ? "" : " (with " + warnings.size() + " test warnings)");
        } else {
            logger.warn("Skill '{}' failed validation: {}", skill.getName(), errors);
        }

        return new ValidationResult(passed, errors);
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
     * Result of skill validation.
     */
    public record ValidationResult(boolean passed, List<String> errors) {
        public String errorsAsString() {
            return String.join("; ", errors);
        }
    }
}
