package ai.intelliswarm.swarmai.tool.code.build;

import ai.intelliswarm.swarmai.api.PublicApi;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Auto-detects a project's test framework from on-disk markers and runs the
 * test suite, returning truncated stdout+stderr plus the exit code.
 *
 * <p>Detection (first match wins):
 * <ul>
 *   <li>{@code pom.xml} → {@code mvn -B -q test}</li>
 *   <li>{@code build.gradle(.kts)} → {@code ./gradlew test --console=plain}
 *       (or {@code gradle} if no wrapper)</li>
 *   <li>{@code package.json} → {@code npm test --silent}
 *       (or {@code yarn test}/{@code pnpm test} when a lockfile signals)</li>
 *   <li>{@code Cargo.toml} → {@code cargo test}</li>
 *   <li>{@code pyproject.toml} / {@code setup.py} / {@code tests/} → {@code pytest -q}</li>
 *   <li>{@code go.mod} → {@code go test ./...}</li>
 * </ul>
 *
 * <p>The agent may override detection by passing {@code runner=mvn} (etc.).
 * For tightly-scoped runs, pass {@code target=path/to/test_file} — the tool
 * forwards it as an extra arg so e.g. {@code pytest tests/test_x.py} works.
 *
 * @since 1.0.26
 */
@PublicApi(since = "1.0.26")
@Component
public class TestRunnerTool implements BaseTool {

    private static final long DEFAULT_TIMEOUT_SEC = 300;
    private static final int MAX_OUTPUT_CHARS = 50_000;

    private final Path projectRoot;

    public TestRunnerTool() { this(null); }

    public TestRunnerTool(Path projectRoot) {
        this.projectRoot = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
    }

    @Override public String getFunctionName() { return "test_runner"; }

