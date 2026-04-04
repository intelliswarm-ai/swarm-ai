package ai.intelliswarm.swarmai.exception;

/**
 * Thrown when a tool fails during execution.
 */
public class ToolExecutionException extends SwarmException {

    private final String toolName;
    private final String agentId;

    public ToolExecutionException(String message, Throwable cause, String toolName, String agentId,
                                   String swarmId, String correlationId) {
        super(message, cause, swarmId, correlationId);
        this.toolName = toolName;
        this.agentId = agentId;
    }

    public ToolExecutionException(String message, Throwable cause, String toolName) {
        this(message, cause, toolName, null, null, null);
    }

    public String getToolName() {
        return toolName;
    }

    public String getAgentId() {
        return agentId;
    }
}
