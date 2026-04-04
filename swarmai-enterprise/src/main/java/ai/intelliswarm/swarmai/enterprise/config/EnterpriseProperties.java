package ai.intelliswarm.swarmai.enterprise.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for SwarmAI Enterprise features.
 *
 * <pre>
 * swarmai:
 *   enterprise:
 *     license-key: "BASE64_ENCODED_LICENSE"
 *     tenant:
 *       enabled: true
 *       default-max-workflows: 10
 *       default-max-skills: 100
 *     governance:
 *       enabled: true
 *     deep-rl:
 *       enabled: false
 * </pre>
 */
@ConfigurationProperties(prefix = "swarmai.enterprise")
public class EnterpriseProperties {

    private String licenseKey;
    private TenantConfig tenant = new TenantConfig();
    private GovernanceConfig governance = new GovernanceConfig();
    private DeepRlConfig deepRl = new DeepRlConfig();

    public String getLicenseKey() {
        return licenseKey;
    }

    public void setLicenseKey(String licenseKey) {
        this.licenseKey = licenseKey;
    }

    public TenantConfig getTenant() {
        return tenant;
    }

    public void setTenant(TenantConfig tenant) {
        this.tenant = tenant;
    }

    public GovernanceConfig getGovernance() {
        return governance;
    }

    public void setGovernance(GovernanceConfig governance) {
        this.governance = governance;
    }

    public DeepRlConfig getDeepRl() {
        return deepRl;
    }

    public void setDeepRl(DeepRlConfig deepRl) {
        this.deepRl = deepRl;
    }

    public static class TenantConfig {
        private boolean enabled = true;
        private int defaultMaxWorkflows = 10;
        private int defaultMaxSkills = 100;
        private int defaultMaxMemoryEntries = 10000;
        private long defaultMaxTokenBudget = 1_000_000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getDefaultMaxWorkflows() { return defaultMaxWorkflows; }
        public void setDefaultMaxWorkflows(int v) { this.defaultMaxWorkflows = v; }
        public int getDefaultMaxSkills() { return defaultMaxSkills; }
        public void setDefaultMaxSkills(int v) { this.defaultMaxSkills = v; }
        public int getDefaultMaxMemoryEntries() { return defaultMaxMemoryEntries; }
        public void setDefaultMaxMemoryEntries(int v) { this.defaultMaxMemoryEntries = v; }
        public long getDefaultMaxTokenBudget() { return defaultMaxTokenBudget; }
        public void setDefaultMaxTokenBudget(long v) { this.defaultMaxTokenBudget = v; }
    }

    public static class GovernanceConfig {
        private boolean enabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class DeepRlConfig {
        private boolean enabled = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
