package ai.intelliswarm.swarmai.tenant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration properties for SwarmAI multi-tenant features.
 * Bound via {@code @EnableConfigurationProperties} in {@link TenantAutoConfiguration}.
 *
 * <p>Example configuration:
 * <pre>
 * swarmai:
 *   tenant:
 *     enabled: true
 *     default-quota:
 *       max-concurrent-workflows: 10
 *       max-skills: 100
 *       max-memory-entries: 10000
 *       max-token-budget: 1000000
 *     quotas:
 *       premium-tenant:
 *         max-concurrent-workflows: 50
 *         max-skills: 500
 * </pre>
 */
@ConfigurationProperties(prefix = "swarmai.tenant")
public class TenantProperties {

    /**
     * Master switch for tenant isolation features.
     */
    private boolean enabled = false;

    /**
     * Per-tenant quota overrides, keyed by tenant ID.
     */
    private Map<String, QuotaConfig> quotas = new HashMap<>();

    /**
     * Default quota applied when no tenant-specific quota is configured.
     */
    private QuotaConfig defaultQuota = new QuotaConfig();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, QuotaConfig> getQuotas() {
        return quotas;
    }

    public void setQuotas(Map<String, QuotaConfig> quotas) {
        this.quotas = quotas;
    }

    public QuotaConfig getDefaultQuota() {
        return defaultQuota;
    }

    public void setDefaultQuota(QuotaConfig defaultQuota) {
        this.defaultQuota = defaultQuota;
    }

    /**
     * Quota configuration for a single tenant or the default.
     */
    public static class QuotaConfig {

        private int maxConcurrentWorkflows = 10;
        private int maxSkills = 100;
        private int maxMemoryEntries = 10_000;
        private long maxTokenBudget = 1_000_000;

        public int getMaxConcurrentWorkflows() {
            return maxConcurrentWorkflows;
        }

        public void setMaxConcurrentWorkflows(int maxConcurrentWorkflows) {
            this.maxConcurrentWorkflows = maxConcurrentWorkflows;
        }

        public int getMaxSkills() {
            return maxSkills;
        }

        public void setMaxSkills(int maxSkills) {
            this.maxSkills = maxSkills;
        }

        public int getMaxMemoryEntries() {
            return maxMemoryEntries;
        }

        public void setMaxMemoryEntries(int maxMemoryEntries) {
            this.maxMemoryEntries = maxMemoryEntries;
        }

        public long getMaxTokenBudget() {
            return maxTokenBudget;
        }

        public void setMaxTokenBudget(long maxTokenBudget) {
            this.maxTokenBudget = maxTokenBudget;
        }
    }
}
