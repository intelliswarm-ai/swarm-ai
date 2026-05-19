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
 * Run a project's linter, auto-detecting the right tool by config file.
 *
 * <p>Detection order (first hit wins):
 * <ul>
 *   <li>{@code .eslintrc*} / {@code eslint.config.*} → {@code eslint}</li>
 *   <li>{@code ruff.toml} / {@code .ruff.toml} / {@code pyproject.toml}
 *       (when [tool.ruff] block is present) → {@code ruff check}</li>
 *   <li>{@code .flake8} / {@code setup.cfg} → {@code flake8}</li>
 *   <li>{@code .pylintrc} → {@code pylint}</li>
 *   <li>{@code .golangci.y(a)ml} → {@code golangci-lint run}</li>
 *   <li>{@code Cargo.toml} → {@code cargo clippy}</li>
 *   <li>{@code .rubocop.yml} → {@code rubocop}</li>
 *   <li>{@code checkstyle.xml} / {@code pom.xml} (best-effort) →
 *       {@code mvn checkstyle:check}</li>
 * </ul>
 *
 * <p>Use {@code fix=true} to apply auto-fixes when the underlying linter
 * supports them (eslint --fix, ruff --fix, rubocop --autocorrect, ...).
 *
 * @since 1.0.25
 */
@PublicApi(since = "1.0.25")
@Component
public class LintTool implements BaseTool {

    private static final long DEFAULT_TIMEOUT_SEC = 180;
    private static final int MAX_OUTPUT_CHARS = 50_000;

    private final Path projectRoot;

    public LintTool() { this(null); }

    public LintTool(Path projectRoot) {
        this.projectRoot = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
    }

    @Override public String getFunctionName() { return "lint"; }

