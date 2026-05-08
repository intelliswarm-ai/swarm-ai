package ai.intelliswarm.swarmai.tool.discovery;

/**
 * Outcome of a {@link ToolLoader#requestLoad(String)} call. Returned to the
 * agent as part of the {@code tool_load} response so it can decide whether to
 * (a) wait for the next turn, (b) use a tool it already has, or (c) pick a
 * different tool from search results.
 */
public enum LoadRequestStatus {

    /** The tool was queued for loading and will be available on the next turn. */
    PENDING,

    /** The tool is already loaded — the agent can call it now. */
    ALREADY_LOADED,

    /** The name doesn't exist in the catalog. The agent should re-search. */
    NOT_IN_CATALOG,

    /** Catalogued but no callback supplier was registered with the loader. */
    NO_CALLBACK_REGISTERED;

    /** True for terminal failure states the agent must recover from. */
    public boolean isFailure() {
        return this == NOT_IN_CATALOG || this == NO_CALLBACK_REGISTERED;
    }
}
