package ai.intelliswarm.swarmai.tool.hooks;

import ai.intelliswarm.swarmai.tool.base.ToolHook;
import ai.intelliswarm.swarmai.tool.base.ToolHookContext;
import ai.intelliswarm.swarmai.tool.base.ToolHookResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Built-in tool hook that logs every tool invocation for audit purposes.
 * Logs tool name, agent, parameters (pre-hook) and execution time, status (post-hook).
 */
public class AuditToolHook implements ToolHook {

    private static final Logger logger = LoggerFactory.getLogger(AuditToolHook.class);

    @Override
    public ToolHookResult beforeToolUse(ToolHookContext context) {
        logger.info("[AUDIT] Tool '{}' invoked by agent '{}' | params: {}",
                context.toolName(), context.agentId(), context.inputParams());
        return ToolHookResult.allow();
    }

    @Override
    public ToolHookResult afterToolUse(ToolHookContext context) {
        if (context.hasError()) {
            logger.warn("[AUDIT] Tool '{}' FAILED after {}ms | error: {}",
                    context.toolName(), context.executionTimeMs(),
                    context.error().getMessage());
        } else {
            int outputLen = context.output() != null ? context.output().length() : 0;
            logger.info("[AUDIT] Tool '{}' completed in {}ms | output length: {} chars",
                    context.toolName(), context.executionTimeMs(), outputLen);
        }
        return ToolHookResult.allow();
    }
}
