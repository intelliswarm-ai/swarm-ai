package ai.intelliswarm.swarmai.skill.runtime;

import groovy.lang.GroovyShell;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.MethodPointerExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.MultipleCompilationErrorsException;
import org.codehaus.groovy.control.customizers.ImportCustomizer;
import org.codehaus.groovy.control.customizers.SecureASTCustomizer;
import org.codehaus.groovy.control.messages.ExceptionMessage;
import org.codehaus.groovy.control.messages.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Compile-time sandbox for LLM-generated Groovy skills — the single source of
 * truth for the {@link CompilerConfiguration} used everywhere generated code is
 * compiled or executed ({@code GeneratedSkill} execution, test/assertion runs,
 * and {@code GroovyInProcRuntime} validation).
 *
 * <p>Security model — ALLOWLIST, enforced at the AST level:
 * <ul>
 *   <li>{@code setIndirectImportCheckEnabled(true)}: every class the code
 *       references — imported, fully-qualified, or auto-imported from
 *       {@code java.lang} — must be on the allowlist. A denylist of import
 *       statements is useless because Groovy code can reference
 *       {@code java.io.File} or {@code Runtime} without any import.</li>
 *   <li>{@code java.lang} is deliberately NOT blanket-allowed: that would
 *       re-admit {@code Runtime}, {@code ProcessBuilder}, {@code System},
 *       {@code Thread}, {@code Class} and {@code ClassLoader}. Only value and
 *       utility types are allowed individually.</li>
 *   <li>An expression checker blocks the reflective escape hatches that survive
 *       class-reference checks: {@code .class}/{@code .metaClass} property
 *       access, {@code getClass()}/{@code forName()}/{@code newInstance()},
 *       metaclass tampering, method pointers, and dynamic (GString) method or
 *       property names, which would otherwise allow {@code obj."${'ex'+'it'}"()}.</li>
 * </ul>
 *
 * <p>Residual risk: an in-process compile-time sandbox cannot stop every attack a
 * malicious model could craft (e.g. resource exhaustion). Skills that need real
 * isolation should use the CONTAINER skill type, which runs code out-of-process.
 * Execution timeouts are enforced by the callers.
 */
public final class GroovySkillSandbox {

    /** Packages generated skills may use freely (data wrangling, no I/O). */
    private static final List<String> ALLOWED_STAR_IMPORTS = List.of(
            "java.util", "java.util.regex", "java.util.stream", "java.util.function",
            "java.math", "java.text",
            "java.time", "java.time.format", "java.time.temporal",
            "groovy.json", "groovy.xml");

    /** Individual classes allowed beyond the packages above. */
    private static final List<String> ALLOWED_IMPORTS = List.of(
            "java.net.URLEncoder", "java.net.URLDecoder",
            // java.lang value/utility types — NOT the whole package (see class doc)
            "java.lang.Object", "java.lang.String", "java.lang.CharSequence",
            "java.lang.StringBuilder", "java.lang.StringBuffer",
            "java.lang.Boolean", "java.lang.Byte", "java.lang.Short", "java.lang.Character",
            "java.lang.Integer", "java.lang.Long", "java.lang.Float", "java.lang.Double",
            "java.lang.Number", "java.lang.Math", "java.lang.Void", "java.lang.Iterable",
            "java.lang.Comparable", "java.lang.Enum",
            // exceptions generated code legitimately throws or catches
            "java.lang.Throwable", "java.lang.Exception", "java.lang.RuntimeException",
            "java.lang.Error", "java.lang.AssertionError",
            "java.lang.IllegalArgumentException", "java.lang.IllegalStateException",
            "java.lang.NullPointerException", "java.lang.NumberFormatException",
            "java.lang.ArithmeticException", "java.lang.IndexOutOfBoundsException",
            "java.lang.ArrayIndexOutOfBoundsException", "java.lang.StringIndexOutOfBoundsException",
            "java.lang.UnsupportedOperationException", "java.lang.ClassCastException",
            // groovy runtime types that appear in ordinary scripts (closures, GStrings, ranges)
            "groovy.lang.Closure", "groovy.lang.GString", "groovy.lang.Range",
            "groovy.lang.IntRange", "groovy.lang.ObjectRange", "groovy.lang.Tuple",
            "groovy.lang.Tuple2", "groovy.lang.MissingMethodException",
            "groovy.lang.MissingPropertyException");

    /**
     * Method names that are reflective/system escape hatches regardless of receiver.
     * Checked on every call expression because receivers are usually dynamically
     * typed in Groovy, so receiver-based checks alone can't see them.
     */
    private static final Set<String> BLOCKED_METHODS = Set.of(
            "getClass", "forName", "newInstance", "setAccessible",
            "invokeMethod", "getMetaClass", "setMetaClass", "propertyMissing",
            "methodMissing", "getRuntime", "exec", "exit", "halt",
            "load", "loadLibrary", "getClassLoader", "getSystemClassLoader",
            "parseClass", "evaluate", "sleep");

