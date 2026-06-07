package ai.intelliswarm.swarmai.agent.subagent.coding;

import ai.intelliswarm.swarmai.agent.subagent.IsolatedWorkspace;
import ai.intelliswarm.swarmai.agent.subagent.Subagent;
import ai.intelliswarm.swarmai.agent.subagent.SubagentResult;
import ai.intelliswarm.swarmai.agent.subagent.SubagentSpawner;
import ai.intelliswarm.swarmai.agent.subagent.SubagentSpec;
import ai.intelliswarm.swarmai.agent.subagent.SubagentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * {@link SubagentSpawner} that runs a coding-agent CLI (Codex, Claude Code,
 * any custom binary) as a {@link ProcessSubagent}. Abstracts over the CLI's
 * conventions so callers don't reimplement Process management for each tool.
 *
 * <h2>Configuration</h2>
 * <ul>
 *   <li>{@code cliCommand} — the binary to invoke, e.g. {@code "codex"} /
 *       absolute path.</li>
 *   <li>{@code baseArgs} — args that always come first (subcommand, flags).</li>
 *   <li>{@link PromptInjection} — how {@link SubagentSpec#userPrompt()} reaches
 *       the CLI: as a positional arg, on stdin, or not at all.</li>
 *   <li>{@code defaultWorkspaceFactory} — used when a spec doesn't bring its
 *       own. Pass {@link ai.intelliswarm.swarmai.agent.subagent.GitWorktreeWorkspace}::new
 *       (curried over a repo) for skill-creation use cases.</li>
 *   <li>{@code parser} — a {@link SubagentOutputParser} for that CLI's output
 *       conventions.</li>
 *   <li>{@code env} — extra env vars (API keys, model overrides) layered on top
 *       of the JVM's environment.</li>
 *   <li>{@code defaultTimeout} — used when the spec doesn't set one.</li>
 * </ul>
 *
 * <h2>Workspace lifecycle</h2>
 * The spawner creates the workspace, sets it as the process's working
 * directory, and hands it to the {@link ProcessSubagent} so it gets closed
 * when the process exits. If the process fails to start, the workspace is
 * closed eagerly and a failed-subagent stub is returned (preserving the
 * "spawn() never throws" convention).
 */
public class CodingAgentSubagentSpawner implements SubagentSpawner {

    private static final Logger logger = LoggerFactory.getLogger(CodingAgentSubagentSpawner.class);

    /** Default per-spec timeout when the spec leaves it unset. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    private final String cliCommand;
    private final List<String> baseArgs;
    private final PromptInjection injection;
    private final ExecutorService executor;
    private final Supplier<IsolatedWorkspace> defaultWorkspaceFactory;
    private final SubagentOutputParser parser;
    private final Map<String, String> env;
    private final Duration defaultTimeout;
    private final boolean inheritParentEnv;

    private CodingAgentSubagentSpawner(Builder b) {
        this.cliCommand = b.cliCommand;
        this.baseArgs = List.copyOf(b.baseArgs);
        this.injection = b.injection;
        this.executor = b.executor;
        this.defaultWorkspaceFactory = b.defaultWorkspaceFactory;
        this.parser = b.parser;
        this.env = Collections.unmodifiableMap(new LinkedHashMap<>(b.env));
        this.defaultTimeout = b.defaultTimeout;
        this.inheritParentEnv = b.inheritParentEnv;
    }

    @Override
    public Subagent spawn(SubagentSpec spec) {
        Objects.requireNonNull(spec, "spec");

        IsolatedWorkspace workspace = createWorkspace(spec);
        Duration timeout = (spec.timeout() != null && !spec.timeout().isZero())
                ? spec.timeout()
                : defaultTimeout;

        Process process;
        try {
            process = startProcess(spec, workspace);
        } catch (IOException e) {
            workspace.close();
            return failedStub(spec.id(), "failed to start " + cliCommand + ": " + e.getMessage());
        }

        // Always close stdin before handing off to ProcessSubagent — this gives
        // the child an empty stdin (EOF on first read) for ARG/NONE injection,
        // and the user prompt followed by EOF for STDIN injection. Using a
        // closed PIPE rather than a redirect-from-file keeps this cross-platform
        // (no /dev/null on Windows; no NUL on Linux).
        try (OutputStream out = process.getOutputStream()) {
            if (injection == PromptInjection.STDIN) {
                out.write(spec.userPrompt().getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            // ARG / NONE: just let try-with-resources close stdin → child sees EOF.
        } catch (IOException e) {
            process.destroyForcibly();
            workspace.close();
            return failedStub(spec.id(),
                    (injection == PromptInjection.STDIN ? "failed to write prompt to stdin: " : "failed to close stdin: ")
                            + e.getMessage());
        }

        return new ProcessSubagent(spec.id(), process, workspace, timeout, executor, parser);
    }

    private IsolatedWorkspace createWorkspace(SubagentSpec spec) {
        Supplier<IsolatedWorkspace> factory = (spec.workspaceFactory() != null)
                ? spec.workspaceFactory()
                : defaultWorkspaceFactory;
        if (factory == null) {
            throw new IllegalStateException(
                    "no workspace factory: spec didn't supply one and the spawner has no default");
        }
        return factory.get();
    }

    private Process startProcess(SubagentSpec spec, IsolatedWorkspace workspace) throws IOException {
        List<String> command = new ArrayList<>(baseArgs.size() + 2);
        command.add(cliCommand);
        command.addAll(baseArgs);
        if (injection == PromptInjection.ARG) {
            command.add(spec.userPrompt());
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workspace.root().toFile());

        if (!inheritParentEnv) {
            pb.environment().clear();
        }
        pb.environment().putAll(env);

        // Always pipe stdin — caller closes it after start (and writes the
        // prompt first for STDIN injection). Avoids platform-specific paths
        // like /dev/null vs NUL.
        pb.redirectInput(ProcessBuilder.Redirect.PIPE);
        return pb.start();
    }

    private Subagent failedStub(String id, String message) {
        logger.warn("spawn failed for {}: {}", id, message);
        SubagentResult result = SubagentResult.failure(id, message, Map.of());
        return new Subagent() {
            @Override public String id() { return id; }
            @Override public SubagentStatus status() { return SubagentStatus.FAILED; }
            @Override public SubagentResult awaitResult(Duration cap) { return result; }
            @Override public void cancel() { /* already terminal */ }
        };
    }

    public String cliCommand() { return cliCommand; }
    public List<String> baseArgs() { return baseArgs; }
    public PromptInjection promptInjection() { return injection; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String cliCommand;
        private List<String> baseArgs = List.of();
        private PromptInjection injection = PromptInjection.ARG;
        private ExecutorService executor;
        private Supplier<IsolatedWorkspace> defaultWorkspaceFactory;
        private SubagentOutputParser parser;
        private Map<String, String> env = new LinkedHashMap<>();
        private Duration defaultTimeout = DEFAULT_TIMEOUT;
        private boolean inheritParentEnv = true;

        Builder() {}

        public Builder cliCommand(String cliCommand) {
            this.cliCommand = cliCommand;
            return this;
        }

        public Builder baseArgs(List<String> args) {
            this.baseArgs = args;
            return this;
        }

        public Builder baseArgs(String... args) {
            this.baseArgs = List.of(args);
            return this;
        }

        public Builder promptInjection(PromptInjection injection) {
            this.injection = injection;
            return this;
        }

        public Builder executor(ExecutorService executor) {
            this.executor = executor;
            return this;
        }

        public Builder defaultWorkspaceFactory(Supplier<IsolatedWorkspace> factory) {
            this.defaultWorkspaceFactory = factory;
            return this;
        }

        public Builder outputParser(SubagentOutputParser parser) {
            this.parser = parser;
            return this;
        }

        public Builder env(String key, String value) {
            this.env.put(Objects.requireNonNull(key, "key"),
                    Objects.requireNonNull(value, "value"));
            return this;
        }

        public Builder env(Map<String, String> map) {
            map.forEach(this::env);
            return this;
        }

        public Builder defaultTimeout(Duration t) {
            this.defaultTimeout = Objects.requireNonNull(t, "defaultTimeout");
            return this;
        }

        /** When false, the spawned CLI gets ONLY the env vars set via {@link #env}. */
        public Builder inheritParentEnv(boolean inherit) {
            this.inheritParentEnv = inherit;
            return this;
        }

        public CodingAgentSubagentSpawner build() {
            Objects.requireNonNull(cliCommand, "cliCommand");
            if (cliCommand.isBlank()) throw new IllegalArgumentException("cliCommand must not be blank");
            Objects.requireNonNull(executor, "executor");
            Objects.requireNonNull(parser, "outputParser");
            Objects.requireNonNull(injection, "injection");
            // defaultWorkspaceFactory may be null — but only if every spec brings its own
            return new CodingAgentSubagentSpawner(this);
        }
    }
}
