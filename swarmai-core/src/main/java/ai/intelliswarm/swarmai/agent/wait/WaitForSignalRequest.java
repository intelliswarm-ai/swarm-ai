package ai.intelliswarm.swarmai.agent.wait;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable snapshot of a wait registration tracked by {@link SignalDispatcher}.
 * The dispatcher keeps a mutable internal entry; this record is what callers
 * see when they query state.
 *
 * @param id          dispatcher-assigned identifier; unique for the dispatcher's
 *                    lifetime
 * @param signalKey   the {@link Signal#key} the wait will match against
 * @param reason      human-readable description of why the agent paused, e.g.
 *                    {@code "waiting for canary deploy to complete"}
 * @param pausedAt    when the wait was registered
 * @param expiresAt   optional TTL — null means the wait has no timeout
 * @param status      current lifecycle status
 * @param completedAt when the wait reached a terminal status, if it has
 * @param received    the signal that completed this wait, if {@link #status}
 *                    is {@link WaitStatus#COMPLETED}; empty otherwise
 */
public record WaitForSignalRequest(
        String id,
        String signalKey,
        String reason,
        Instant pausedAt,
        Instant expiresAt,
        WaitStatus status,
        Instant completedAt,
        Signal received) {

    public WaitForSignalRequest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(signalKey, "signalKey");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(pausedAt, "pausedAt");
        Objects.requireNonNull(status, "status");
        if (id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        if (signalKey.isBlank()) throw new IllegalArgumentException("signalKey must not be blank");
    }

    /** True if the wait has reached COMPLETED, CANCELLED, or EXPIRED. */
    public boolean isTerminal() {
        return status.isTerminal();
    }

    /** Optional view on the TTL deadline. */
    public Optional<Instant> expiresAtOpt() {
        return Optional.ofNullable(expiresAt);
    }

    /** Optional view on the completion timestamp. */
    public Optional<Instant> completedAtOpt() {
        return Optional.ofNullable(completedAt);
    }

    /** Optional view on the received signal — present only when COMPLETED. */
    public Optional<Signal> receivedOpt() {
        return Optional.ofNullable(received);
    }
}
