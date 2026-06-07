package ai.intelliswarm.swarmai.agent.llm;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.List;
import java.util.Objects;

/**
 * One message in an LLM conversation, provider-neutral. Carries a role + text
 * + an optional list of tool calls (when {@link #role()} is
 * {@link LlmRole#ASSISTANT}) or a single tool-call-id (when role is
 * {@link LlmRole#TOOL}, i.e. a tool-result reply).
 *
 * <p>{@link LlmClient} implementations translate to their provider's wire
 * format. Adapters are expected to round-trip cleanly: assistant messages
 * with tool calls flow out, tool-result messages flow back in.
 */
@PublicApi(since = "1.0.24")
public record LlmMessage(
        LlmRole role,
        String content,
        List<LlmToolCall> toolCalls,
        String toolUseId) {

    public LlmMessage {
        Objects.requireNonNull(role, "role must not be null");
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static LlmMessage system(String content) {
        return new LlmMessage(LlmRole.SYSTEM, content, List.of(), null);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage(LlmRole.USER, content, List.of(), null);
    }

    public static LlmMessage assistant(String content) {
        return new LlmMessage(LlmRole.ASSISTANT, content, List.of(), null);
    }

    public static LlmMessage assistantWithToolCalls(String content, List<LlmToolCall> toolCalls) {
        return new LlmMessage(LlmRole.ASSISTANT, content, toolCalls, null);
    }

    public static LlmMessage toolResult(String toolUseId, String content) {
        return new LlmMessage(LlmRole.TOOL, content, List.of(), toolUseId);
    }
}
