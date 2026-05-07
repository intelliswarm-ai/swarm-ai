package ai.intelliswarm.swarmai.agent.subagent;

/**
 * Factory for spawning {@link Subagent}s. Singleton-friendly — a single
 * instance handles many subagents over the application's lifetime.
 *
 * <p>Implementations decide:
 * <ul>
 *   <li>Where the subagent runs — same JVM (in-process), separate process,
 *       remote (future).</li>
 *   <li>How tool calls are wired — by name from the Spring AI registry,
 *       custom callbacks, MCP, etc.</li>
 *   <li>Workspace materialisation — when the spec has a
 *       {@link SubagentSpec#workspaceFactory}, the spawner is responsible for
 *       creating the workspace before start and reaping it after terminate.</li>
 * </ul>
 */
public interface SubagentSpawner {

    /**
     * Spawn a subagent from {@code spec}. The returned handle is in
     * {@link SubagentStatus#PENDING} or {@link SubagentStatus#RUNNING}; the
     * caller awaits a terminal status via {@link Subagent#awaitResult}.
     */
    Subagent spawn(SubagentSpec spec);
}
