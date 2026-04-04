package ai.intelliswarm.swarmai.exception;

/**
 * Thrown when an agent fails during task execution.
 */
public class AgentExecutionException extends SwarmException {

    private final String agentId;
    private final String taskId;

    public AgentExecutionException(String message, Throwable cause, String agentId, String taskId,
                                    String swarmId, String correlationId) {
        super(message, cause, swarmId, correlationId);
        this.agentId = agentId;
        this.taskId = taskId;
    }

    public AgentExecutionException(String message, String agentId, String taskId) {
        this(message, null, agentId, taskId, null, null);
    }

    public AgentExecutionException(String message, Throwable cause, String agentId, String taskId) {
        this(message, cause, agentId, taskId, null, null);
    }

    public String getAgentId() {
        return agentId;
    }

    public String getTaskId() {
        return taskId;
    }
}
