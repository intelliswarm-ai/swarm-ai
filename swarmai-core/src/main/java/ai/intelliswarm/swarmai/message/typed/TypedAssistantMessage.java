package ai.intelliswarm.swarmai.message.typed;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An assistant message — text the model produced and (optionally) any tool
 * calls it issued in the same turn. Typed wrapper around Spring AI's
 * {@code AssistantMessage}.
 *
 * <p>{@code content} may be empty when the assistant only emitted tool calls;
 * {@code toolCalls} may be empty when the assistant only produced text.
 */
public record TypedAssistantMessage(
        String content,
        Instant timestamp,
        List<ToolCall> toolCalls,
        Map<String, Object> metadata) implements TypedMessage {

    public TypedAssistantMessage {
        Objects.requireNonNull(content, "content");
        if (timestamp == null) timestamp = Instant.now();
        toolCalls = (toolCalls == null) ? List.of() : List.copyOf(toolCalls);
        metadata = (metadata == null) ? Map.of() : Map.copyOf(metadata);
    }

    /** Plain text-only assistant message. */
    public static TypedAssistantMessage now(String content) {
        return new TypedAssistantMessage(content, Instant.now(), List.of(), Map.of());
    }

    /** Assistant message with tool calls. {@code content} may be empty. */
    public static TypedAssistantMessage withCalls(String content, List<ToolCall> toolCalls) {
        return new TypedAssistantMessage(content, Instant.now(), toolCalls, Map.of());
    }

    /** True if the assistant emitted any tool calls in this turn. */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
