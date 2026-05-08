package ai.intelliswarm.swarmai.message.typed;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * A system-role prompt — instructions, persona, constraints. Typed wrapper
 * around Spring AI's {@code SystemMessage}.
 */
public record TypedSystemMessage(
        String content,
        Instant timestamp,
        Map<String, Object> metadata) implements TypedMessage {

    public TypedSystemMessage {
        Objects.requireNonNull(content, "content");
        if (timestamp == null) timestamp = Instant.now();
        metadata = (metadata == null) ? Map.of() : Map.copyOf(metadata);
    }

    /** Convenience: now-stamped system message with no metadata. */
    public static TypedSystemMessage now(String content) {
        return new TypedSystemMessage(content, Instant.now(), Map.of());
    }
}
