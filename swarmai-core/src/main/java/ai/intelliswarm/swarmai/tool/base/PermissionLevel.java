package ai.intelliswarm.swarmai.tool.base;

/**
 * Permission level required to use a tool.
 * Ordered from least to most privileged. An agent's permission mode
 * determines the maximum level of tools it can invoke.
 *
 * <p>Usage:
 * <pre>{@code
 * // Tool declares its level
 * public PermissionLevel getPermissionLevel() {
 *     return PermissionLevel.WORKSPACE_WRITE;
 * }
 *
 * // Agent restricts what it can access
 * Agent.builder()
 *     .permissionMode(PermissionLevel.WORKSPACE_WRITE)
 *     .build();
 * }</pre>
 */
public enum PermissionLevel {

    /**
     * Read-only operations: search, query, fetch.
     * Safe for exploratory/research agents.
     */
    READ_ONLY,

    /**
     * Can modify files, databases, or workspace state.
     * Appropriate for builder/executor agents.
     */
    WORKSPACE_WRITE,

    /**
     * Potentially destructive or irreversible operations:
     * shell commands, network calls, deletions.
     */
    DANGEROUS,

    /**
     * Requires explicit approval (via governance gates) before execution.
     * Used for production deployments, external API calls with side effects, etc.
     */
    REQUIRES_APPROVAL;

    /**
     * Returns true if this level is permitted by the given agent permission mode.
     * A tool is permitted if its level is at or below the agent's mode.
     */
    public boolean isPermittedBy(PermissionLevel agentMode) {
        if (agentMode == null) {
            return true; // no restriction
        }
        return this.ordinal() <= agentMode.ordinal();
    }
}
