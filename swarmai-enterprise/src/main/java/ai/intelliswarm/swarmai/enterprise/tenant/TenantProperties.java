package ai.intelliswarm.swarmai.enterprise.tenant;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration properties for SwarmAI Enterprise multi-tenant features.
 *
 * <pre>
 * swarmai:
 *   enterprise:
 *     tenant:
 *       enabled: true
 *       default-quota:
 *         max-concurrent-workflows: 10
 *         max-skills: 100
 *       quotas:
 *         premium-tenant:
 *           max-concurrent-workflows: 50
 * </pre>
 */
@ConfigurationProperties(prefix = "swarmai.enterprise.tenant")
public class TenantProperties {

    private boolean enabled = true;
    private Map<String, QuotaConfig> quotas = new HashMap<>();
    private QuotaConfig defaultQuota = new QuotaConfig();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Map<String, QuotaConfig> getQuotas() { return quotas; }
    public void setQuotas(Map<String, QuotaConfig> quotas) { this.quotas = quotas; }

    public QuotaConfig getDefaultQuota() { return defaultQuota; }
    public void setDefaultQuota(QuotaConfig defaultQuota) { this.defaultQuota = defaultQuota; }

    public static class QuotaConfig {
        private int maxConcurrentWorkflows = 10;
        private int maxSkills = 100;
        private int maxMemoryEntries = 10_000;
        private long maxTokenBudget = 1_000_000;

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
