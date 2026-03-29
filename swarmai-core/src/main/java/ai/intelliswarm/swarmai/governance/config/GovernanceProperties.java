package ai.intelliswarm.swarmai.governance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the SwarmAI Workflow Governance Engine.
 * Bound via {@code swarmai.governance.*} in application.yml or application.properties.
 */
@ConfigurationProperties(prefix = "swarmai.governance")
public class GovernanceProperties {

    /**
     * Master switch for the governance engine. When false, no governance beans are created.
     */
    private boolean enabled = false;

    /**
     * Default timeout in minutes for approval gates that don't specify their own timeout.
     */
    private long defaultGateTimeoutMinutes = 30;

    /**
     * Default behavior when a gate times out. When true, gates auto-approve on timeout.
     */
    private boolean autoApproveOnTimeout = false;

    /**
     * Policy for skill promotion governance.
     */
    private SkillPromotionPolicy skillPromotion = new SkillPromotionPolicy();

    // ============================================
    // Getters and Setters
    // ============================================

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getDefaultGateTimeoutMinutes() {
        return defaultGateTimeoutMinutes;
    }

    public void setDefaultGateTimeoutMinutes(long defaultGateTimeoutMinutes) {
        this.defaultGateTimeoutMinutes = defaultGateTimeoutMinutes;
    }

    public boolean isAutoApproveOnTimeout() {
        return autoApproveOnTimeout;
    }

    public void setAutoApproveOnTimeout(boolean autoApproveOnTimeout) {
        this.autoApproveOnTimeout = autoApproveOnTimeout;
    }

    public SkillPromotionPolicy getSkillPromotion() {
        return skillPromotion;
    }

    public void setSkillPromotion(SkillPromotionPolicy skillPromotion) {
        this.skillPromotion = skillPromotion;
    }

    /**
     * Nested configuration for skill promotion governance.
     */
    public static class SkillPromotionPolicy {

        /**
         * Whether skill promotions require approval through a governance gate.
         */
        private boolean requiresApproval = false;

        /**
         * Number of approvals required for skill promotion.
         */
        private int requiredApprovals = 1;

        public boolean isRequiresApproval() {
            return requiresApproval;
        }

        public void setRequiresApproval(boolean requiresApproval) {
            this.requiresApproval = requiresApproval;
        }

        public int getRequiredApprovals() {
            return requiredApprovals;
        }

        public void setRequiredApprovals(int requiredApprovals) {
            this.requiredApprovals = requiredApprovals;
        }
    }
}
