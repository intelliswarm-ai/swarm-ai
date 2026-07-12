package ai.intelliswarm.swarmai.skill.runtime;

import groovy.lang.GroovyShell;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class GroovyInProcRuntime implements SkillRuntime {

    public static final String ID = "groovy-inproc";

    // Java/Groovy identifiers — exact-case match is intentional. A lowercase
    // "process" or "classloader" as a local variable name is legitimate;
    // only the actual class/method refs are dangerous.
    private static final List<String> JAVA_API_BLOCKED = List.of(
        "Runtime.getRuntime", "ProcessBuilder", "Process ",
        "System.exit", "System.getProperty", "System.setProperty",
        "new File(", "new FileWriter", "new FileReader",
        "new FileInputStream", "new FileOutputStream",
        "new URL(", "new Socket(", "HttpURLConnection",
        "new ServerSocket", "InetAddress",
        "Class.forName", "ClassLoader",
        "GroovyShell", "GroovyClassLoader",
        "Thread.sleep", "Runtime.exec",
        "java.lang.reflect.", "setAccessible"
    );

    // SQL destructive keywords — matched case-insensitively. A destructive
    // statement is destructive regardless of the case the LLM chose to emit.
    private static final List<String> SQL_DESTRUCTIVE_BLOCKED = List.of(
        "DELETE ", "DROP ", "TRUNCATE "
    );

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<String> supportedLanguages() {
        return Set.of(SkillSource.GROOVY);
    }

    @Override
    public SecurityReport securityScan(SkillSource source) {
        List<String> violations = new ArrayList<>();
        String code = source.code();
        String lowerCode = code.toLowerCase();
        // Fast string pre-filter: catches the obvious cases with a readable message.
        // NOT the security boundary — that's the AST allowlist below, which also
        // guards every execution site via the same GroovySkillSandbox config.
        for (String pattern : JAVA_API_BLOCKED) {
            if (code.contains(pattern)) {
                violations.add("Security violation: code contains blocked pattern '" + pattern + "'");
            }
        }
        for (String pattern : SQL_DESTRUCTIVE_BLOCKED) {
            if (lowerCode.contains(pattern.toLowerCase())) {
                violations.add("Security violation: code contains blocked pattern '" + pattern + "'");
            }
        }
        violations.addAll(GroovySkillSandbox.scan(code));
        return violations.isEmpty() ? SecurityReport.passed() : SecurityReport.failed(violations);
    }

    @Override
    public SyntaxReport syntaxCheck(SkillSource source) {
        try {
            // Compile under the same hardened config used at execution time, so
            // "compiles here" means "compiles when the skill actually runs".
            GroovyShell shell = new GroovyShell(GroovySkillSandbox.hardenedConfig());
            shell.parse(source.code());
            return SyntaxReport.passed();
        } catch (Exception e) {
            return SyntaxReport.failed(e.getMessage());
        }
    }
}
