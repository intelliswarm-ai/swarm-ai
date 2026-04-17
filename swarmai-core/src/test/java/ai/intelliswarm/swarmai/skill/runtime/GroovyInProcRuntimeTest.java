package ai.intelliswarm.swarmai.skill.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("GroovyInProcRuntime")
class GroovyInProcRuntimeTest {

    private final GroovyInProcRuntime runtime = new GroovyInProcRuntime();

    @Test
    void exposesExpectedIdentity() {
        assertEquals("groovy-inproc", runtime.id());
        assertTrue(runtime.supportedLanguages().contains(SkillSource.GROOVY));
    }

    @Nested
    @DisplayName("SQL destructive keywords — case-insensitive")
    class SqlDestructiveKeywords {

        @Test
        void blocksUppercaseDelete() {
            SecurityReport report = scan("def q = \"DELETE FROM users\"");
            assertFalse(report.ok());
            assertHasViolation(report, "DELETE ");
        }

        @Test
        void blocksLowercaseDelete() {
            SecurityReport report = scan("def q = \"delete from users\"");
            assertFalse(report.ok(),
                "Lowercase 'delete from' must be caught — previously bypassed case-sensitive check");
            assertHasViolation(report, "DELETE ");
        }

        @Test
        void blocksMixedCaseDrop() {
            SecurityReport report = scan("def q = \"DrOp TaBlE users\"");
            assertFalse(report.ok());
            assertHasViolation(report, "DROP ");
        }

        @Test
        void blocksLowercaseTruncate() {
            SecurityReport report = scan("def q = \"truncate logs\"");
            assertFalse(report.ok());
            assertHasViolation(report, "TRUNCATE ");
        }
    }

    @Nested
    @DisplayName("Java API patterns — case-sensitive (no false positives)")
    class JavaApiPatterns {

        @Test
        void blocksExactProcessBuilder() {
            SecurityReport report = scan("def pb = new ProcessBuilder(\"ls\")");
            assertFalse(report.ok());
            assertHasViolation(report, "ProcessBuilder");
        }

        @Test
        void allowsLowercaseProcessAsVariableName() {
            SecurityReport report = scan("def process = \"harmless string\"\nprocess.toUpperCase()");
            assertTrue(report.ok(),
                "Lowercase 'process' as a local variable must not trigger the Java-API blocklist");
        }

        @Test
        void allowsLowercaseClassloaderInIdentifier() {
            // "classloader" lowercase is not a Java class reference — shouldn't match ClassLoader
            SecurityReport report = scan("def classloader_label = \"just a string\"");
            assertTrue(report.ok());
        }
    }

    @Nested
    @DisplayName("Benign code passes")
    class BenignCode {

        @Test
        void simpleArithmeticPasses() {
            SecurityReport report = scan("def total = (1..10).sum()\ntotal");
            assertTrue(report.ok());
            assertTrue(report.violations().isEmpty());
        }

        @Test
        void jsonTransformationPasses() {
            SecurityReport report = scan("""
                def json = new groovy.json.JsonSlurper().parseText(params.get("input"))
                json.values*.toUpperCase()
                """);
            assertTrue(report.ok());
        }
    }

    @Nested
    @DisplayName("Syntax check")
    class SyntaxCheck {

        @Test
        void acceptsValidGroovy() {
            SyntaxReport report = runtime.syntaxCheck(new SkillSource(
                SkillSource.GROOVY,
                "def x = params.get(\"input\") ?: \"default\"\nx",
                List.of()));
            assertTrue(report.ok(), report.errorMessage());
        }

        @Test
        void rejectsInvalidGroovy() {
            SyntaxReport report = runtime.syntaxCheck(new SkillSource(
                SkillSource.GROOVY,
                "def x = ((((",
                List.of()));
            assertFalse(report.ok());
        }
    }

    private SecurityReport scan(String code) {
        return runtime.securityScan(new SkillSource(SkillSource.GROOVY, code, List.of()));
    }

    private static void assertHasViolation(SecurityReport report, String expectedPattern) {
        assertTrue(
            report.violations().stream().anyMatch(v -> v.contains(expectedPattern)),
            "Expected a violation mentioning '" + expectedPattern + "' but got: " + report.violations());
    }
}
