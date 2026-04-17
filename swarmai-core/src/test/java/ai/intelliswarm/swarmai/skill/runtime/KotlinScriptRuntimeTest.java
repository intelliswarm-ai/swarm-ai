package ai.intelliswarm.swarmai.skill.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KotlinScriptRuntimeTest {

    private static final KotlinScriptRuntime RUNTIME = new KotlinScriptRuntime();

    @Test
    void exposesExpectedIdentity() {
        assertEquals("kotlin-script", RUNTIME.id());
        assertTrue(RUNTIME.supportedLanguages().contains(SkillSource.KOTLIN_SCRIPT));
    }

    @Test
    void securityScanFlagsBlockedPattern() {
        SkillSource src = new SkillSource(
            SkillSource.KOTLIN_SCRIPT,
            "val r = Runtime.getRuntime()",
            List.of());

        SecurityReport report = RUNTIME.securityScan(src);

        assertFalse(report.ok());
        assertTrue(report.violations().stream().anyMatch(v -> v.contains("Runtime.getRuntime")));
    }

    @Test
    void securityScanPassesBenignCode() {
        SkillSource src = new SkillSource(
            SkillSource.KOTLIN_SCRIPT,
            "val sum = (1..10).sum()\nsum",
            List.of());

        SecurityReport report = RUNTIME.securityScan(src);

        assertTrue(report.ok());
        assertTrue(report.violations().isEmpty());
    }

    @Test
    void securityScanFlagsKotlinFileAccessWithoutNewKeyword() {
        SkillSource src = new SkillSource(
            SkillSource.KOTLIN_SCRIPT,
            "val data = java.io.File(\"/tmp/demo.txt\").readText()",
            List.of());

        SecurityReport report = RUNTIME.securityScan(src);

        assertFalse(report.ok());
        assertTrue(report.violations().stream().anyMatch(v -> v.contains("java.io.File(")));
    }

    @Test
    void securityScanFlagsKotlinNetworkAccessWithoutNewKeyword() {
        SkillSource src = new SkillSource(
            SkillSource.KOTLIN_SCRIPT,
            "val body = java.net.URL(\"https://example.com\").readText()",
            List.of());

        SecurityReport report = RUNTIME.securityScan(src);

        assertFalse(report.ok());
        assertTrue(report.violations().stream().anyMatch(v -> v.contains("java.net.URL(")));
    }

    @Test
    void securityScanFlagsLowercaseDestructiveSql() {
        SkillSource src = new SkillSource(
            SkillSource.KOTLIN_SCRIPT,
            "val sql = \"delete from users\"",
            List.of());

        SecurityReport report = RUNTIME.securityScan(src);

        assertFalse(report.ok());
        assertTrue(report.violations().stream().anyMatch(v -> v.contains("DELETE ")));
    }

    @Test
    void securityScanFlagsMixedCaseDestructiveSql() {
        SkillSource src = new SkillSource(
            SkillSource.KOTLIN_SCRIPT,
            "val sql = \"DrOp table users\"",
            List.of());

        SecurityReport report = RUNTIME.securityScan(src);

        assertFalse(report.ok());
        assertTrue(report.violations().stream().anyMatch(v -> v.contains("DROP ")));
    }

    @Test
    void syntaxCheckAcceptsValidKotlin() {
        SkillSource src = new SkillSource(
            SkillSource.KOTLIN_SCRIPT,
            "val greeting = \"hello\"\ngreeting.uppercase()",
            List.of());

        SyntaxReport report = RUNTIME.syntaxCheck(src);

        assertTrue(report.ok(), "Expected valid Kotlin to pass syntax check, got: " + report.errorMessage());
        assertNull(report.errorMessage());
    }

    @Test
    void syntaxCheckRejectsInvalidKotlin() {
        SkillSource src = new SkillSource(
            SkillSource.KOTLIN_SCRIPT,
            "val x = ((((",
            List.of());

        SyntaxReport report = RUNTIME.syntaxCheck(src);

        assertFalse(report.ok());
        assertNotNull(report.errorMessage());
    }
}
