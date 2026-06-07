package ai.intelliswarm.swarmai.agent.wait;

/**
 * Lifecycle of a {@link WaitForSignalRequest}. PENDING → one of three
 * terminal states.
 */
public enum WaitStatus {

    /** The wait is registered and the agent is suspended. */
    PENDING,

    /** A matching signal arrived; the agent has been (or is being) woken up. */
    COMPLETED,

    /** The wait was cancelled by the harness or operator before any signal arrived. */
    CANCELLED,

    /** The wait's TTL elapsed before any signal arrived. */
    EXPIRED;

    /** True for the three terminal states. */
    public boolean isTerminal() {
        return this != PENDING;
    }
}
