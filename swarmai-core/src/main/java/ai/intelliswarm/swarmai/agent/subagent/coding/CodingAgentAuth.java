package ai.intelliswarm.swarmai.agent.subagent.coding;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Helpers for wiring authentication to a coding-agent CLI (Codex, Claude Code).
 *
 * <h2>Auth flows by provider</h2>
 * <ul>
 *   <li><b>Codex</b> — reads {@code OPENAI_API_KEY} env, or a cached login at
 *       {@code ~/.codex/auth.json} produced by {@code codex login}. The home
 *       directory can be relocated via {@code CODEX_HOME}.</li>
 *   <li><b>Claude Code</b> — reads {@code ANTHROPIC_API_KEY} env, or an OAuth
 *       credential at {@code ~/.claude/.credentials.json} produced by
 *       {@code claude login}. The home directory can be relocated via
 *       {@code CLAUDE_CONFIG_DIR}.</li>
 * </ul>
 *
 * <h2>Three deployment patterns</h2>
 * <ol>
 *   <li><b>Host execution</b> — the spawner runs the CLI as a child process
 *       on the host. With {@code inheritParentEnv=true} (default), the
 *       parent shell's env (including {@code OPENAI_API_KEY} and
 *       {@code HOME}) flows through automatically. If the user has done
 *       {@code codex login}, "it just works."</li>
 *   <li><b>Custom env</b> — if the caller pinned a specific env map and
 *       disabled inheritance, use
 *       {@link #codexEnvFromSystem()} / {@link #claudeEnvFromSystem()} to
 *       cherry-pick the auth-relevant env vars from the parent.</li>
 *       <li><b>Docker workspace</b> — the container has no host {@code HOME}.
 *           Either pass the API key via env (works), or bind-mount the auth
 *           directory: see {@link #codexHome()} / {@link #claudeHome()} and
 *           {@code DockerWorkspace.Builder.bindMount(host, container, readOnly=true)}.</li>
 * </ol>
 *
 * <h2>Fail-fast at startup</h2>
 * Call {@link #requireCodexAuth()} or {@link #requireClaudeAuth()} when
 * wiring the spawner — throws {@link IllegalStateException} with a
 * remediation hint if no usable credential is detected. Better than letting
 * the first skill-generation attempt 30 minutes later fail with a cryptic
 * non-zero exit.
 */
public final class CodingAgentAuth {

    private CodingAgentAuth() {}

    /** Path to the codex CLI's per-user state directory. */
    public static Path codexHome() {
        String env = System.getenv("CODEX_HOME");
        if (env != null && !env.isBlank()) return Path.of(env);
        return Path.of(System.getProperty("user.home"), ".codex");
    }

    /** Path to the Claude Code CLI's per-user state directory. */
    public static Path claudeHome() {
        String env = System.getenv("CLAUDE_CONFIG_DIR");
        if (env != null && !env.isBlank()) return Path.of(env);
        return Path.of(System.getProperty("user.home"), ".claude");
    }

    /** True if {@code codex login} has produced a cached credential on this host. */
    public static boolean codexHasCachedLogin() {
        return Files.exists(codexHome().resolve("auth.json"));
    }

    /** True if {@code claude login} has produced a cached credential on this host. */
    public static boolean claudeHasCachedLogin() {
        return Files.exists(claudeHome().resolve(".credentials.json"));
    }

    /**
     * True if the host has <em>any</em> usable codex credential —
     * cached login or {@code OPENAI_API_KEY} in the environment.
     */
    public static boolean hasCodexAuth() {
        return codexHasCachedLogin() || hasNonBlankEnv("OPENAI_API_KEY");
    }

    /**
     * True if the host has <em>any</em> usable Claude Code credential —
     * cached login or {@code ANTHROPIC_API_KEY} in the environment.
     */
    public static boolean hasClaudeAuth() {
        return claudeHasCachedLogin() || hasNonBlankEnv("ANTHROPIC_API_KEY");
    }

    /**
     * Auth-relevant env vars to pass to the codex CLI. Only includes keys
     * that are set and non-blank in the parent process. Empty map if
     * nothing is set — the caller can safely merge this into a builder.
     */
    public static Map<String, String> codexEnvFromSystem() {
        return pickEnv("OPENAI_API_KEY", "OPENAI_BASE_URL", "OPENAI_ORG_ID", "CODEX_HOME");
    }

    /**
     * Auth-relevant env vars to pass to the Claude Code CLI. Only includes
     * keys that are set and non-blank in the parent process.
     */
    public static Map<String, String> claudeEnvFromSystem() {
        return pickEnv("ANTHROPIC_API_KEY", "ANTHROPIC_BASE_URL",
                "CLAUDE_CONFIG_DIR", "CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_VERTEX");
    }

    /**
     * Throw if no codex credential is detected. Message includes the two
     * remediation paths so the user knows what to do.
     */
    public static void requireCodexAuth() {
        if (!hasCodexAuth()) {
            throw new IllegalStateException(
                    "Codex CLI is not authenticated. Either run `codex login` "
                  + "(creates " + codexHome().resolve("auth.json") + ") "
                  + "or set the OPENAI_API_KEY environment variable.");
        }
    }

    /** Throw if no Claude Code credential is detected. */
    public static void requireClaudeAuth() {
        if (!hasClaudeAuth()) {
            throw new IllegalStateException(
                    "Claude Code CLI is not authenticated. Either run `claude login` "
                  + "(creates " + claudeHome().resolve(".credentials.json") + ") "
                  + "or set the ANTHROPIC_API_KEY environment variable.");
        }
    }

    private static boolean hasNonBlankEnv(String key) {
        String v = System.getenv(key);
        return v != null && !v.isBlank();
    }

    private static Map<String, String> pickEnv(String... keys) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : keys) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) out.put(key, value);
        }
        return out;
    }
}
