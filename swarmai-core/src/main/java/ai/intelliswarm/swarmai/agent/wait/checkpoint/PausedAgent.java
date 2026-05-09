package ai.intelliswarm.swarmai.agent.wait.checkpoint;

import ai.intelliswarm.swarmai.message.typed.TypedMessage;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable record of an agent that paused mid-conversation while waiting for a
 * signal. Contains everything a {@link ResumeCoordinator} needs to rehydrate
 * the agent in a fresh JVM session and continue from where it left off.
 *
 * @param id                wait id — same as the {@code WaitForSignalRequest.id}
 *                          the dispatcher knows. Acts as the stable correlation key.
 * @param signalKey         the signal the agent is waiting for
 * @param reason            human-readable description, propagated from the wait
 * @param systemPrompt      the system prompt the original chat client used; needed
 *                          on resume to recreate the same agent personality
 * @param history           conversation history at pause time. Reuses the
 *                          existing {@link TypedMessage} sealed family so the
 *                          {@link ai.intelliswarm.swarmai.message.typed.TypedMessageHistory}
 *                          round-trip works.
 * @param toolCallbackIds   names of tool callbacks the agent had loaded.
 *                          {@link ResumeCoordinator.ToolCallbackResolver}
 *                          re-creates actual {@code ToolCallback} instances on
 *                          resume from these names.
 * @param metadata          arbitrary harness-attached state (trace ids,
 *                          tenant ids, retry counts, etc.)
 * @param pausedAt          when the agent paused
 * @param expiresAt         optional cap on how long the persisted state lives;
 *                          useful for stores that periodically GC
 */
public record PausedAgent(
        String id,
        String signalKey,
        String reason,
        String systemPrompt,
        List<TypedMessage> history,
        List<String> toolCallbackIds,
        Map<String, Object> metadata,
        Instant pausedAt,
        Instant expiresAt) {

    public PausedAgent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(signalKey, "signalKey");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        Objects.requireNonNull(pausedAt, "pausedAt");
        if (id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        if (signalKey.isBlank()) throw new IllegalArgumentException("signalKey must not be blank");
        history = (history == null) ? List.of() : List.copyOf(history);
        toolCallbackIds = (toolCallbackIds == null) ? List.of() : List.copyOf(toolCallbackIds);
        metadata = (metadata == null) ? Map.of() : Map.copyOf(metadata);
        // expiresAt may legitimately be null (no expiry)
    }

    public Optional<Instant> expiresAtOpt() {
        return Optional.ofNullable(expiresAt);
    }

    /**
     * True if this paused-agent record has passed its TTL. Stores can GC
     * records satisfying this predicate.
     */
    public boolean hasExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }
}
