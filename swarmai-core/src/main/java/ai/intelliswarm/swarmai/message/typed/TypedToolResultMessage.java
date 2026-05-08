package ai.intelliswarm.swarmai.message.typed;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * The result of a tool call — pairs with a {@link TypedToolCallMessage} (or a
 * call inside {@link TypedAssistantMessage#toolCalls()}) by {@code toolCallId}.
 *
 * @param content     the tool's output as a string (typically what the LLM sees)
 * @param toolCallId  matches {@link ToolCall#id()} of the originating call
 * @param isError     true if the tool returned an error (e.g. validation failure,
 *                    refusal, exception). Carried separately so introspection
 *                    can show "agent retried after each error" without parsing
 *                    content for "Error:" prefixes.
 */
public record TypedToolResultMessage(
        String content,
        Instant timestamp,
        String toolCallId,
        boolean isError,
        Map<String, Object> metadata) implements TypedMessage {

    public TypedToolResultMessage {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(toolCallId, "toolCallId");
        if (toolCallId.isBlank()) {
            throw new IllegalArgumentException("toolCallId must not be blank");
        }
        if (timestamp == null) timestamp = Instant.now();
        metadata = (metadata == null) ? Map.of() : Map.copyOf(metadata);
    }

    /** Convenience: a successful tool result. */
    public static TypedToolResultMessage success(String toolCallId, String content) {
        return new TypedToolResultMessage(content, Instant.now(), toolCallId, false, Map.of());
    }

    /** Convenience: a tool error result. */
    public static TypedToolResultMessage error(String toolCallId, String content) {
        return new TypedToolResultMessage(content, Instant.now(), toolCallId, true, Map.of());
    }
}
