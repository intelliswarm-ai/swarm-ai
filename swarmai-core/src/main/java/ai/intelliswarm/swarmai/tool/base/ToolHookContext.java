package ai.intelliswarm.swarmai.tool.base;

import java.util.Map;

/**
 * Rich context passed to {@link ToolHook} implementations before and after tool execution.
 * Pre-hooks receive a context with {@code output = null}; post-hooks receive the full context.
 *
 * @param toolName        name of the tool being invoked
 * @param inputParams     parameters passed to the tool
 * @param output          tool output (null for pre-hooks)
 * @param executionTimeMs execution duration in milliseconds (0 for pre-hooks)
 * @param error           exception thrown during execution (null if successful)
 * @param agentId         the agent invoking the tool
 * @param workflowId      the workflow this execution belongs to (may be null)
 */
public record ToolHookContext(
        String toolName,
        Map<String, Object> inputParams,
        String output,
        long executionTimeMs,
        Throwable error,
        String agentId,
        String workflowId
) {
    /**
     * Creates a pre-execution context (before tool runs).
     */
    public static ToolHookContext before(String toolName, Map<String, Object> inputParams,
                                         String agentId, String workflowId) {
        return new ToolHookContext(toolName, inputParams, null, 0L, null, agentId, workflowId);
    }

    /**
     * Creates a post-execution context (after tool runs successfully).
     */
    public static ToolHookContext after(String toolName, Map<String, Object> inputParams,
                                        String output, long executionTimeMs,
                                        String agentId, String workflowId) {
        return new ToolHookContext(toolName, inputParams, output, executionTimeMs, null, agentId, workflowId);
    }

    /**
     * Creates an error context (after tool throws).
     */
    public static ToolHookContext error(String toolName, Map<String, Object> inputParams,
                                        long executionTimeMs, Throwable error,
                                        String agentId, String workflowId) {
        return new ToolHookContext(toolName, inputParams, null, executionTimeMs, error, agentId, workflowId);
    }

    /**
     * Returns true if this is a pre-execution context.
     */
    public boolean isBeforeExecution() {
        return output == null && error == null && executionTimeMs == 0L;
    }

    /**
     * Returns true if the tool execution resulted in an error.
     */
    public boolean hasError() {
        return error != null;
    }
}
