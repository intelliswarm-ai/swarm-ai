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
 * Run a code formatter, auto-detecting the right tool by config / project type.
 *
 * <p>Default mode is <b>check</b> — the formatter runs in dry-run / diff mode
 * and the tool reports whether files would change but does not write. Pass
 * {@code write=true} to apply formatting in place.
 *
 * <p>Detection:
 * <ul>
 *   <li>{@code .prettierrc*} / {@code prettier.config.*} → {@code prettier}</li>
 *   <li>{@code pyproject.toml} with [tool.black] / {@code .black.toml} → {@code black}</li>
 *   <li>{@code pyproject.toml} with [tool.ruff.format] → {@code ruff format}</li>
 *   <li>{@code go.mod} → {@code gofmt}</li>
 *   <li>{@code Cargo.toml} → {@code cargo fmt}</li>
 *   <li>{@code .rubocop.yml} → {@code rubocop --autocorrect}</li>
 *   <li>{@code pom.xml} → {@code mvn spotless:apply} (or spotless:check)</li>
 * </ul>
 *
 * @since 1.0.26
 */
@PublicApi(since = "1.0.26")
@Component
public class FormatterTool implements BaseTool {

    private static final long DEFAULT_TIMEOUT_SEC = 180;
    private static final int MAX_OUTPUT_CHARS = 50_000;

    private final Path projectRoot;

    public FormatterTool() { this(null); }

    public FormatterTool(Path projectRoot) {
        this.projectRoot = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
    }

    @Override public String getFunctionName() { return "format"; }

    @Override
    public String getDescription() {
        return "Run a code formatter (auto-detects prettier/black/ruff-format/gofmt/cargo-fmt/"
             + "rubocop/spotless). Default mode is CHECK (no writes); pass write=true to apply formatting "
             + "in place. Returns exit code + truncated output.";
    }

    @Override
    public PermissionLevel getPermissionLevel() {
        // We default to check-only (no writes), but the agent can opt-in to write.
        // Conservatively report WORKSPACE_WRITE so policy systems know writes are possible.
        return PermissionLevel.WORKSPACE_WRITE;
    }

    @Override public String getCategory() { return "code-build"; }
    @Override public List<String> getTags() { return List.of("format", "style"); }
    @Override public boolean isAsync() { return false; }
    @Override public int getMaxResponseLength() { return MAX_OUTPUT_CHARS; }

    @Override
    public Object execute(Map<String, Object> parameters) {
        Path cwd = resolveWorkingDir((String) parameters.getOrDefault("path", "."));
        if (cwd == null) return "Error: refusing to run outside project root";
        if (!Files.isDirectory(cwd)) return "Error: not a directory: " + cwd;

        String formatter = ((String) parameters.getOrDefault("formatter", "")).trim().toLowerCase();
        if (formatter.isEmpty()) {
            formatter = detectFormatter(cwd);
            if (formatter == null) {
                return "Error: could not auto-detect a formatter in " + cwd
                     + ". Pass 'formatter' (prettier|black|ruff|gofmt|cargo-fmt|rubocop|spotless).";
            }
        }
        boolean write = Boolean.TRUE.equals(parameters.get("write"));
        String target = (String) parameters.get("target");

        List<String> argv = buildArgv(formatter, write, target, cwd);
        if (argv == null) return "Error: unsupported formatter '" + formatter + "'";

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
                return "Error: " + formatter + " timed out after " + timeoutSec + "s";
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = p.exitValue();
            if (out.length() > MAX_OUTPUT_CHARS) {
                out = out.substring(0, MAX_OUTPUT_CHARS)
                    + "\n... [truncated " + (out.length() - MAX_OUTPUT_CHARS) + " more chars]";
            }
            return formatter + " (" + (write ? "WRITE" : "CHECK") + ", "
                 + (exit == 0 ? "PASS" : "FAIL exit=" + exit) + "):\n" + out;
        } catch (IOException e) {
            return "Error launching " + argv.get(0) + ": " + e.getMessage()
                 + " — is it installed and on PATH?";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: format interrupted";
        }
    }

    static String detectFormatter(Path cwd) {
        if (hasPrettierConfig(cwd)) return "prettier";
        if (hasPyprojectSection(cwd, "[tool.ruff.format")) return "ruff";
        if (hasPyprojectSection(cwd, "[tool.black") || Files.exists(cwd.resolve(".black.toml"))) return "black";
        if (Files.exists(cwd.resolve("go.mod"))) return "gofmt";
        if (Files.exists(cwd.resolve("Cargo.toml"))) return "cargo-fmt";
        if (Files.exists(cwd.resolve(".rubocop.yml"))) return "rubocop";
        if (Files.exists(cwd.resolve("pom.xml"))) return "spotless";
        return null;
    }

    static List<String> buildArgv(String formatter, boolean write, String target, Path cwd) {
        String tgt = (target != null && !target.isBlank()) ? target : ".";
        List<String> argv = new ArrayList<>();
        switch (formatter) {
            case "prettier" -> {
                argv.add("prettier");
                argv.add(write ? "--write" : "--check");
                argv.add(tgt);
            }
            case "black" -> {
                argv.add("black");
                if (!write) argv.add("--check");
                argv.add(tgt);
            }
            case "ruff" -> {
                argv.addAll(List.of("ruff", "format"));
                if (!write) argv.add("--check");
                argv.add(tgt);
            }
            case "gofmt" -> {
                argv.add("gofmt");
                argv.add(write ? "-w" : "-l");
                argv.add(tgt);
            }
            case "cargo-fmt" -> {
                argv.addAll(List.of("cargo", "fmt"));
                if (!write) argv.add("--check");
            }
            case "rubocop" -> {
                argv.add("rubocop");
                if (write) argv.add("--autocorrect");
                argv.add(tgt);
            }
            case "spotless" -> {
                argv.addAll(List.of("mvn", "-B", "-q"));
                argv.add(write ? "spotless:apply" : "spotless:check");
            }
            default -> { return null; }
        }
        return argv;
    }

    // --- detection helpers ---

    private static boolean hasPrettierConfig(Path cwd) {
        for (String name : List.of(
                ".prettierrc", ".prettierrc.js", ".prettierrc.cjs", ".prettierrc.json",
                ".prettierrc.yml", ".prettierrc.yaml", ".prettierrc.toml",
                "prettier.config.js", "prettier.config.cjs", "prettier.config.mjs")) {
            if (Files.exists(cwd.resolve(name))) return true;
        }
        return false;
    }

    private static boolean hasPyprojectSection(Path cwd, String marker) {
        Path p = cwd.resolve("pyproject.toml");
        if (!Files.isRegularFile(p)) return false;
        try {
            return Files.readString(p, StandardCharsets.UTF_8).contains(marker);
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
        props.put("formatter", Map.of("type", "string",
                "description", "Override auto-detection: prettier|black|ruff|gofmt|cargo-fmt|rubocop|spotless."));
        props.put("write", Map.of("type", "boolean", "default", false,
                "description", "Apply formatting in place (default false = check mode, no writes)."));
        props.put("target", Map.of("type", "string",
                "description", "Path/glob to format (default '.')."));
        props.put("path", Map.of("type", "string", "default", ".",
                "description", "Project subdirectory."));
        props.put("timeout_sec", Map.of("type", "integer",
                "description", "Max seconds (clamped 5..600, default 180)."));
        schema.put("properties", props);
        return schema;
    }
}
