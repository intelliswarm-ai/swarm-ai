package ai.intelliswarm.swarmai.agent.subagent;

import java.time.Duration;

/**
 * Runtime handle for a spawned subagent. Returned by
 * {@link SubagentSpawner#spawn(SubagentSpec)}; the parent uses it to
 * {@link #awaitResult} (foreground), poll {@link #status}, or {@link #cancel}.
 *
 * <p>Lifecycle: {@link SubagentStatus#PENDING} → {@link SubagentStatus#RUNNING}
 * → one of the terminal statuses. {@link #awaitResult} blocks until the
 * subagent reaches a terminal status or its own timeout fires.
 *
 * <p>Implementations must be thread-safe — a parent may poll status and
 * cancel from a different thread than the one that spawned.
 */
public interface Subagent {

    /** Spec id; matches {@link SubagentSpec#id()}. */
    String id();

    /** Current lifecycle status. Safe to call from any thread. */
    SubagentStatus status();

    /**
     * Block until the subagent reaches a terminal status or {@code waitUntil}
     * elapses (whichever is sooner). If the spec carried its own
     * {@link SubagentSpec#timeout}, the effective wait is the minimum.
     *
     * <p>Always returns a {@link SubagentResult} — never throws. If the wait
     * elapses, the result's status is {@link SubagentStatus#TIMED_OUT} and the
     * underlying execution is cancelled.
     *
     * @param waitUntil maximum time to wait; {@link Duration#ZERO} means "wait
     *                  indefinitely" (subject to the spec's own timeout)
     */
    SubagentResult awaitResult(Duration waitUntil);

    /**
     * Cancel the subagent if it hasn't completed. No-op for terminal states.
     * Subsequent {@link #awaitResult} returns
     * {@link SubagentStatus#CANCELLED} unless it had already terminated.
     */
    void cancel();
}
