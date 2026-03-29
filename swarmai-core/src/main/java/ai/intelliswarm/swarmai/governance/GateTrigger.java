package ai.intelliswarm.swarmai.governance;

/**
 * Defines when an approval gate is triggered during workflow execution.
 */
public enum GateTrigger {

    /**
     * Gate is checked before a task begins execution.
     */
    BEFORE_TASK,

    /**
     * Gate is checked after a task completes execution.
     */
    AFTER_TASK,

    /**
     * Gate is checked before a generated skill is promoted to the registry.
     */
    BEFORE_SKILL_PROMOTION,

    /**
     * Gate is triggered when the workflow budget threshold is exceeded.
     */
    ON_BUDGET_WARNING,

    /**
     * Gate is triggered at the end of each iteration in an iterative process.
     */
    ON_ITERATION_COMPLETE
}
