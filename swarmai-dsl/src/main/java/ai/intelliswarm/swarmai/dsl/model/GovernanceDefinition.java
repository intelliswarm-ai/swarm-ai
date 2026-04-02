package ai.intelliswarm.swarmai.dsl.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * YAML definition for governance configuration.
 *
 * <pre>{@code
 * governance:
 *   approvalGates:
 *     - name: "Review Gate"
 *       description: "Requires review before proceeding"
 *       trigger: AFTER_TASK
 *       timeoutMinutes: 30
 * }</pre>
 */
public class GovernanceDefinition {

    @JsonProperty("approvalGates")
    private List<ApprovalGateDefinition> approvalGates = new ArrayList<>();

    public List<ApprovalGateDefinition> getApprovalGates() { return approvalGates; }
    public void setApprovalGates(List<ApprovalGateDefinition> approvalGates) { this.approvalGates = approvalGates; }
}
