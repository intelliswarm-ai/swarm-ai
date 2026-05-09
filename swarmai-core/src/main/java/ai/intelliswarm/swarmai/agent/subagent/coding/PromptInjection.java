package ai.intelliswarm.swarmai.agent.subagent.coding;

/**
 * How {@link CodingAgentSubagentSpawner} feeds the user prompt to the CLI.
 *
 * <ul>
 *   <li>{@link #ARG} — append {@code SubagentSpec.userPrompt()} as the last
 *       argument. Best for CLIs that accept a positional task argument
 *       (e.g. {@code codex exec "..."}).</li>
 *   <li>{@link #STDIN} — write the prompt to the process's stdin and close it.
 *       Best for CLIs that read the task from stdin (some interactive modes).</li>
 *   <li>{@link #NONE} — don't inject the prompt at all; the caller has already
 *       embedded it via {@code baseArgs}. Useful for fully-templated commands.</li>
 * </ul>
 */
public enum PromptInjection {
    ARG,
    STDIN,
    NONE
}