    @Override
    public String getDescription() {
        return "Run the project's linter (auto-detects eslint/ruff/flake8/pylint/golangci-lint/"
             + "clippy/rubocop/checkstyle from config files). Pass fix=true to apply auto-fixes "
             + "when supported. Returns exit code + truncated output.";
    }

    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.WORKSPACE_WRITE; }
    @Override public String getCategory() { return "code-build"; }
    @Override public List<String> getTags() { return List.of("lint", "quality"); }
    @Override public boolean isAsync() { return false; }
    @Override public int getMaxResponseLength() { return MAX_OUTPUT_CHARS; }

    @Override
    public Object execute(Map<String, Object> parameters) {
        Path cwd = resolveWorkingDir((String) parameters.getOrDefault("path", "."));
        if (cwd == null) return "Error: refusing to run outside project root";
        if (!Files.isDirectory(cwd)) return "Error: not a directory: " + cwd;

        String linter = ((String) parameters.getOrDefault("linter", "")).trim().toLowerCase();
        if (linter.isEmpty()) {
            linter = detectLinter(cwd);
            if (linter == null) {
                return "Error: could not auto-detect a linter in " + cwd
                     + ". Pass 'linter' (eslint|ruff|flake8|pylint|golangci-lint|clippy|rubocop|checkstyle).";
            }
        }
        boolean fix = Boolean.TRUE.equals(parameters.get("fix"));
        String target = (String) parameters.get("target");

        List<String> argv = buildArgv(linter, fix, target);
        if (argv == null) return "Error: unsupported linter '" + linter + "'";

        long timeoutSec = parameters.get("timeout_sec") instanceof Number n
                ? Math.max(5, Math.min(600, n.intValue())) : DEFAULT_TIMEOUT_SEC;

        try {
            Process p = new ProcessBuilder(argv)
                    .directory(cwd.toFile())
                    .redirectErrorStream(true)
                    .start();
            boolean finished = p.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return "Error: " + linter + " timed out after " + timeoutSec + "s";
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = p.exitValue();
            // Linter errors typically appear early in output — keep the head.
            if (out.length() > MAX_OUTPUT_CHARS) {
                out = out.substring(0, MAX_OUTPUT_CHARS)
                    + "\n... [truncated " + (out.length() - MAX_OUTPUT_CHARS) + " more chars]";
            }
            return linter + " (" + (exit == 0 ? "PASS" : "FAIL exit=" + exit) + ")"
                 + (fix ? " [--fix]" : "") + ":\n" + out;
        } catch (IOException e) {
            return "Error launching " + argv.get(0) + ": " + e.getMessage()
                 + " — is it installed and on PATH?";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: lint interrupted";
        }
    }

    static String detectLinter(Path cwd) {
        if (hasEslintConfig(cwd)) return "eslint";
        if (hasRuffConfig(cwd)) return "ruff";
        if (Files.exists(cwd.resolve(".flake8")) || hasSection(cwd.resolve("setup.cfg"), "[flake8]")) return "flake8";
        if (Files.exists(cwd.resolve(".pylintrc"))) return "pylint";
        if (Files.exists(cwd.resolve(".golangci.yml")) || Files.exists(cwd.resolve(".golangci.yaml"))) return "golangci-lint";
        if (Files.exists(cwd.resolve("Cargo.toml"))) return "clippy";
        if (Files.exists(cwd.resolve(".rubocop.yml"))) return "rubocop";
        if (Files.exists(cwd.resolve("checkstyle.xml")) || Files.exists(cwd.resolve("pom.xml"))) return "checkstyle";
        return null;
    }

    static List<String> buildArgv(String linter, boolean fix, String target) {
        List<String> argv = new ArrayList<>();
        switch (linter) {
            case "eslint" -> {
                argv.add("eslint");
                if (fix) argv.add("--fix");
                argv.add(target != null && !target.isBlank() ? target : ".");
            }
            case "ruff" -> {
                argv.addAll(List.of("ruff", "check"));
                if (fix) argv.add("--fix");
                argv.add(target != null && !target.isBlank() ? target : ".");
            }
            case "flake8" -> {
                argv.add("flake8");
                if (target != null && !target.isBlank()) argv.add(target);
            }
            case "pylint" -> {
                argv.add("pylint");
                argv.add(target != null && !target.isBlank() ? target : ".");
            }
            case "golangci-lint" -> {
                argv.addAll(List.of("golangci-lint", "run"));
                if (fix) argv.add("--fix");
                if (target != null && !target.isBlank()) argv.add(target);
            }
            case "clippy" -> {
                argv.addAll(List.of("cargo", "clippy"));
                if (fix) { argv.add("--fix"); argv.add("--allow-dirty"); argv.add("--allow-staged"); }
                argv.add("--"); argv.add("-D"); argv.add("warnings");
            }
            case "rubocop" -> {
                argv.add("rubocop");
                if (fix) argv.add("--autocorrect");
                if (target != null && !target.isBlank()) argv.add(target);
            }
            case "checkstyle" -> argv.addAll(List.of("mvn", "-B", "-q", "checkstyle:check"));
            default -> { return null; }
        }
        return argv;
    }

    // --- detection helpers ---

    private static boolean hasEslintConfig(Path cwd) {
        for (String name : List.of(
                ".eslintrc", ".eslintrc.js", ".eslintrc.cjs", ".eslintrc.mjs",
                ".eslintrc.json", ".eslintrc.yml", ".eslintrc.yaml",
                "eslint.config.js", "eslint.config.cjs", "eslint.config.mjs", "eslint.config.ts")) {
            if (Files.exists(cwd.resolve(name))) return true;
        }
        return false;
    }

    private static boolean hasRuffConfig(Path cwd) {
        if (Files.exists(cwd.resolve("ruff.toml")) || Files.exists(cwd.resolve(".ruff.toml"))) return true;
        return hasSection(cwd.resolve("pyproject.toml"), "[tool.ruff");
    }

    private static boolean hasSection(Path file, String marker) {
        if (!Files.isRegularFile(file)) return false;
        try {
            return Files.readString(file, StandardCharsets.UTF_8).contains(marker);
        } catch (IOException e) {
            return false;
        }
    }

    private Path resolveWorkingDir(String relPath) {
        Path root = projectRoot != null ? projectRoot : Path.of(".").toAbsolutePath().normalize();
        Path resolved = root.resolve(relPath).normalize();
        return resolved.startsWith(root) ? resolved : null;
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("linter", Map.of("type", "string",
                "description", "Override auto-detection: eslint|ruff|flake8|pylint|golangci-lint|clippy|rubocop|checkstyle."));
        props.put("fix", Map.of("type", "boolean", "default", false,
                "description", "Apply auto-fixes when the linter supports it (eslint, ruff, clippy, rubocop)."));
        props.put("target", Map.of("type", "string",
                "description", "Path/glob to lint (default '.')."));
        props.put("path", Map.of("type", "string", "default", ".",
                "description", "Project subdirectory."));
        props.put("timeout_sec", Map.of("type", "integer",
                "description", "Max seconds (clamped 5..600, default 180)."));
        schema.put("properties", props);
        return schema;
    }
}
