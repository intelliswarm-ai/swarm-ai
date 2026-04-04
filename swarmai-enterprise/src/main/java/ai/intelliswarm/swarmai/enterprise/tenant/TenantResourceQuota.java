package ai.intelliswarm.swarmai.enterprise.tenant;

/**
 * Immutable resource quota definition for a single tenant.
 * Controls the maximum resources a tenant can consume within the SwarmAI framework.
 *
 * @param tenantId               the tenant this quota applies to
 * @param maxConcurrentWorkflows maximum number of workflows running simultaneously
 * @param maxSkills              maximum number of skills the tenant can register
 * @param maxMemoryEntries       maximum number of memory entries the tenant can store
 * @param maxTokenBudget         maximum token budget for LLM calls
 */
public record TenantResourceQuota(
        String tenantId,
        int maxConcurrentWorkflows,
        int maxSkills,
        int maxMemoryEntries,
        long maxTokenBudget
) {

    public static Builder builder(String tenantId) {
        return new Builder(tenantId);
    }

    public static final class Builder {

        private final String tenantId;
        private int maxConcurrentWorkflows = 10;
        private int maxSkills = 100;
        private int maxMemoryEntries = 10_000;
        private long maxTokenBudget = 1_000_000;

        private Builder(String tenantId) {
            this.tenantId = tenantId;
        }

        public Builder maxConcurrentWorkflows(int maxConcurrentWorkflows) {
            this.maxConcurrentWorkflows = maxConcurrentWorkflows;
            return this;
        }

        public Builder maxSkills(int maxSkills) {
            this.maxSkills = maxSkills;
            return this;
        }

        public Builder maxMemoryEntries(int maxMemoryEntries) {
            this.maxMemoryEntries = maxMemoryEntries;
            return this;
        }

        public Builder maxTokenBudget(long maxTokenBudget) {
            this.maxTokenBudget = maxTokenBudget;
            return this;
        }

        public TenantResourceQuota build() {
            return new TenantResourceQuota(
                    tenantId,
                    maxConcurrentWorkflows,
                    maxSkills,
                    maxMemoryEntries,
                    maxTokenBudget
            );
        }
    }
}
