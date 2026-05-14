package ai.intelliswarm.swarmai.agent.llm;

import ai.intelliswarm.swarmai.api.PublicApi;

/**
 * Provider-neutral stop reason. Adapters map their provider's specific stop
 * codes onto this small set so agent-loop code reads a single enum.
 */
@PublicApi(since = "1.0.24")
public enum LlmStopReason {
    END_TURN,
    MAX_TOKENS,
    STOP_SEQUENCE,
    /** Model requested tool execution — caller must run tools and resume. */
    TOOL_USE,
    /** Safety / refusal stop. */
    REFUSAL,
    UNKNOWN
}
