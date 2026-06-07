package ai.intelliswarm.swarmai.agent.wait;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;

/**
 * Builds an agent-callable {@link ToolCallback} that exposes a
 * {@link SignalDispatcher} as the {@code wait_for_signal} tool. Pair with a
 * complementary signal source (wall-clock scheduler, webhook handler, manual
 * operator command) that calls {@link SignalDispatcher#deliver} when the
 * external event occurs.
 *
 * <p><b>This is the Mode A implementation</b> — the tool blocks the calling
 * thread until the signal arrives or the wait times out. Suitable for
 * minute-to-hour-scale waits within a single JVM session. For
 * day-to-week-scale cross-session waits, a future Mode B implementation will
 * checkpoint via {@code CheckpointSaver} and release the thread; the
 * {@link SignalDispatcher} API and the records here are designed to
 * round-trip through that flow unchanged.
 *
 * <h2>Tool input shape</h2>
 * <pre>
 *   { "signalKey": "deploy.canary.ready",
 *     "reason":    "waiting for canary deploy",
 *     "timeoutSeconds": 600 }   // optional; default {@link #DEFAULT_TIMEOUT}
 * </pre>
 *
 * <h2>Tool output shape</h2>
 * <pre>
 *   "Signal received: ok" or
 *   "Signal received: <payload>" — the LLM sees the signal payload
 * </pre>
 *
 * On error:
 * <pre>
 *   "Error: timeout — the wait expired after 600s"
 *   "Error: cancelled — the harness cancelled this wait"
 *   "Error: ..." — other failure modes
 * </pre>
 */
public final class WaitForSignalTool {

    /** Default function name registered with Spring AI. */
    public static final String FUNCTION_NAME = "wait_for_signal";

    /** Default per-call block ceiling — 30 minutes. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(30);

    private WaitForSignalTool() {}

    /** Build a callback bound to the given dispatcher, with the default function name + timeout. */
    public static ToolCallback callback(SignalDispatcher dispatcher) {
        return callback(dispatcher, FUNCTION_NAME, DEFAULT_TIMEOUT);
    }

    /** Build a callback with a custom function name and the default timeout. */
    public static ToolCallback callback(SignalDispatcher dispatcher, String functionName) {
        return callback(dispatcher, functionName, DEFAULT_TIMEOUT);
    }

    /** Build a callback with full control over function name and per-call block ceiling. */
    public static ToolCallback callback(SignalDispatcher dispatcher, String functionName,
                                         Duration defaultTimeout) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        Objects.requireNonNull(functionName, "functionName");
        Objects.requireNonNull(defaultTimeout, "defaultTimeout");
        Function<WaitInput, String> fn = input -> handle(dispatcher, defaultTimeout, input);
        return FunctionToolCallback.builder(functionName, fn)
                .description(
                        "Pause execution until an external signal arrives. "
                                + "Required: signalKey (the identifier the signal source will "
                                + "use to wake you up; e.g. 'deploy.canary.ready'), reason "
                                + "(short human-readable description for the operator log). "
                                + "Optional: timeoutSeconds (defaults to 1800 = 30 minutes; "
                                + "the call will time out and return an error if no signal "
                                + "arrives in this window). "
                                + "Returns the signal's payload as 'Signal received: <payload>' "
                                + "on success, or 'Error: ...' on timeout/cancel/etc.")
                .inputType(WaitInput.class)
                .build();
    }

    /** Visible for testing. */
    static String handle(SignalDispatcher dispatcher, Duration defaultTimeout, WaitInput input) {
        if (input == null || input.signalKey == null || input.signalKey.isBlank()) {
            return "Error: 'signalKey' is required and must not be blank.";
        }
        String reason = (input.reason == null || input.reason.isBlank())
                ? "wait_for_signal call from agent"
                : input.reason;
        Duration ttl = (input.timeoutSeconds == null || input.timeoutSeconds <= 0)
                ? defaultTimeout
                : Duration.ofSeconds(input.timeoutSeconds);

        WaitForSignalRequest wait;
        try {
            wait = dispatcher.registerWait(input.signalKey, reason, ttl);
        } catch (RuntimeException e) {
            return "Error: " + e.getMessage();
        }

        // Block the agent's tool-call thread until signal / cancel / expire.
        // The block-deadline is generous beyond the wait's own TTL so the TTL
        // is what actually expires (gives clearer error reporting).
        Duration blockCap = ttl.plus(Duration.ofSeconds(10));
        try {
            Signal signal = dispatcher.awaitSignal(wait.id(), blockCap);
            String payload = signal.payload();
            return "Signal received: " + (payload.isEmpty() ? "(empty)" : payload);
        } catch (WaitForSignalException.Timeout e) {
            return "Error: timeout — " + e.getMessage();
        } catch (WaitForSignalException.Cancelled e) {
            return "Error: cancelled — " + e.getMessage();
        } catch (WaitForSignalException e) {
            return "Error: " + e.getMessage();
        } catch (RuntimeException e) {
            return "Error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** Tool input shape, deserialised by Spring AI from the LLM's JSON. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class WaitInput {
        public String signalKey;
        public String reason;
        public Long timeoutSeconds;
    }
}
