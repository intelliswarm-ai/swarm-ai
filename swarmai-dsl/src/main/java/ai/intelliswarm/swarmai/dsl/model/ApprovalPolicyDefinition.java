package ai.intelliswarm.swarmai.dsl.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * YAML definition for an approval policy on a governance gate.
 *
 * <pre>{@code
 * policy:
 *   requiredApprovals: 2
 *   approverRoles:
 *     - tech-lead
 *     - security-reviewer
 *   autoApproveOnTimeout: false
 * }</pre>
 */
public class ApprovalPolicyDefinition {

    @JsonProperty("requiredApprovals")
    private Integer requiredApprovals;

    @JsonProperty("approverRoles")
    private List<String> approverRoles = new ArrayList<>();

    @JsonProperty("autoApproveOnTimeout")
    private Boolean autoApproveOnTimeout;

    // --- Getters & Setters ---

    public Integer getRequiredApprovals() { return requiredApprovals; }
    public void setRequiredApprovals(Integer requiredApprovals) { this.requiredApprovals = requiredApprovals; }

    public List<String> getApproverRoles() { return approverRoles; }
    public void setApproverRoles(List<String> approverRoles) { this.approverRoles = approverRoles; }

    public Boolean getAutoApproveOnTimeout() { return autoApproveOnTimeout; }
    public void setAutoApproveOnTimeout(Boolean autoApproveOnTimeout) { this.autoApproveOnTimeout = autoApproveOnTimeout; }
}
