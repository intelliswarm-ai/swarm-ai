package ai.intelliswarm.swarmai.agent.llm;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.List;
import java.util.Objects;

/**
 * Provider-neutral LLM response. Carries text, tool calls, citations, stop
 * reason, and full token-usage telemetry (including cache breakdown when the
 * adapter supports it).
 *
 * @param requestId  provider-issued id for support escalation; may be {@code null}
 * @param modelId    the model that produced the response
 * @param stopReason why the model stopped
 * @param text       concatenated assistant text (empty when stopReason = TOOL_USE and no preamble was emitted)
 * @param toolCalls  tool-use blocks the model emitted (empty when stopReason ≠ TOOL_USE)
 * @param citations  inline citations across the response (empty when citations weren't requested)
 * @param tokenUsage per-response token accounting
 */
@PublicApi(since = "1.0.24")
public record LlmResponse(
        String requestId,
        String modelId,
        LlmStopReason stopReason,
        String text,
        List<LlmToolCall> toolCalls,
        List<InlineCitation> citations,
        TokenUsage tokenUsage) {

    public LlmResponse {
        Objects.requireNonNull(stopReason, "stopReason must not be null");
        text = text == null ? "" : text;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        citations = citations == null ? List.of() : List.copyOf(citations);
        if (tokenUsage == null) tokenUsage = TokenUsage.ZERO;
    }

    public boolean needsToolExecution() {
        return stopReason == LlmStopReason.TOOL_USE && !toolCalls.isEmpty();
    }
}
