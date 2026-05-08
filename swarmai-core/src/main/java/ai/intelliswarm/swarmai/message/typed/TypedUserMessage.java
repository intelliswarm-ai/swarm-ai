package ai.intelliswarm.swarmai.message.typed;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * A message authored by the user — typed wrapper around what Spring AI calls
 * {@code UserMessage}.
 */
public record TypedUserMessage(
        String content,
        Instant timestamp,
        Map<String, Object> metadata) implements TypedMessage {

    public TypedUserMessage {
        Objects.requireNonNull(content, "content");
        if (timestamp == null) timestamp = Instant.now();
        metadata = (metadata == null) ? Map.of() : Map.copyOf(metadata);
    }

    /** Convenience: now-stamped user message with no metadata. */
    public static TypedUserMessage now(String content) {
        return new TypedUserMessage(content, Instant.now(), Map.of());
    }
}
