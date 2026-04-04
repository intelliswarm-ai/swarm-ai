package ai.intelliswarm.swarmai.enterprise.security;

/**
 * Defines the granular permissions that control access to SwarmAI enterprise
 * operations. Permissions are grouped into {@link Role}s; the RBAC enforcer
 * checks whether a user's role contains the required permission before
 * allowing the operation to proceed.
 *
 * @see Role
 * @see RbacEnforcer
 */
public enum Permission {

    /** Execute a workflow (start, resume, cancel). */
    WORKFLOW_EXECUTE,

    /** View workflow definitions, history, and status. */
    WORKFLOW_VIEW,

    /** Create, update, deprecate, or delete skills. */
    SKILL_MANAGE,

    /** Manage budget policies, thresholds, and allocations. */
    BUDGET_MANAGE,

    /** Manage tenant configuration and resource quotas. */
    TENANT_MANAGE,

    /** Register, configure, or remove tools and MCP adapters. */
    TOOL_MANAGE
}
