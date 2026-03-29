package ai.intelliswarm.swarmai.governance;

import java.time.Duration;
import java.util.UUID;

/**
 * Represents an approval gate in the workflow governance pipeline.
 * Gates pause workflow execution until an authorized approver grants or rejects the request.
 *
 * @param gateId      unique identifier for this gate (UUID by default)
 * @param name        human-readable name of the gate
 * @param description describes what this gate protects or validates
 * @param trigger     when this gate activates during workflow execution
 * @param timeout     how long to wait for approval before timing out (default 30 minutes)
 * @param policy      the approval requirements for this gate
 */
public record ApprovalGate(
        String gateId,
        String name,
        String description,
        GateTrigger trigger,
        Duration timeout,
        ApprovalPolicy policy
) {

    /**
     * Compact constructor with defaults.
     */
    public ApprovalGate {
        if (gateId == null || gateId.isBlank()) {
            gateId = UUID.randomUUID().toString();
        }
        if (timeout == null) {
            timeout = Duration.ofMinutes(30);
        }
        if (policy == null) {
            policy = new ApprovalPolicy();
        }
    }

    /**
     * Builder for constructing ApprovalGate instances.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String gateId;
        private String name;
        private String description;
        private GateTrigger trigger;
        private Duration timeout;
        private ApprovalPolicy policy;

        public Builder gateId(String gateId) {
            this.gateId = gateId;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder trigger(GateTrigger trigger) {
            this.trigger = trigger;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder policy(ApprovalPolicy policy) {
            this.policy = policy;
            return this;
        }

        public ApprovalGate build() {
            return new ApprovalGate(gateId, name, description, trigger, timeout, policy);
        }
    }
}
