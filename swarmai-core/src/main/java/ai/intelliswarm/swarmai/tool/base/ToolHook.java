package ai.intelliswarm.swarmai.tool.base;

/**
 * Intercepts tool execution at the agent level with pre/post hooks.
 * Hooks wrap every individual tool call, enabling:
 * <ul>
 *   <li>Permission enforcement — deny tools at runtime</li>
 *   <li>Audit logging — record every tool invocation</li>
 *   <li>Rate limiting — throttle expensive tool calls</li>
 *   <li>Output filtering — sanitize or transform tool output</li>
 *   <li>Cost tracking — record per-tool execution costs</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * ToolHook auditHook = new ToolHook() {
 *     @Override
 *     public ToolHookResult beforeToolUse(ToolHookContext ctx) {
 *         logger.info("Tool {} called with {}", ctx.toolName(), ctx.inputParams());
 *         return ToolHookResult.allow();
 *     }
 *
 *     @Override
 *     public ToolHookResult afterToolUse(ToolHookContext ctx) {
 *         logger.info("Tool {} completed in {} ms", ctx.toolName(), ctx.executionTimeMs());
 *         return ToolHookResult.allow();
 *     }
 * };
 *
 * Agent.builder()
 *     .toolHook(auditHook)
 *     .build();
 * }</pre>
 */
public interface ToolHook {

    /**
     * Called before a tool is executed. Return {@link ToolHookResult#deny(String)}
     * to block execution, or {@link ToolHookResult#allow()} to proceed.
     *
     * @param context pre-execution context (output and executionTimeMs will be null/0)
     * @return hook result controlling whether execution proceeds
     */
    default ToolHookResult beforeToolUse(ToolHookContext context) {
        return ToolHookResult.allow();
    }

    /**
     * Called after a tool has executed (or thrown an error). Return
     * {@link ToolHookResult#withModifiedOutput(String)} to replace the output.
     *
     * @param context post-execution context with output, timing, and possible error
     * @return hook result, optionally with modified output
     */
    default ToolHookResult afterToolUse(ToolHookContext context) {
        return ToolHookResult.allow();
    }
}
