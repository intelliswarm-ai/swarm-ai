package ai.intelliswarm.swarmai.state;

/**
 * Points in the workflow lifecycle where hooks can be registered.
 * Hooks provide a unified mechanism for cross-cutting concerns like
 * observability, governance, budget tracking, and custom logic.
 */
public enum HookPoint {
    /** Before the entire workflow starts */
    BEFORE_WORKFLOW,
    /** After the entire workflow completes */
    AFTER_WORKFLOW,
    /** Before each task executes */
    BEFORE_TASK,
    /** After each task completes */
    AFTER_TASK,
    /** Before a tool is invoked */
    BEFORE_TOOL,
    /** After a tool completes */
    AFTER_TOOL,
    /** When an error occurs during execution */
    ON_ERROR,
    /** When a checkpoint is saved */
    ON_CHECKPOINT
}
