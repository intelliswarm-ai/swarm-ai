package ai.intelliswarm.swarmai.agent.llm;

import ai.intelliswarm.swarmai.api.PublicApi;

/**
 * Role of a single message in an LLM conversation. Provider-neutral —
 * adapters translate to whatever the underlying provider uses
 * (Spring AI's {@code MessageType}, Anthropic's {@code role}, OpenAI's
 * {@code role}, etc.).
 */
@PublicApi(since = "1.0.24")
public enum LlmRole {
    SYSTEM,
    USER,
    ASSISTANT,
    /** Tool result turn — replies a prior tool call's output. */
    TOOL
}