    @Override
    public String getDescription() {
        return "Run the project's test suite, auto-detecting mvn/gradle/npm/cargo/pytest/go from on-disk markers. "
             + "Pass {target} to scope to a single test file/class. Returns exit code + truncated output. "
             + "Use this after every code edit that could affect behaviour.";
    }

    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.WORKSPACE_WRITE; }
    @Override public String getCategory() { return "code-build"; }
    @Override public List<String> getTags() { return List.of("test", "build", "ci"); }
    @Override public boolean isAsync() { return false; }
    @Override public int getMaxResponseLength() { return MAX_OUTPUT_CHARS; }

    @Override
    public Object execute(Map<String, Object> parameters) {
        Path cwd = resolveWorkingDir((String) parameters.getOrDefault("path", "."));
        if (cwd == null) return "Error: refusing to run outside project root";
        if (!Files.isDirectory(cwd)) return "Error: not a directory: " + cwd;

        String runner = ((String) parameters.getOrDefault("runner", "")).trim().toLowerCase();
        if (runner.isEmpty()) {
            runner = detectRunner(cwd);
            if (runner == null) {
                return "Error: could not auto-detect a test runner in " + cwd
                     + ". Pass 'runner' explicitly (mvn|gradle|npm|cargo|pytest|go).";
            }
        }

        String target = (String) parameters.get("target");
        List<String> argv = buildArgv(runner, target, cwd);
        if (argv == null) {
            return "Error: unsupported runner '" + runner + "'";
        }

        long timeoutSec = parameters.get("timeout_sec") instanceof Number n
                ? Math.max(5, Math.min(900, n.intValue())) : DEFAULT_TIMEOUT_SEC;

        return runProcess(argv, cwd, timeoutSec, runner);
    }

    static String detectRunner(Path cwd) {
        if (Files.exists(cwd.resolve("pom.xml"))) return "mvn";
        if (Files.exists(cwd.resolve("build.gradle")) || Files.exists(cwd.resolve("build.gradle.kts"))) return "gradle";
        if (Files.exists(cwd.resolve("package.json"))) return "npm";
        if (Files.exists(cwd.resolve("Cargo.toml"))) return "cargo";
        if (Files.exists(cwd.resolve("pyproject.toml"))
            || Files.exists(cwd.resolve("setup.py"))
            || Files.isDirectory(cwd.resolve("tests"))) return "pytest";
        if (Files.exists(cwd.resolve("go.mod"))) return "go";
        return null;
    }

    static List<String> buildArgv(String runner, String target, Path cwd) {
        List<String> argv = new ArrayList<>();
        switch (runner) {
            case "mvn" -> {
                argv.add("mvn"); argv.add("-B"); argv.add("-q"); argv.add("test");
                if (target != null && !target.isBlank()) {
                    argv.add("-Dtest=" + target);
                }
            }
            case "gradle" -> {
                Path wrapper = cwd.resolve(isWindows() ? "gradlew.bat" : "gradlew");
                argv.add(Files.isExecutable(wrapper) ? wrapper.toString() : "gradle");
                argv.add("test"); argv.add("--console=plain");
                if (target != null && !target.isBlank()) {
                    argv.add("--tests"); argv.add(target);
                }
            }
            case "npm" -> {
                if (Files.exists(cwd.resolve("pnpm-lock.yaml"))) {
                    argv.add("pnpm");
                } else if (Files.exists(cwd.resolve("yarn.lock"))) {
                    argv.add("yarn");
                } else {
                    argv.add("npm");
                }
                argv.add("test"); argv.add("--silent");
                if (target != null && !target.isBlank()) {
                    argv.add("--"); argv.add(target);
                }
            }
            case "cargo" -> {
                argv.add("cargo"); argv.add("test");
                if (target != null && !target.isBlank()) argv.add(target);
            }
            case "pytest" -> {
                argv.add("pytest"); argv.add("-q"); argv.add("--no-header");
                if (target != null && !target.isBlank()) argv.add(target);
            }
            case "go" -> {
                argv.add("go"); argv.add("test");
                argv.add(target != null && !target.isBlank() ? target : "./...");
            }
            default -> { return null; }
        }
        return argv;
    }

    private Object runProcess(List<String> argv, Path cwd, long timeoutSec, String runner) {
        try {
            Process p = new ProcessBuilder(argv)
                    .directory(cwd.toFile())
                    .redirectErrorStream(true)
                    .start();
            boolean finished = p.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return "Error: " + runner + " test run timed out after " + timeoutSec + "s. "
                     + "Increase 'timeout_sec' or narrow with 'target'.";
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = p.exitValue();
            if (out.length() > MAX_OUTPUT_CHARS) {
                // For tests, the SUMMARY at the tail is usually more useful than the head.
                out = "...[truncated " + (out.length() - MAX_OUTPUT_CHARS) + " chars from head]...\n"
                    + out.substring(out.length() - MAX_OUTPUT_CHARS);
            }
            return runner + " test (" + (exit == 0 ? "PASS" : "FAIL exit=" + exit) + "):\n" + out;
        } catch (IOException e) {
            return "Error launching " + argv.get(0) + ": " + e.getMessage()
                 + " — is it installed and on PATH?";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: test run interrupted";
        }
    }

    private Path resolveWorkingDir(String relPath) {
        Path root = projectRoot != null ? projectRoot : Path.of(".").toAbsolutePath().normalize();
        Path resolved = root.resolve(relPath).normalize();
        return resolved.startsWith(root) ? resolved : null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("runner", Map.of(
                "type", "string",
                "description", "Override auto-detection: mvn|gradle|npm|cargo|pytest|go (default: auto)."));
        props.put("target", Map.of(
                "type", "string",
                "description", "Scope to a single test (mvn: -Dtest= value, gradle: --tests value, pytest: file::class, etc.)."));
        props.put("path", Map.of(
                "type", "string",
                "description", "Project subdirectory to run from (default '.').",
                "default", "."));
        props.put("timeout_sec", Map.of(
                "type", "integer",
                "description", "Max seconds to wait (clamped 5..900, default 300)."));
        schema.put("properties", props);
        return schema;
    }
}
