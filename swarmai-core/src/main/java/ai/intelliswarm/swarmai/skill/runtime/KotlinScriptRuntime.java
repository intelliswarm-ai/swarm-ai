package ai.intelliswarm.swarmai.skill.runtime;

import javax.script.Compilable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class KotlinScriptRuntime implements SkillRuntime {

    public static final String ID = "kotlin-script";

    // Same boundary as Groovy: in-proc runtimes share JVM memory, so HTTP/file/network
    // access must come from subprocess or container runtimes, not from here.
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

    private final ScriptEngine engine;

    public KotlinScriptRuntime() {
        ScriptEngine kotlin = new ScriptEngineManager().getEngineByName("kotlin");
        if (kotlin == null) {
            throw new IllegalStateException(
                "Kotlin script engine not found on classpath. " +
                "Add 'org.jetbrains.kotlin:kotlin-scripting-jsr223' to enable the Kotlin skill runtime.");
        }
        this.engine = kotlin;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<String> supportedLanguages() {
        return Set.of(SkillSource.KOTLIN_SCRIPT);
    }

    @Override
    public SecurityReport securityScan(SkillSource source) {
        List<String> violations = new ArrayList<>();
        String code = source.code();
        for (String pattern : BLOCKED_PATTERNS) {
            if (code.contains(pattern)) {
                violations.add("Security violation: code contains blocked pattern '" + pattern + "'");
            }
        }
        return violations.isEmpty() ? SecurityReport.passed() : SecurityReport.failed(violations);
    }

    @Override
    public SyntaxReport syntaxCheck(SkillSource source) {
        if (!(engine instanceof Compilable compilable)) {
            return SyntaxReport.failed("Kotlin engine does not support compilation");
        }
        try {
            compilable.compile(source.code());
            return SyntaxReport.passed();
        } catch (ScriptException e) {
            return SyntaxReport.failed(e.getMessage());
        }
    }
}
