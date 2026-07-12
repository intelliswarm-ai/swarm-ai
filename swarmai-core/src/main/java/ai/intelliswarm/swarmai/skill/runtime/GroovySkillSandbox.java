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
                return isDirectToolAccess(call.getObjectExpression());
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
     * Reports whether a receiver is a DIRECT tool access — that is, a tool object obtained in one
     * hop from the {@code tools} binding. Exactly three shapes qualify:
     *
     * <pre>
     *   tools.name          (PropertyExpression on the tools variable)
     *   tools['name']       (subscript on the tools variable)
     *   tools.get('name')   (get() on the tools variable)
     * </pre>
     *
     * <p><b>Why this is not a recursive walk.</b> An earlier version walked the receiver chain down
     * to its root variable and accepted anything rooted at {@code tools}. That was a sandbox escape:
     * the receiver of the OUTER call in
     *
     * <pre>tools.echo.execute(Map.of('text', 'id')).execute().text</pre>
     *
     * is itself a method call rooted at {@code tools}, so it was accepted — and the outer
     * {@code execute()} is {@code String.execute()} on the tool's return value, i.e. arbitrary
     * process execution driven by whatever text a tool happened to return. Tool results are
     * attacker-influenceable (a scraped page, an API response), which made it directly exploitable.
     *
     * <p>A real tool is always one hop from the binding, so we match that shape and nothing else.
     * The return value of {@code execute()} is a {@code String}, never a tool, and can never be
     * {@code execute()}d again.
     */
    private static boolean isDirectToolAccess(Expression receiver) {
        if (receiver instanceof PropertyExpression property) {
            // tools.name — the property name may be dynamic; the receiver check still holds,
            // because whatever it resolves to came out of the tools map.
            return isToolsVariable(property.getObjectExpression());
        }
        if (receiver instanceof BinaryExpression binary) {
            // tools['name'] — subscript access
            return binary.getOperation() != null
                    && "[".equals(binary.getOperation().getText())
                    && isToolsVariable(binary.getLeftExpression());
        }
        if (receiver instanceof MethodCallExpression call) {
            // tools.get('name') — and ONLY get(); notably not tools.foo.execute(...).execute()
            return call.getMethod() instanceof ConstantExpression method
                    && "get".equals(String.valueOf(method.getValue()))
                    && isToolsVariable(call.getObjectExpression());
        }
        return false;
    }

    private static boolean isToolsVariable(Expression expression) {
        return expression instanceof VariableExpression variable
                && TOOLS_BINDING.equals(variable.getName());
    }

    /**
     * Rejects source that carries a compile-time AST transformation annotation.
     *
     * <p>This is the single most important check in the sandbox, and it must run BEFORE the code is
     * ever handed to {@code parse()}/{@code evaluate()}. {@link SecureASTCustomizer} runs at the
     * {@code CANONICALIZATION} compile phase, but annotation-triggered AST transforms run EARLIER —
     * {@code @Grab} at {@code CONVERSION}, {@code @groovy.transform.ASTTest} at
     * {@code SEMANTIC_ANALYSIS} — so their code executes during {@code parse()} itself, before any
     * customizer sees the AST. That means a hostile skill runs its payload merely by being
     * VALIDATED (securityScan/syntaxCheck both parse). {@code @ASTTest(value={ ... })} is a
     * confirmed arbitrary-code-execution escape this way; no {@code SecureASTCustomizer} setting can
     * stop it.
     *
     * <p>Every compile-time transform is annotation-triggered, and legitimate skill code (scripts of
     * expressions and tool calls) never needs annotations — so the safe, allowlist-shaped rule is:
     * untrusted skill code may contain NO annotations at all. We detect them textually, before the
     * compiler runs, after stripping comments and string/GString literals so an {@code @Grab} inside
     * a string is not a false positive.
     *
     * @throws SecurityException if the source contains an annotation
     */
    public static void rejectCompileTimeTransforms(String code) {
        String stripped = stripCommentsAndStrings(code);
        // An annotation is '@' + identifier, not preceded by '.' (which would be obj.@field
        // attribute access — handled separately by the AST checker, and harmless at parse time).
        java.util.regex.Matcher m = ANNOTATION.matcher(stripped);
        if (m.find()) {
            throw new SecurityException(
                    "annotations are not permitted in skill code (found '@" + m.group(1) + "'). "
                    + "Compile-time AST transforms such as @Grab and @ASTTest execute during "
                    + "compilation, before the sandbox can inspect the code.");
        }
    }

    private static final java.util.regex.Pattern ANNOTATION =
            java.util.regex.Pattern.compile("(?<![.\\w$])@([A-Za-z_$][\\w$.]*)");

    /**
     * Removes line comments, block comments, and single/double/triple-quoted string literals,
     * replacing each with a space so positions of remaining tokens are preserved well enough for the
     * annotation scan. Conservative by design: if anything is ambiguous it leaves content in place,
     * which can only cause a FALSE POSITIVE (a rejected-but-safe skill), never a false negative.
     */
    private static String stripCommentsAndStrings(String code) {
        StringBuilder out = new StringBuilder(code.length());
        int i = 0;
        int n = code.length();
        while (i < n) {
            char c = code.charAt(i);
            char next = i + 1 < n ? code.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {                       // line comment
                while (i < n && code.charAt(i) != '\n') i++;
            } else if (c == '/' && next == '*') {                // block comment
                i += 2;
                while (i + 1 < n && !(code.charAt(i) == '*' && code.charAt(i + 1) == '/')) i++;
                i += 2;
                out.append(' ');
            } else if (c == '"' || c == '\'') {                  // string / GString (incl. triple)
                boolean triple = next == c && i + 2 < n && code.charAt(i + 2) == c;
                i = skipString(code, i, c, triple);
                out.append(' ');
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int skipString(String code, int start, char quote, boolean triple) {
        int n = code.length();
        int i = start + (triple ? 3 : 1);
        while (i < n) {
            char c = code.charAt(i);
            if (c == '\\') {                                     // escape — skip next char
                i += 2;
                continue;
            }
            if (triple) {
                if (c == quote && i + 2 < n && code.charAt(i + 1) == quote && code.charAt(i + 2) == quote) {
                    return i + 3;
                }
            } else if (c == quote || c == '\n') {
                return i + 1;
            }
            i++;
        }
        return n;
    }

    /**
     * Parses code under the hardened configuration, having first rejected compile-time transforms.
     * Use this everywhere untrusted skill code is compiled, so the pre-parse gate can never be
     * forgotten at a call site (as it was forgotten for the whole transform class until now).
     *
     * @throws SecurityException if the code carries an annotation
     */
    public static GroovyShell newGuardedShell(groovy.lang.Binding binding, String code) {
        rejectCompileTimeTransforms(code);
        return new GroovyShell(binding, hardenedConfig());
    }

    /**
     * Compiles the code under the hardened configuration and returns the sandbox
     * violations found, empty if the code is clean. Plain syntax errors are NOT
     * reported here — they are the syntax check's job — only violations raised
     * by the secure AST customizer.
     */
    public static List<String> scan(String code) {
        try {
            rejectCompileTimeTransforms(code);
        } catch (SecurityException se) {
            return List.of("Security violation: " + se.getMessage());
        }
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
