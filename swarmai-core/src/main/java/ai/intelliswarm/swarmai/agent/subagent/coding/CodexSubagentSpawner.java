package ai.intelliswarm.swarmai.agent.subagent.coding;

import ai.intelliswarm.swarmai.agent.subagent.IsolatedWorkspace;
import ai.intelliswarm.swarmai.agent.subagent.SubagentResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * Factory for a {@link CodingAgentSubagentSpawner} pre-configured for the
 * Codex CLI. Defaults reflect the typical "non-interactive single-task"
 * mode of operation; override via {@link #builder()} if you need different
 * flags, env vars, or output parsing.
 *
 * <p>The Codex CLI is expected to:
 * <ol>
 *   <li>Accept a task as a positional argument (we use {@link PromptInjection#ARG}).</li>
 *   <li>Write the agent's reply to stdout.</li>
 *   <li>Write progress / error messages to stderr.</li>
 *   <li>Exit 0 on success, non-zero on failure.</li>
 * </ol>
 *
 * <p>Default base args are {@code ["exec", "--skip-git-repo-check", "--full-auto"]} —
 * suitable for sandboxed runs where the harness is responsible for git management
 * and approvals are not interactive. Override via {@link #builder()} when these
 * defaults don't match your Codex version.
 */
public final class CodexSubagentSpawner {

    /** Default CLI binary name. */
    public static final String DEFAULT_CLI = "codex";

    /**
     * Default command-line flags for non-interactive sandboxed execution.
     *
     * <p>{@code --sandbox workspace-write} replaces the older
     * {@code --full-auto} flag (deprecated since codex 0.130.0). It tells
     * codex it has read+write access to the cwd (our workspace) and read
     * access to the rest of the system; sufficient for "write SKILL.md
     * here, exit 0" workflows without granting network or arbitrary fs.
     */
    public static final List<String> DEFAULT_ARGS = List.of(
            "exec",
            "--skip-git-repo-check",
            "--sandbox", "workspace-write"
    );

    private CodexSubagentSpawner() {}

    /**
     * Build a spawner with the {@link #DEFAULT_CLI} command, {@link #DEFAULT_ARGS},
     * and {@link #defaultParser()}. Caller supplies the executor and a workspace
     * factory.
     */
    public static CodingAgentSubagentSpawner create(ExecutorService executor,
                                                     Supplier<IsolatedWorkspace> workspaceFactory) {
        return builder()
                .executor(executor)
                .defaultWorkspaceFactory(workspaceFactory)
                .build();
    }

    /**
     * Like {@link #create(ExecutorService, Supplier)} but fails fast at
     * startup if Codex isn't authenticated, and forwards any auth-relevant
     * env vars from the parent shell to the child process.
     *
     * <p>Use this when you control the deployment and want to surface
     * "you forgot to log in" before the first skill-generation attempt.
     *
     * @throws IllegalStateException if no codex login or {@code OPENAI_API_KEY}
     *         is detected on the host
     */
    public static CodingAgentSubagentSpawner createWithSystemAuth(ExecutorService executor,
                                                                    Supplier<IsolatedWorkspace> workspaceFactory) {
        CodingAgentAuth.requireCodexAuth();
        return builder()
                .executor(executor)
                .defaultWorkspaceFactory(workspaceFactory)
                .env(CodingAgentAuth.codexEnvFromSystem())
                .build();
    }

    /**
     * Pre-populated builder. Returns a {@link CodingAgentSubagentSpawner.Builder}
     * already configured with Codex defaults; caller can override any field
     * before {@code build()}.
     */
    public static CodingAgentSubagentSpawner.Builder builder() {
        return CodingAgentSubagentSpawner.builder()
                .cliCommand(DEFAULT_CLI)
                .baseArgs(DEFAULT_ARGS)
                .promptInjection(PromptInjection.ARG)
                .outputParser(defaultParser());
    }

    /**
     * Default output parser for Codex. Treats stdout as the agent's reply,
     * stderr as logs, exit code 0 as success. Captures bytes-counts and
     * elapsed time as result metrics.
     */
    public static SubagentOutputParser defaultParser() {
        return CodexSubagentSpawner::parse;
    }

    private static SubagentResult parse(String id, ProcessOutput output) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("exitCode", output.exitCode());
        metrics.put("stdoutBytes", output.stdout().length());
        metrics.put("stderrBytes", output.stderr().length());
        metrics.put("elapsedMs", output.elapsed().toMillis());

        if (output.ok()) {
            return SubagentResult.success(id, output.stdout(), metrics);
        }
        // Failure — surface stderr (typically the error message) so the caller
        // can decide whether to retry, escalate, or feed to the bandit.
        String message = "codex exited " + output.exitCode();
        if (!output.stderr().isBlank()) {
            message += ": " + output.stderr().trim();
        }
        return SubagentResult.failure(id, message, metrics);
    }
}
