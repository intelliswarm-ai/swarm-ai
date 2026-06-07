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
 * Compile / build the current project, with auto-detection of the build tool
 * by on-disk markers. Companion to {@link TestRunnerTool}: this one only
 * <em>compiles</em>, which is cheaper than running tests and what an agent
 * usually wants after writing/editing code to catch syntax + type errors
 * before committing to a slower test cycle.
 *
 * <p>Detection (first match wins):
 * <ul>
 *   <li>{@code pom.xml} → {@code mvn -B -q -DskipTests compile}</li>
 *   <li>{@code build.gradle(.kts)} → {@code ./gradlew compileJava --console=plain}
 *       (or {@code gradle} without wrapper)</li>
 *   <li>{@code package.json} → {@code npm run build --silent}
 *       (or {@code yarn}/{@code pnpm} when lockfile present)</li>
 *   <li>{@code Cargo.toml} → {@code cargo build}</li>
 *   <li>{@code go.mod} → {@code go build ./...}</li>
 *   <li>{@code Makefile} → {@code make}</li>
 *   <li>{@code tsconfig.json} → {@code tsc --noEmit}</li>
 * </ul>
 *
 * @since 1.0.26
 */
@PublicApi(since = "1.0.26")
@Component
public class BuildTool implements BaseTool {

    private static final long DEFAULT_TIMEOUT_SEC = 300;
    private static final int MAX_OUTPUT_CHARS = 50_000;

    private final Path projectRoot;

    public BuildTool() { this(null); }

    public BuildTool(Path projectRoot) {
        this.projectRoot = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
    }

    @Override public String getFunctionName() { return "build"; }

    @Override
    public String getDescription() {
        return "Compile or build the project, auto-detecting mvn/gradle/npm/cargo/go/make/tsc from on-disk "
             + "markers. Returns exit code + truncated output. Use this after editing code to catch syntax "
             + "and type errors fast — cheaper than running the full test suite.";
    }

    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.WORKSPACE_WRITE; }
    @Override public String getCategory() { return "code-build"; }
    @Override public List<String> getTags() { return List.of("build", "compile"); }
    @Override public boolean isAsync() { return false; }
    @Override public int getMaxResponseLength() { return MAX_OUTPUT_CHARS; }

    @Override
    public Object execute(Map<String, Object> parameters) {
        Path cwd = resolveWorkingDir((String) parameters.getOrDefault("path", "."));
        if (cwd == null) return "Error: refusing to run outside project root";
        if (!Files.isDirectory(cwd)) return "Error: not a directory: " + cwd;

        String builder = ((String) parameters.getOrDefault("builder", "")).trim().toLowerCase();
        if (builder.isEmpty()) {
            builder = detectBuilder(cwd);
            if (builder == null) {
                return "Error: could not auto-detect a builder in " + cwd
                     + ". Pass 'builder' (mvn|gradle|npm|cargo|go|make|tsc).";
            }
        }

        List<String> argv = buildArgv(builder, cwd);
        if (argv == null) return "Error: unsupported builder '" + builder + "'";

        long timeoutSec = parameters.get("timeout_sec") instanceof Number n
                ? Math.max(5, Math.min(900, n.intValue())) : DEFAULT_TIMEOUT_SEC;

        try {
            Process p = new ProcessBuilder(argv)
                    .directory(cwd.toFile())
                    .redirectErrorStream(true)
                    .start();
            boolean finished = p.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return "Error: " + builder + " build timed out after " + timeoutSec + "s";
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = p.exitValue();
            // For builds, the TAIL is what matters (errors at the end).
            if (out.length() > MAX_OUTPUT_CHARS) {
                out = "...[truncated " + (out.length() - MAX_OUTPUT_CHARS) + " chars from head]...\n"
                    + out.substring(out.length() - MAX_OUTPUT_CHARS);
            }
            return builder + " build (" + (exit == 0 ? "PASS" : "FAIL exit=" + exit) + "):\n" + out;
        } catch (IOException e) {
            return "Error launching " + argv.get(0) + ": " + e.getMessage()
                 + " — is it installed and on PATH?";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: build interrupted";
        }
    }

    static String detectBuilder(Path cwd) {
        if (Files.exists(cwd.resolve("pom.xml"))) return "mvn";
        if (Files.exists(cwd.resolve("build.gradle")) || Files.exists(cwd.resolve("build.gradle.kts"))) return "gradle";
        if (Files.exists(cwd.resolve("Cargo.toml"))) return "cargo";
        if (Files.exists(cwd.resolve("go.mod"))) return "go";
        if (Files.exists(cwd.resolve("package.json"))) return "npm";
        if (Files.exists(cwd.resolve("tsconfig.json"))) return "tsc";
        if (Files.exists(cwd.resolve("Makefile")) || Files.exists(cwd.resolve("makefile"))) return "make";
        return null;
    }

    static List<String> buildArgv(String builder, Path cwd) {
        List<String> argv = new ArrayList<>();
        switch (builder) {
            case "mvn" -> argv.addAll(List.of("mvn", "-B", "-q", "-DskipTests", "compile"));
            case "gradle" -> {
                Path wrapper = cwd.resolve(isWindows() ? "gradlew.bat" : "gradlew");
                argv.add(Files.isExecutable(wrapper) ? wrapper.toString() : "gradle");
                argv.addAll(List.of("compileJava", "--console=plain"));
            }
            case "cargo" -> argv.addAll(List.of("cargo", "build"));
            case "go" -> argv.addAll(List.of("go", "build", "./..."));
            case "npm" -> {
                if (Files.exists(cwd.resolve("pnpm-lock.yaml"))) argv.add("pnpm");
                else if (Files.exists(cwd.resolve("yarn.lock"))) argv.add("yarn");
                else argv.add("npm");
                argv.addAll(List.of("run", "build", "--silent"));
            }
            case "tsc" -> argv.addAll(List.of("tsc", "--noEmit"));
            case "make" -> argv.add("make");
            default -> { return null; }
        }
        return argv;
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
        props.put("builder", Map.of("type", "string",
                "description", "Override auto-detection: mvn|gradle|npm|cargo|go|make|tsc."));
        props.put("path", Map.of("type", "string",
                "description", "Project subdirectory (default '.').",
                "default", "."));
        props.put("timeout_sec", Map.of("type", "integer",
                "description", "Max seconds (clamped 5..900, default 300)."));
        schema.put("properties", props);
        return schema;
    }
}
