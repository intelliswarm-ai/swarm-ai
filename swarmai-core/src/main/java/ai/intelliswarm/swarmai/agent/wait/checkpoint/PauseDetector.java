package ai.intelliswarm.swarmai.agent.wait.checkpoint;

import ai.intelliswarm.swarmai.agent.wait.SignalDispatcher;
import ai.intelliswarm.swarmai.agent.wait.WaitForSignalRequest;
import ai.intelliswarm.swarmai.message.typed.TypedAssistantMessage;
import ai.intelliswarm.swarmai.message.typed.TypedMessage;
import ai.intelliswarm.swarmai.message.typed.TypedMessageHistory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Caller-side helper for detecting and persisting checkpoint-style pauses.
 * Wraps the inspect-then-persist flow so harness code can call a single method
 * after each LLM round-trip:
 *
 * <pre>{@code
 * String reply = chatClient.prompt().messages(history).call().content();
 * Optional<PausedAgent> paused = pauseDetector.detectAndPersist(
 *         reply, history, systemPrompt, toolCallbackIds, metadata);
 * if (paused.isPresent()) {
 *     // agent paused — return control to the operator/UI
 *     return;
 * }
 * // otherwise treat reply as the final answer
 * }</pre>
 *
 * <p>The detector:
 * <ol>
 *   <li>Looks for a {@link WaitSentinel} in the LLM's reply.</li>
 *   <li>If found, looks up the matching {@code WaitForSignalRequest} from the
 *       dispatcher to confirm the wait actually exists.</li>
 *   <li>Constructs a {@link PausedAgent} record with the conversation history
 *       (including the LLM's most recent reply, so resume picks up correctly)
 *       and the supplied system prompt + tool callback ids.</li>
 *   <li>Persists the record via {@link PausedAgentStore#save}.</li>
 * </ol>
 *
 * <p>Idempotent: calling {@code detectAndPersist} on the same reply twice
 * persists the record twice — but always under the same wait id, so the second
 * call overwrites the first. This is a feature: it lets harness retries work.
 */
public final class PauseDetector {

    private final SignalDispatcher dispatcher;
    private final PausedAgentStore store;

    public PauseDetector(SignalDispatcher dispatcher, PausedAgentStore store) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * Inspect an LLM reply for a {@link WaitSentinel}; if found, persist the
     * conversation state and return the resulting {@link PausedAgent} record.
     *
     * @param llmReply         the assistant's response from the most recent
     *                         {@code chatClient.prompt().call()}
     * @param history          the conversation history NOT yet including the
     *                         {@code llmReply} — the detector appends the
     *                         assistant message internally
     * @param systemPrompt     the system prompt the chat client used
     * @param toolCallbackIds  ids of tool callbacks loaded for the call
     * @param metadata         optional harness state to persist alongside
     * @return the persisted {@link PausedAgent} if a pause was detected,
     *         empty otherwise
     */
    public Optional<PausedAgent> detectAndPersist(String llmReply,
                                                   TypedMessageHistory history,
                                                   String systemPrompt,
                                                   List<String> toolCallbackIds,
                                                   Map<String, Object> metadata) {
        Objects.requireNonNull(llmReply, "llmReply");
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(systemPrompt, "systemPrompt");

        Optional<String> waitId = WaitSentinel.parseWaitId(llmReply);
        if (waitId.isEmpty()) return Optional.empty();

        Optional<WaitForSignalRequest> waitOpt = dispatcher.getWait(waitId.get());
        if (waitOpt.isEmpty()) {
            // The LLM emitted a sentinel that doesn't correspond to any
            // dispatcher wait. Could be hallucination or a stale resume.
            // Don't persist — the wait id is meaningless.
            return Optional.empty();
        }
        WaitForSignalRequest wait = waitOpt.get();

        // Append the assistant's reply to the history so resume sees it
        TypedMessageHistory pausedHistory = new TypedMessageHistory(history.all());
        pausedHistory.add(TypedAssistantMessage.now(llmReply));

        PausedAgent paused = new PausedAgent(
                wait.id(),
                wait.signalKey(),
                wait.reason(),
                systemPrompt,
                pausedHistory.all(),
                (toolCallbackIds == null) ? List.of() : toolCallbackIds,
                (metadata == null) ? Map.of() : metadata,
                Instant.now(),
                wait.expiresAtOpt().orElse(null));

        store.save(paused);
        return Optional.of(paused);
    }

    /** Convenience: detect without persisting. */
    public Optional<String> detect(String llmReply) {
        return WaitSentinel.parseWaitId(llmReply);
    }
}
