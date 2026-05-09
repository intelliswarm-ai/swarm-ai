package ai.intelliswarm.swarmai.agent.subagent.coding;

import ai.intelliswarm.swarmai.agent.subagent.SubagentResult;

/**
 * Maps a coding-agent CLI's {@link ProcessOutput} into a {@link SubagentResult}.
 *
 * <p>Each CLI has its own conventions:
 * <ul>
 *   <li><b>Codex</b> — stdout is the agent's reply, stderr is logs;
 *       non-zero exit = failure. {@code CodexSubagentSpawner.parser()}.</li>
 *   <li><b>Claude Code</b> — stdout is JSON event stream when run with
 *       {@code --output-format=stream-json}; needs structured parsing to
 *       extract the final assistant message.</li>
 * </ul>
 *
 * <p>Implementations are typically static methods used as method references.
 */
@FunctionalInterface
public interface SubagentOutputParser {

    /**
     * Build a result from the captured output. Implementations should never
     * throw — they should return {@link SubagentResult#failure} for malformed
     * output rather than propagate.
     *
     * @param subagentId  the spec's id; used as the result's id
     * @param output      captured stdout/stderr/exit code/elapsed
     */
    SubagentResult parse(String subagentId, ProcessOutput output);
}
