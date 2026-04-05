package ai.intelliswarm.swarmai.dsl.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * YAML definition for multi-tenant configuration.
 *
 * <pre>{@code
 * tenant:
 *   id: "tenant-acme"
 *   quota:
 *     maxConcurrentWorkflows: 10
 *     maxSkills: 100
 *     maxMemoryEntries: 10000
 *     maxTokenBudget: 1000000
 * }</pre>
 */
public class TenantDefinition {

    private String id;
    private QuotaDefinition quota;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public QuotaDefinition getQuota() { return quota; }
    public void setQuota(QuotaDefinition quota) { this.quota = quota; }

    public static class QuotaDefinition {
        @JsonProperty("maxConcurrentWorkflows")
        private int maxConcurrentWorkflows = 10;

        @JsonProperty("maxSkills")
        private int maxSkills = 100;

        @JsonProperty("maxMemoryEntries")
        private int maxMemoryEntries = 10000;

        @JsonProperty("maxTokenBudget")
        private long maxTokenBudget = 1000000;

        public int getMaxConcurrentWorkflows() { return maxConcurrentWorkflows; }
        public void setMaxConcurrentWorkflows(int v) { this.maxConcurrentWorkflows = v; }

        public int getMaxSkills() { return maxSkills; }
        public void setMaxSkills(int v) { this.maxSkills = v; }

        public int getMaxMemoryEntries() { return maxMemoryEntries; }
        public void setMaxMemoryEntries(int v) { this.maxMemoryEntries = v; }

        public long getMaxTokenBudget() { return maxTokenBudget; }
        public void setMaxTokenBudget(long v) { this.maxTokenBudget = v; }
    }
}
