package ai.intelliswarm.swarmai.enterprise.governance;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Enterprise governance configuration properties.
 */
@ConfigurationProperties(prefix = "swarmai.enterprise.governance")
public class GovernanceProperties {

    private boolean enabled = true;
    private long defaultGateTimeoutMinutes = 30;
    private boolean autoApproveOnTimeout = false;
    private SkillPromotionPolicy skillPromotion = new SkillPromotionPolicy();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long getDefaultGateTimeoutMinutes() { return defaultGateTimeoutMinutes; }
    public void setDefaultGateTimeoutMinutes(long v) { this.defaultGateTimeoutMinutes = v; }

    public boolean isAutoApproveOnTimeout() { return autoApproveOnTimeout; }
    public void setAutoApproveOnTimeout(boolean v) { this.autoApproveOnTimeout = v; }

    public SkillPromotionPolicy getSkillPromotion() { return skillPromotion; }
    public void setSkillPromotion(SkillPromotionPolicy v) { this.skillPromotion = v; }

    public static class SkillPromotionPolicy {
        private boolean requiresApproval = false;
        private int requiredApprovals = 1;

        public boolean isRequiresApproval() { return requiresApproval; }
        public void setRequiresApproval(boolean v) { this.requiresApproval = v; }
        public int getRequiredApprovals() { return requiredApprovals; }
        public void setRequiredApprovals(int v) { this.requiredApprovals = v; }
    }
}
