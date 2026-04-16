package ai.intelliswarm.swarmai.skill.runtime;

import groovy.lang.GroovyShell;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ImportCustomizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class GroovyInProcRuntime implements SkillRuntime {

    public static final String ID = "groovy-inproc";

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
        for (String pattern : BLOCKED_PATTERNS) {
            if (code.contains(pattern)) {
                violations.add("Security violation: code contains blocked pattern '" + pattern + "'");
            }
        }
        return violations.isEmpty() ? SecurityReport.passed() : SecurityReport.failed(violations);
    }

    @Override
    public SyntaxReport syntaxCheck(SkillSource source) {
        try {
            CompilerConfiguration config = new CompilerConfiguration();
            ImportCustomizer imports = new ImportCustomizer();
            imports.addStarImports("java.util", "java.math", "groovy.json", "groovy.xml",
                "java.util.regex", "java.time");
            imports.addImports("java.net.URLEncoder", "java.net.URLDecoder");
            config.addCompilationCustomizers(imports);

            GroovyShell shell = new GroovyShell(config);
            shell.parse(source.code());
            return SyntaxReport.passed();
        } catch (Exception e) {
            return SyntaxReport.failed(e.getMessage());
        }
    }
}
