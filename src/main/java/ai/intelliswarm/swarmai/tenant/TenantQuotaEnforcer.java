package ai.intelliswarm.swarmai.tenant;

/**
 * Enforces resource quotas for tenants within the SwarmAI framework.
 * Implementations track active resource usage and reject operations
 * that would exceed configured limits.
 */
public interface TenantQuotaEnforcer {

    /**
     * Checks whether the tenant can start a new workflow.
     *
     * @param tenantId the tenant ID to check
     * @throws TenantQuotaExceededException if the tenant has reached the maximum concurrent workflows
     */
    void checkWorkflowQuota(String tenantId) throws TenantQuotaExceededException;

    /**
     * Checks whether the tenant can register additional skills.
     *
     * @param tenantId     the tenant ID to check
     * @param currentCount the current number of registered skills
     * @throws TenantQuotaExceededException if adding a skill would exceed the maximum
     */
    void checkSkillQuota(String tenantId, int currentCount) throws TenantQuotaExceededException;

    /**
     * Records that a workflow has started for the given tenant.
     *
     * @param tenantId the tenant ID
     */
    void recordWorkflowStart(String tenantId);

    /**
     * Records that a workflow has ended for the given tenant.
     *
     * @param tenantId the tenant ID
     */
    void recordWorkflowEnd(String tenantId);

    /**
     * Returns the number of currently active workflows for the given tenant.
     *
     * @param tenantId the tenant ID
     * @return the active workflow count
     */
    int getActiveWorkflowCount(String tenantId);
}
