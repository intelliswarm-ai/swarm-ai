package ai.intelliswarm.swarmai.message.typed;

import java.time.Instant;
import java.util.Map;

/**
 * Sealed root of the typed-message hierarchy. Distinct from Spring AI's
 * {@code Message} interface in three ways:
 *
 * <ol>
 *   <li><b>Type-safe access:</b> sealed permits + records means callers can
 *       use exhaustive {@code switch} expressions without {@code instanceof}
 *       chains.</li>
 *   <li><b>Structured fields:</b> tool calls have a {@link ToolCall} record
 *       with id/name/args fields; tool results carry an explicit
 *       {@code toolCallId} and {@code isError} flag.</li>
 *   <li><b>Per-message metadata:</b> every message carries a free-form
 *       {@code metadata} map for harness-attached state (provenance, span ids,
 *       routing tags) without polluting the content string.</li>
 * </ol>
 *
 * <p>Messages are immutable. Append to a {@link TypedMessageHistory} to record
 * a conversation; query by type or predicate after the fact.
 *
 * <p>Each variant carries:
 * <ul>
 *   <li>{@code content()} — the human-readable message body</li>
 *   <li>{@code timestamp()} — when the message was created</li>
 *   <li>{@code metadata()} — immutable map of free-form key/value pairs</li>
 * </ul>
 */
public sealed interface TypedMessage permits
        TypedSystemMessage,
        TypedUserMessage,
        TypedAssistantMessage,
        TypedToolCallMessage,
        TypedToolResultMessage {

    String content();
    Instant timestamp();
    Map<String, Object> metadata();
}
