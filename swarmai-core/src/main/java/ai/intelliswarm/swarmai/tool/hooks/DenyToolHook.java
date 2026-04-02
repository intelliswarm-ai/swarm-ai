package ai.intelliswarm.swarmai.tool.hooks;

import ai.intelliswarm.swarmai.tool.base.ToolHook;
import ai.intelliswarm.swarmai.tool.base.ToolHookContext;
import ai.intelliswarm.swarmai.tool.base.ToolHookResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * Built-in tool hook that blocks specific tools from being invoked.
 * Returns a DENY result with a descriptive message when a blocked tool is called.
 */
public class DenyToolHook implements ToolHook {

    private static final Logger logger = LoggerFactory.getLogger(DenyToolHook.class);

    private final Set<String> deniedTools;

    public DenyToolHook(Set<String> deniedTools) {
        this.deniedTools = Set.copyOf(deniedTools);
    }

    @Override
    public ToolHookResult beforeToolUse(ToolHookContext context) {
        if (deniedTools.contains(context.toolName())) {
            String msg = String.format("Tool '%s' is denied by policy", context.toolName());
            logger.warn("[DENY] {}", msg);
            return ToolHookResult.deny(msg);
        }
        return ToolHookResult.allow();
    }
}
