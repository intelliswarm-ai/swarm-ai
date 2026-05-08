package ai.intelliswarm.swarmai.agent.background;

/**
 * Lifecycle of a {@link BackgroundTask}. PENDING → RUNNING → one of the
 * three terminal states.
 */
public enum BackgroundTaskStatus {

    /** Spawned but not yet picked up by the runner. */
    PENDING,

    /** The runner is actively executing the task. */
    RUNNING,

    /** The runner finished successfully; {@code output} is set. */
    COMPLETED,

    /** The runner threw or returned an error; {@code error} is set. */
    FAILED,

    /** The task was cancelled before completion. */
    CANCELLED;

    /** True for terminal states the agent shouldn't expect to change further. */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
