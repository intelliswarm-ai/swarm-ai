package ai.intelliswarm.swarmai.state.hooks;

import ai.intelliswarm.swarmai.state.AgentState;
import ai.intelliswarm.swarmai.state.HookContext;
import ai.intelliswarm.swarmai.state.SwarmHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Built-in SwarmHook that logs lifecycle events with an optional custom message.
 */
public class LoggingSwarmHook implements SwarmHook<AgentState> {

    private static final Logger logger = LoggerFactory.getLogger(LoggingSwarmHook.class);

    private final String message;

    public LoggingSwarmHook(String message) {
        this.message = message;
    }

    public LoggingSwarmHook() {
        this(null);
    }

    @Override
    public AgentState apply(HookContext<AgentState> context) {
        String taskInfo = context.taskId() != null ? " task='" + context.taskId() + "'" : "";
        String toolInfo = context.toolName() != null ? " tool='" + context.toolName() + "'" : "";
        String errorInfo = context.error() != null ? " error='" + context.error().getMessage() + "'" : "";
        String customMsg = message != null ? " — " + message : "";

        logger.info("[HOOK] {} workflow='{}'{}{}{}{}",
                context.hookPoint(), context.workflowId(),
                taskInfo, toolInfo, errorInfo, customMsg);

        return context.state();
    }
}
