package ai.intelliswarm.swarmai.tool.code.git;

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
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Read-only git operations. Exposes a whitelist of safe subcommands the agent
 * needs to understand a repository ({@code status}, {@code diff},
 * {@code log}, {@code branch}, {@code blame}, {@code show}, {@code ls-files})
 * and refuses everything else. Write operations (commit, push, reset, checkout
 * mutating the worktree) are intentionally NOT supported here — those are
 * better surfaced through a separate, explicitly-DANGEROUS tool so reviewers
 * can keep the safe/unsafe split visible at the permission layer.
 *
 * <p>Output is shell-escaped before being passed to {@code git} — the
 * {@code args} parameter is split on whitespace and individual tokens are
 * passed via {@link ProcessBuilder}, so there is no string interpolation into
 * a shell.
 *
 * @since 1.0.25
 */
@PublicApi(since = "1.0.25")
@Component
public class GitTool implements BaseTool {

    private static final Set<String> ALLOWED_SUBCOMMANDS = Set.of(
            "status", "diff", "log", "branch", "blame", "show",
            "ls-files", "rev-parse", "remote", "tag", "describe");

    /** Reject argv tokens that try to escape into shell features. */
    private static final List<String> FORBIDDEN_TOKEN_PARTS = List.of(
            ";", "&&", "||", "|", "`", "$(", ">", "<");

    private static final long DEFAULT_TIMEOUT_SEC = 30;
    private static final int MAX_OUTPUT_CHARS = 50_000;

    private final Path projectRoot;

    /** No-arg ctor for Spring auto-wiring — uses the JVM's CWD. */
    public GitTool() { this(null); }

    public GitTool(Path projectRoot) {
        this.projectRoot = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
    }

    @Override public String getFunctionName() { return "git"; }

    @Override
    public String getDescription() {
        return "Run a read-only git subcommand (status, diff, log, branch, blame, show, ls-files, "
             + "rev-parse, remote, tag, describe). Use this to inspect repo state before editing — "
             + "diffs reveal in-progress work, log gives history context, blame attributes specific lines.";
    }

    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.READ_ONLY; }
    @Override public String getCategory() { return "code-git"; }
    @Override public List<String> getTags() { return List.of("git", "vcs", "history"); }
    @Override public boolean isAsync() { return false; }
    @Override public int getMaxResponseLength() { return MAX_OUTPUT_CHARS; }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String subcommand = (String) parameters.get("subcommand");
        if (subcommand == null || subcommand.isBlank()) {
            return "Error: 'subcommand' is required (one of: " + ALLOWED_SUBCOMMANDS + ")";
        }
        subcommand = subcommand.trim().toLowerCase();
        if (!ALLOWED_SUBCOMMANDS.contains(subcommand)) {
            return "Error: subcommand '" + subcommand + "' is not allowed. "
                 + "Allowed: " + ALLOWED_SUBCOMMANDS;
        }

        String argsString = (String) parameters.getOrDefault("args", "");
        List<String> argv = new ArrayList<>();
        argv.add("git");
        argv.add(subcommand);

        if (argsString != null && !argsString.isBlank()) {
            for (String token : argsString.trim().split("\\s+")) {
                if (token.isEmpty()) continue;
                for (String bad : FORBIDDEN_TOKEN_PARTS) {
                    if (token.contains(bad)) {
                        return "Error: argument token '" + token + "' contains forbidden shell metacharacter '" + bad + "'";
                    }
                }
                argv.add(token);
            }
        }

        Path cwd = resolveWorkingDir(parameters);
        if (cwd == null) return "Error: refusing to run outside project root";
        if (!Files.isDirectory(cwd)) return "Error: not a directory: " + cwd;
        if (!Files.isDirectory(cwd.resolve(".git")) && !insideGitRepo(cwd)) {
            return "Error: " + cwd + " is not inside a git repository";
        }

        long timeoutSec = parameters.get("timeout_sec") instanceof Number n
                ? Math.max(1, Math.min(120, n.intValue())) : DEFAULT_TIMEOUT_SEC;

        try {
            ProcessBuilder pb = new ProcessBuilder(argv)
                    .directory(cwd.toFile())
                    .redirectErrorStream(true);
            // Disable git's interactive prompts (credential helpers, etc.) so a
            // private repo lookup can't hang the tool.
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            pb.environment().put("GIT_OPTIONAL_LOCKS", "0");

            Process p = pb.start();
            boolean finished = p.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return "Error: git " + subcommand + " timed out after " + timeoutSec + "s";
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = p.exitValue();

            if (out.length() > MAX_OUTPUT_CHARS) {
                out = out.substring(0, MAX_OUTPUT_CHARS)
                     + "\n... [truncated; " + (out.length() - MAX_OUTPUT_CHARS) + " more chars]";
            }
            return "git " + subcommand + (exit == 0 ? "" : " (exit=" + exit + ")") + ":\n" + out;
        } catch (IOException e) {
            return "Error running git: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: git interrupted";
        }
    }

    private Path resolveWorkingDir(Map<String, Object> parameters) {
        String relPath = (String) parameters.getOrDefault("path", ".");
        Path root = projectRoot != null ? projectRoot : Path.of(".").toAbsolutePath().normalize();
        Path resolved = root.resolve(relPath).normalize();
        if (!resolved.startsWith(root)) return null;
        return resolved;
    }

    /** Walks parents looking for a {@code .git} dir/file — supports work-trees and submodules. */
    private boolean insideGitRepo(Path start) {
        for (Path cur = start; cur != null; cur = cur.getParent()) {
            if (Files.exists(cur.resolve(".git"))) return true;
        }
        return false;
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("subcommand", Map.of(
                "type", "string",
                "description", "Git subcommand to run. Allowed: " + ALLOWED_SUBCOMMANDS,
                "enum", new ArrayList<>(ALLOWED_SUBCOMMANDS)));
        props.put("args", Map.of(
                "type", "string",
                "description", "Additional whitespace-separated arguments, e.g. '--oneline -n 20' or 'HEAD~3'."));
        props.put("path", Map.of(
                "type", "string",
                "description", "Subdirectory of the project to run from (default '.').",
                "default", "."));
        props.put("timeout_sec", Map.of(
                "type", "integer",
                "description", "Max seconds to wait (clamped to 1..120; default 30)."));
        schema.put("properties", props);
        schema.put("required", new String[]{"subcommand"});
        return schema;
    }
}
