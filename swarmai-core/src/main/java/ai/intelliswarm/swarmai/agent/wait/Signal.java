package ai.intelliswarm.swarmai.agent.wait;

import java.time.Instant;
import java.util.Objects;

/**
 * A wake-up event delivered to a paused agent. Identified by a {@link #key}
 * the agent registered when calling {@code wait_for_signal}; carries an
 * opaque {@link #payload} that the agent will see as the tool's return value.
 *
 * <p>Signal sources (wall-clock timers, webhook handlers, manual operator
 * commands, external job-completion notifications) call into
 * {@link SignalDispatcher#deliver} to fire signals. The first pending wait
 * with a matching key is woken up.
 *
 * @param key         identifier the agent registered the wait under (e.g.
 *                    {@code "deploy.canary.complete"} or {@code "approval.123"})
 * @param payload     opaque content delivered to the agent — typically a
 *                    short string the LLM will see as the tool's output
 * @param deliveredAt wall-clock instant the signal was delivered
 */
public record Signal(String key, String payload, Instant deliveredAt) {

    public Signal {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(payload, "payload");
        if (key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        if (deliveredAt == null) deliveredAt = Instant.now();
    }

    /** Convenience: now-stamped signal. */
    public static Signal of(String key, String payload) {
        return new Signal(key, payload, Instant.now());
    }

    /** Convenience: now-stamped signal with empty payload. */
    public static Signal of(String key) {
        return new Signal(key, "", Instant.now());
    }
}
