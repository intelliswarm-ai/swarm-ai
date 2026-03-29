package ai.intelliswarm.swarmai.state;

import java.util.Map;

/**
 * A functional interface for defining node actions in the graph-based workflow API.
 * Each node receives the current {@link AgentState} and returns a partial state update
 * that is merged back into the workflow state.
 *
 * <p>This enables lambda-based workflow construction as an alternative to the
 * Agent/Task builder pattern:
 * <pre>{@code
 * SwarmGraph.create()
 *     .addNode("research", state -> {
 *         String topic = state.valueOrDefault("topic", "AI");
 *         // ... do research ...
 *         return Map.of("findings", results);
 *     })
 *     .addNode("summarize", state -> {
 *         String findings = state.valueOrDefault("findings", "");
 *         return Map.of("summary", summarize(findings));
 *     })
 *     .addEdge("research", "summarize")
 *     .compile();
 * }</pre>
 *
 * @param <S> the state type (extends AgentState)
 */
@FunctionalInterface
public interface NodeAction<S extends AgentState> {

    /**
     * Executes the node logic and returns a partial state update.
     *
     * @param state the current workflow state
     * @return a map of key-value updates to merge into the state
     * @throws Exception if the node action fails
     */
    Map<String, Object> apply(S state) throws Exception;
}
