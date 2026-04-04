package ai.intelliswarm.swarmai.exception;

import ai.intelliswarm.swarmai.tool.base.PermissionLevel;

/**
 * Thrown when an agent attempts to use a tool above its permission level.
 */
public class PermissionDeniedException extends SwarmException {

    private final String toolName;
    private final PermissionLevel required;
    private final PermissionLevel agentLevel;

    public PermissionDeniedException(String toolName, PermissionLevel required, PermissionLevel agentLevel) {
        super(String.format("Permission denied: tool '%s' requires %s but agent has %s",
                toolName, required, agentLevel));
        this.toolName = toolName;
        this.required = required;
        this.agentLevel = agentLevel;
    }

    public String getToolName() {
        return toolName;
    }

    public PermissionLevel getRequired() {
        return required;
    }

    public PermissionLevel getAgentLevel() {
        return agentLevel;
    }
}
