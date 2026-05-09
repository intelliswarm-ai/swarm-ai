package ai.intelliswarm.swarmai.agent.wait.checkpoint;

import ai.intelliswarm.swarmai.agent.wait.SignalDispatcher;
import ai.intelliswarm.swarmai.agent.wait.WaitForSignalRequest;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;

/**
 * Mode B variant of the {@code wait_for_signal} tool: instead of blocking the
 * tool-call thread, it registers the wait and returns a sentinel string
 * carrying the wait id. The LLM is instructed (via system prompt) to forward
 * the sentinel verbatim in its response. The caller-side {@link PauseDetector}
 * recognises the sentinel and triggers persistence to a {@link PausedAgentStore}.
 *
 * <p>This is the "checkpoint-and-release" half of cross-session pause-resume:
 * the agent's thread is freed; the conversation state is persisted; a separate
 * {@link ResumeCoordinator} rehydrates and re-enters the agent loop when the
 * signal arrives.
 *
 * <h2>Tool input shape</h2>
 * <pre>
 *   { "signalKey": "deploy.canary.healthy",
 *     "reason":    "waiting for deploy",
 *     "ttlSeconds": 86400 }   // optional; defaults to {@link #DEFAULT_TTL}
 * </pre>
 *
 * <h2>Tool output shape</h2>
 * <pre>
 *   "Paused: WAITING_FOR_SIGNAL:wait_a1b2c3d4 — your reply MUST end with
 *    this sentinel exactly so the harness knows to persist the conversation."
 * </pre>
 *
 * <p>The trailing instruction nudges the LLM to forward the sentinel even
 * when its training inclines it to "explain" the result. The actual contract
 * (the sentinel must appear somewhere in the LLM's response) is enforced by
 * {@link PauseDetector}.
 */
public final class CheckpointingWaitForSignalTool {

    public static final String FUNCTION_NAME = "wait_for_signal";

    /** Default wait TTL — 24 hours, suitable for typical multi-day pauses. */
    public static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private CheckpointingWaitForSignalTool() {}

    /** Build a callback bound to the given dispatcher, with the default function name + TTL. */
    public static ToolCallback callback(SignalDispatcher dispatcher) {
        return callback(dispatcher, FUNCTION_NAME, DEFAULT_TTL);
    }

    /** Build a callback with a custom function name and the default TTL. */
    public static ToolCallback callback(SignalDispatcher dispatcher, String functionName) {
        return callback(dispatcher, functionName, DEFAULT_TTL);
    }

    /** Build a callback with full control over function name and default TTL. */
    public static ToolCallback callback(SignalDispatcher dispatcher, String functionName,
                                         Duration defaultTtl) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        Objects.requireNonNull(functionName, "functionName");
        Objects.requireNonNull(defaultTtl, "defaultTtl");
        Function<WaitInput, String> fn = input -> handle(dispatcher, defaultTtl, input);
        return FunctionToolCallback.builder(functionName, fn)
                .description(
                        "Pause execution until an external signal arrives. The harness "
                                + "will persist your conversation state and resume you when "
                                + "the signal is delivered. "
                                + "Required: signalKey (the identifier the signal source will "
                                + "use to wake you up), reason (short description for the operator log). "
                                + "Optional: ttlSeconds (how long the persisted state lives, default 86400 = 24h). "
                                + "Returns a sentinel string starting with 'WAITING_FOR_SIGNAL:'. "
                                + "When you receive that sentinel, your reply MUST include it "
                                + "verbatim — that's how the harness knows to persist your state. "
                                + "Do NOT call wait_for_signal more than once in a single response.")
                .inputType(WaitInput.class)
                .build();
    }

    /** Visible for testing. */
    static String handle(SignalDispatcher dispatcher, Duration defaultTtl, WaitInput input) {
        if (input == null || input.signalKey == null || input.signalKey.isBlank()) {
            return "Error: 'signalKey' is required and must not be blank.";
        }
        String reason = (input.reason == null || input.reason.isBlank())
                ? "checkpointing wait_for_signal call"
                : input.reason;
        Duration ttl = (input.ttlSeconds == null || input.ttlSeconds <= 0)
                ? defaultTtl
                : Duration.ofSeconds(input.ttlSeconds);

        WaitForSignalRequest wait;
        try {
            wait = dispatcher.registerWait(input.signalKey, reason, ttl);
        } catch (RuntimeException e) {
            return "Error: " + e.getMessage();
        }

        String sentinel = WaitSentinel.forWaitId(wait.id());
        return "Paused: " + sentinel
                + " — your reply MUST end with this sentinel exactly so the harness "
                + "knows to persist the conversation.";
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class WaitInput {
        public String signalKey;
        public String reason;
        public Long ttlSeconds;
    }
}
