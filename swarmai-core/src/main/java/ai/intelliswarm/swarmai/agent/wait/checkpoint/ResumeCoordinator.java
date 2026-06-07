package ai.intelliswarm.swarmai.agent.wait.checkpoint;

import ai.intelliswarm.swarmai.agent.wait.Signal;
import ai.intelliswarm.swarmai.agent.wait.SignalDispatcher;
import ai.intelliswarm.swarmai.message.typed.TypedMessage;
import ai.intelliswarm.swarmai.message.typed.TypedMessageHistory;
import ai.intelliswarm.swarmai.message.typed.TypedUserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Rehydrates a {@link PausedAgent} record and re-enters the agent loop with
 * the arrived signal as the next user message. The "second half" of the
 * cross-session checkpoint-and-release flow.
 *
 * <h2>Resume flow</h2>
 * <ol>
 *   <li>{@link #resume(String, Signal)} is called with the wait id and the
 *       arrived signal — typically by a listener on
 *       {@link SignalDispatcher#addCompletionListener}, or by an operator CLI.</li>
 *   <li>The coordinator loads the {@link PausedAgent} record from
 *       {@link PausedAgentStore}.</li>
 *   <li>It rebuilds the message list: system prompt + every persisted
 *       {@link TypedMessage} + a new user message containing the signal payload
 *       wrapped in {@code <system-reminder>} to alert the LLM that resumption
 *       is happening.</li>
 *   <li>It re-creates the tool callbacks via the supplied resolver.</li>
 *   <li>It calls {@code chatClient.prompt().messages(...).call().content()}
 *       and returns the result.</li>
 *   <li>If the resumed run produces another pause sentinel, the caller-side
 *       {@link PauseDetector} can persist a new record (this coordinator does
 *       not do that automatically — chain a {@code PauseDetector} call
 *       afterwards if needed).</li>
 *   <li>If the resumed run produced a final answer, the caller deletes the
 *       paused-agent record from the store.</li>
 * </ol>
 *
 * <h2>Tool callback rehydration</h2>
 * Spring AI tool callbacks are runtime objects (not serialisable). The
 * {@link ToolCallbackResolver} SPI lets harness code map persisted ids back to
 * fresh callback instances. Typical implementation: a registry keyed by name.
 */
public final class ResumeCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(ResumeCoordinator.class);

    private final ChatClient chatClient;
    private final PausedAgentStore store;
    private final ToolCallbackResolver resolver;

    public ResumeCoordinator(ChatClient chatClient, PausedAgentStore store, ToolCallbackResolver resolver) {
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient");
        this.store = Objects.requireNonNull(store, "store");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    /**
     * Resume the paused agent identified by {@code waitId} with the supplied
     * {@code signal}. Returns the LLM's response text, or empty if no paused
     * agent matches the wait id.
     *
     * <p>This method does NOT delete the paused-agent record on success — it
     * leaves that to the caller because the run might end with another pause
     * (in which case the record should be replaced via
     * {@link PauseDetector#detectAndPersist}, not deleted).
     */
    public Optional<ResumeResult> resume(String waitId, Signal signal) {
        Objects.requireNonNull(waitId, "waitId");
        Objects.requireNonNull(signal, "signal");

        Optional<PausedAgent> agentOpt = store.load(waitId);
        if (agentOpt.isEmpty()) {
            logger.warn("resume: no paused-agent record for wait id '{}'", waitId);
            return Optional.empty();
        }
        PausedAgent agent = agentOpt.get();

        // Rebuild the message list: system + persisted history + signal-as-user.
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(agent.systemPrompt()));

        TypedMessageHistory history = new TypedMessageHistory(agent.history());
        messages.addAll(history.toSpringMessages());

        // The signal payload becomes a user message wrapped in <system-reminder>
        // so the LLM recognises it as resume context, not a fresh user request.
        String resumeMessage = "<system-reminder>\n"
                + "You are being resumed after pausing on signal '" + signal.key() + "'.\n"
                + "Signal payload: " + signal.payload() + "\n"
                + "</system-reminder>\n\n"
                + "Continue your work using the signal payload above.";
        messages.add(new org.springframework.ai.chat.messages.UserMessage(resumeMessage));
        history.add(TypedUserMessage.now(resumeMessage));

        // Re-create tool callbacks
        List<ToolCallback> tools = resolver.resolve(agent.toolCallbackIds());

        // Run
        String reply;
        try {
            ChatClient.ChatClientRequestSpec spec = chatClient.prompt().messages(messages);
            if (!tools.isEmpty()) {
                spec = spec.toolCallbacks(tools.toArray(new ToolCallback[0]));
            }
            reply = spec.call().content();
        } catch (RuntimeException e) {
            logger.error("resume: LLM call failed for wait '{}': {}", waitId, e.getMessage(), e);
            return Optional.of(new ResumeResult(waitId, "(LLM call failed: " + e.getMessage() + ")",
                    history, false, null));
        }

        if (reply == null) reply = "";

        // Detect whether the resumed run paused again
        Optional<String> nextWait = WaitSentinel.parseWaitId(reply);
        return Optional.of(new ResumeResult(
                waitId, reply, history,
                nextWait.isPresent(),
                nextWait.orElse(null)));
    }

    /** SPI: re-create {@code ToolCallback} instances from persisted name ids. */
    @FunctionalInterface
    public interface ToolCallbackResolver {
        /**
         * Resolve a list of tool ids to fresh {@code ToolCallback} instances.
         * Ids unknown to the resolver should be skipped (the caller can no
         * longer call those tools, but a partial resume is better than none).
         */
        List<ToolCallback> resolve(List<String> toolIds);
    }

    /** Result of a {@link #resume(String, Signal)} call. */
    public record ResumeResult(
            String resumedWaitId,
            String reply,
            TypedMessageHistory history,
            boolean stillPaused,
            String nextWaitId) {

        public ResumeResult {
            Objects.requireNonNull(resumedWaitId, "resumedWaitId");
            Objects.requireNonNull(reply, "reply");
            Objects.requireNonNull(history, "history");
        }

        /** Convenience: paused again? */
        public boolean isStillPaused() { return stillPaused; }

        public Optional<String> nextWaitIdOpt() { return Optional.ofNullable(nextWaitId); }
    }
}
