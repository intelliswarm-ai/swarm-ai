package ai.intelliswarm.swarmai.agent.subagent;

/**
 * Lifecycle of a {@link Subagent} from spawn to terminal state.
 */
public enum SubagentStatus {
    /** Spec built, not yet started. */
    PENDING,
    /** Started; the LLM call is in flight (or its tool calls are running). */
    RUNNING,
    /** Finished successfully; {@link SubagentResult#output()} is the model's final response. */
    COMPLETED,
    /** Errored — exception thrown during the LLM call or tool execution. */
    FAILED,
    /** Cancelled by an explicit {@link Subagent#cancel()} call before completion. */
    CANCELLED,
    /** Did not complete within the spec's timeout. */
    TIMED_OUT;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED || this == TIMED_OUT;
    }
}
