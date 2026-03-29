package ai.intelliswarm.swarmai.state;

/**
 * A functional interface for conditional edge routing in the graph-based workflow API.
 * Given the current state, returns the ID of the next node to execute.
 *
 * <p>Usage:
 * <pre>{@code
 * graph.addConditionalEdge("analyze", state -> {
 *     boolean needsMore = state.valueOrDefault("needsMoreData", false);
 *     return needsMore ? "research" : SwarmGraph.END;
 * });
 * }</pre>
 *
 * @param <S> the state type (extends AgentState)
 */
@FunctionalInterface
public interface EdgeAction<S extends AgentState> {

    /**
     * Evaluates the current state and returns the ID of the next node.
     *
     * @param state the current workflow state
     * @return the next node ID, or {@link SwarmGraph#END} to finish
     * @throws Exception if the routing logic fails
     */
    String apply(S state) throws Exception;
}