    /**
     * {@code execute()} is special: Groovy's {@code String.execute()} /
     * {@code List.execute()} extensions spawn a shell process (arbitrary command
     * execution, and no {@code Runtime}/{@code exec} token appears in the source
     * for a string scan to catch). But {@code execute()} is ALSO how skills invoke
     * their tools — {@code tools.http_request.execute(...)} is the documented
     * calling convention. So the call is allowed only when its receiver chain is
     * rooted at the {@code tools} binding, and blocked on every other receiver.
     */
    private static final String EXECUTE = "execute";
    private static final String TOOLS_BINDING = "tools";

    /** Property reads that reach reflection: {@code foo.class.forName(...)}. */
    private static final Set<String> BLOCKED_PROPERTIES = Set.of("class", "metaClass", "classLoader");

    private GroovySkillSandbox() {
    }

    /**
     * Builds the hardened compiler configuration. Every compilation of generated
     * skill code — execution, tests, assertions, validation — must use this.
     */
    public static CompilerConfiguration hardenedConfig() {
        CompilerConfiguration config = new CompilerConfiguration();

        ImportCustomizer imports = new ImportCustomizer();
        imports.addStarImports("java.util", "java.math", "groovy.json", "groovy.xml",
                "java.util.regex", "java.time");
        imports.addImports("java.net.URLEncoder", "java.net.URLDecoder");
        config.addCompilationCustomizers(imports);

        SecureASTCustomizer secure = new SecureASTCustomizer();
        secure.setIndirectImportCheckEnabled(true);
        secure.setAllowedStarImports(ALLOWED_STAR_IMPORTS);
        secure.setAllowedImports(ALLOWED_IMPORTS);
        secure.setClosuresAllowed(true);
        secure.setMethodDefinitionAllowed(true);
        secure.setPackageAllowed(false);
        secure.addExpressionCheckers(GroovySkillSandbox::isExpressionAllowed);
        config.addCompilationCustomizers(secure);

        return config;
    }

    private static boolean isExpressionAllowed(Expression expression) {
        if (expression instanceof MethodCallExpression call) {
            Expression method = call.getMethod();
            // Dynamic method names (obj."${name}"()) defeat static checking — reject.
            if (!(method instanceof ConstantExpression constant)) {
                return false;
            }
            String name = String.valueOf(constant.getValue());
            if (EXECUTE.equals(name)) {
                return isRootedAtToolsBinding(call.getObjectExpression());
            }
            return !BLOCKED_METHODS.contains(name);
        }
        if (expression instanceof StaticMethodCallExpression staticCall) {
            return !BLOCKED_METHODS.contains(staticCall.getMethod());
        }
        if (expression instanceof PropertyExpression property) {
            if (!(property.getProperty() instanceof ConstantExpression constant)) {
                return false; // dynamic property name
            }
            return !BLOCKED_PROPERTIES.contains(String.valueOf(constant.getValue()));
        }
        // Method pointers (obj.&method) produce closures that dodge name checks.
        return !(expression instanceof MethodPointerExpression);
    }

    /**
     * Walks a receiver expression down to its root variable and reports whether that
     * root is the {@code tools} binding. Covers {@code tools.name.execute(...)},
     * {@code tools['name'].execute(...)} and {@code tools.get('name').execute(...)};
     * anything else — a string literal, a local variable, a method result — is not
     * a tool and may not be {@code execute()}d.
     */
    private static boolean isRootedAtToolsBinding(Expression receiver) {
        Expression current = receiver;
        while (true) {
            if (current instanceof VariableExpression variable) {
                return TOOLS_BINDING.equals(variable.getName());
            }
            if (current instanceof PropertyExpression property) {
                current = property.getObjectExpression();
            } else if (current instanceof MethodCallExpression call) {
                current = call.getObjectExpression();
            } else if (current instanceof BinaryExpression binary) {
                current = binary.getLeftExpression(); // subscript: tools['name']
            } else {
                return false;
            }
        }
    }

    /**
     * Compiles the code under the hardened configuration and returns the sandbox
     * violations found, empty if the code is clean. Plain syntax errors are NOT
     * reported here — they are the syntax check's job — only violations raised
     * by the secure AST customizer.
     */
    public static List<String> scan(String code) {
        try {
            new GroovyShell(hardenedConfig()).parse(code);
            return List.of();
        } catch (MultipleCompilationErrorsException e) {
            List<String> violations = new ArrayList<>();
            for (Message message : e.getErrorCollector().getErrors()) {
                if (message instanceof ExceptionMessage exceptionMessage
                        && exceptionMessage.getCause() instanceof SecurityException se) {
                    violations.add("Security violation: " + se.getMessage());
                }
            }
            return violations;
        } catch (Exception e) {
            // Anything else (syntax, resolution) is handled by the syntax check
            return List.of();
        }
    }
}
