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
import java.util.regex.Pattern;

/**
 * List / inspect a project's third-party dependencies. Auto-detects the
 * package manager from on-disk markers and dispatches to its CLI.
 *
 * <p>Supported operations:
 * <ul>
 *   <li>{@code list} (default) — flat list of resolved deps</li>
 *   <li>{@code tree} — hierarchical dep graph</li>
 *   <li>{@code why} — show why a specific package is pulled in
 *       (requires {@code target})</li>
 * </ul>
 *
 * @since 1.0.25
 */
@PublicApi(since = "1.0.25")
@Component
public class DependencyTool implements BaseTool {

    private static final long DEFAULT_TIMEOUT_SEC = 180;
    private static final int MAX_OUTPUT_CHARS = 50_000;
    private static final Pattern PACKAGE_NAME = Pattern.compile("[A-Za-z0-9_.@/\\-]+");

    private final Path projectRoot;

    public DependencyTool() { this(null); }

    public DependencyTool(Path projectRoot) {
        this.projectRoot = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
    }

    @Override public String getFunctionName() { return "dependencies"; }

    @Override
    public String getDescription() {
        return "List or inspect the project's third-party dependencies. "
             + "Auto-detects mvn/gradle/npm/yarn/pnpm/pip/poetry/cargo/go. "
             + "operation=list (default) | tree | why (requires target=<pkg>).";
    }

    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.READ_ONLY; }
    @Override public String getCategory() { return "code-build"; }
    @Override public List<String> getTags() { return List.of("deps", "dependencies", "packages"); }
    @Override public boolean isAsync() { return false; }
    @Override public int getMaxResponseLength() { return MAX_OUTPUT_CHARS; }

    @Override
    public Object execute(Map<String, Object> parameters) {
        Path cwd = resolveWorkingDir((String) parameters.getOrDefault("path", "."));
        if (cwd == null) return "Error: refusing to run outside project root";
        if (!Files.isDirectory(cwd)) return "Error: not a directory: " + cwd;

        String manager = ((String) parameters.getOrDefault("manager", "")).trim().toLowerCase();
        if (manager.isEmpty()) {
            manager = detectManager(cwd);
            if (manager == null) {
                return "Error: could not auto-detect a package manager in " + cwd
                     + ". Pass 'manager' (mvn|gradle|npm|yarn|pnpm|pip|poetry|cargo|go).";
            }
        }

        String operation = ((String) parameters.getOrDefault("operation", "list")).trim().toLowerCase();
        String target = (String) parameters.get("target");

        if ("why".equals(operation) && (target == null || target.isBlank())) {
            return "Error: operation=why requires 'target' (package name to trace).";
        }
        if (target != null && !target.isBlank() && !PACKAGE_NAME.matcher(target).matches()) {
            return "Error: 'target' must look like a package name, got: '" + target + "'";
        }

        List<String> argv = buildArgv(manager, operation, target);
        if (argv == null) {
            return "Error: operation '" + operation + "' not supported for '" + manager + "'";
        }

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
                return "Error: " + manager + " " + operation + " timed out after " + timeoutSec + "s";
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = p.exitValue();
            if (out.length() > MAX_OUTPUT_CHARS) {
                out = out.substring(0, MAX_OUTPUT_CHARS)
                    + "\n... [truncated " + (out.length() - MAX_OUTPUT_CHARS) + " more chars]";
            }
            return manager + " " + operation
                 + (target != null && !target.isBlank() ? " " + target : "")
                 + (exit == 0 ? "" : " (exit=" + exit + ")") + ":\n" + out;
        } catch (IOException e) {
            return "Error launching " + argv.get(0) + ": " + e.getMessage()
                 + " — is it installed and on PATH?";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: dependency lookup interrupted";
        }
    }

    static String detectManager(Path cwd) {
        if (Files.exists(cwd.resolve("pom.xml"))) return "mvn";
        if (Files.exists(cwd.resolve("build.gradle")) || Files.exists(cwd.resolve("build.gradle.kts"))) return "gradle";
        if (Files.exists(cwd.resolve("pnpm-lock.yaml"))) return "pnpm";
        if (Files.exists(cwd.resolve("yarn.lock"))) return "yarn";
        if (Files.exists(cwd.resolve("package.json"))) return "npm";
        if (Files.exists(cwd.resolve("Cargo.toml"))) return "cargo";
        if (Files.exists(cwd.resolve("go.mod"))) return "go";
        if (Files.exists(cwd.resolve("poetry.lock")) || Files.exists(cwd.resolve("pyproject.toml"))) return "poetry";
        if (Files.exists(cwd.resolve("requirements.txt"))
            || Files.exists(cwd.resolve("setup.py"))
            || Files.exists(cwd.resolve("Pipfile"))) return "pip";
        return null;
    }

    static List<String> buildArgv(String manager, String operation, String target) {
        List<String> argv = new ArrayList<>();
        switch (manager) {
            case "mvn" -> {
                argv.add("mvn"); argv.add("-B"); argv.add("-q");
                switch (operation) {
                    case "list" -> argv.add("dependency:list");
                    case "tree" -> argv.add("dependency:tree");
                    case "why" -> {
                        argv.add("dependency:tree");
                        argv.add("-Dincludes=" + target);
                    }
                    default -> { return null; }
                }
            }
            case "gradle" -> {
                argv.add("gradle"); argv.add("--console=plain");
                switch (operation) {
                    case "list", "tree" -> argv.add("dependencies");
                    case "why" -> { argv.add("dependencyInsight"); argv.add("--dependency"); argv.add(target); }
                    default -> { return null; }
                }
            }
            case "npm" -> {
                argv.add("npm");
                switch (operation) {
                    case "list" -> { argv.add("ls"); argv.add("--depth=0"); }
                    case "tree" -> argv.add("ls");
                    case "why" -> { argv.add("explain"); argv.add(target); }
                    default -> { return null; }
                }
            }
            case "yarn" -> {
                argv.add("yarn");
                switch (operation) {
                    case "list" -> argv.add("list");
                    case "tree" -> argv.add("list");
                    case "why" -> { argv.add("why"); argv.add(target); }
                    default -> { return null; }
                }
            }
            case "pnpm" -> {
                argv.add("pnpm");
                switch (operation) {
                    case "list" -> { argv.add("list"); argv.add("--depth=0"); }
                    case "tree" -> argv.add("list");
                    case "why" -> { argv.add("why"); argv.add(target); }
                    default -> { return null; }
                }
            }
            case "cargo" -> {
                argv.add("cargo");
                switch (operation) {
                    case "list", "tree" -> argv.add("tree");
                    case "why" -> { argv.add("tree"); argv.add("-i"); argv.add(target); }
                    default -> { return null; }
                }
            }
            case "go" -> {
                argv.add("go"); argv.add("list");
                switch (operation) {
                    case "list" -> { argv.add("-m"); argv.add("all"); }
                    case "tree" -> { argv.add("-m"); argv.add("all"); }
                    case "why" -> { argv = new ArrayList<>(List.of("go", "mod", "why", target)); }
                    default -> { return null; }
                }
            }
            case "pip" -> {
                argv.add("pip");
                switch (operation) {
                    case "list" -> argv.add("list");
                    case "tree" -> { argv = new ArrayList<>(List.of("pipdeptree")); }
                    case "why" -> { argv.add("show"); argv.add(target); }
                    default -> { return null; }
                }
            }
            case "poetry" -> {
                argv.add("poetry");
                switch (operation) {
                    case "list" -> argv.add("show");
                    case "tree" -> { argv.add("show"); argv.add("--tree"); }
                    case "why" -> { argv.add("show"); argv.add(target); }
                    default -> { return null; }
                }
            }
            default -> { return null; }
        }
        return argv;
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
        props.put("operation", Map.of("type", "string",
                "description", "list (default) | tree | why",
                "default", "list"));
        props.put("target", Map.of("type", "string",
                "description", "Package name for 'why' (or extra arg for list/tree)."));
        props.put("manager", Map.of("type", "string",
                "description", "Override auto-detection: mvn|gradle|npm|yarn|pnpm|pip|poetry|cargo|go."));
        props.put("path", Map.of("type", "string", "default", ".",
                "description", "Project subdirectory (default '.')."));
        props.put("timeout_sec", Map.of("type", "integer",
                "description", "Max seconds (clamped 5..600, default 180)."));
        schema.put("properties", props);
        return schema;
    }
}
