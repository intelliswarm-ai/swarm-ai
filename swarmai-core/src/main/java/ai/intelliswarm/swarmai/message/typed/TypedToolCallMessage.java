package ai.intelliswarm.swarmai.message.typed;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * A standalone tool-call record — when the harness wants to log each tool
 * call as its own history entry rather than embedding all of a turn's calls
 * inside an {@link TypedAssistantMessage#toolCalls()} list. Useful for
 * fine-grained observability ("show me every tool the agent called, in order").
 *
 * <p>Both representations are valid: keep tool calls inside the assistant
 * message for prompt-replay fidelity, OR also append a separate
 * {@code TypedToolCallMessage} for query-friendly history. The harness chooses.
 *
 * @param content   pretty-printed summary, e.g. {@code "calculator(expression=2+2)"}
 * @param toolCall  the structured call — id, name, argsJson
 */
public record TypedToolCallMessage(
        String content,
        Instant timestamp,
        ToolCall toolCall,
        Map<String, Object> metadata) implements TypedMessage {

    public TypedToolCallMessage {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(toolCall, "toolCall");
        if (timestamp == null) timestamp = Instant.now();
        metadata = (metadata == null) ? Map.of() : Map.copyOf(metadata);
    }

    /** Convenience: now-stamped, summary derived from the call. */
    public static TypedToolCallMessage now(ToolCall call) {
        Objects.requireNonNull(call, "call");
        return new TypedToolCallMessage(
                call.name() + "(" + call.argsJson() + ")",
                Instant.now(), call, Map.of());
    }
}
