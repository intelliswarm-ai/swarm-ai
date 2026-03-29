package ai.intelliswarm.swarmai.governance;

import java.util.List;

/**
 * Defines the approval requirements for a governance gate.
 *
 * @param requiredApprovals  number of approvals needed to pass the gate (default 1)
 * @param approverRoles      roles allowed to approve; empty list means anyone can approve
 * @param autoApproveOnTimeout whether to automatically approve when the gate times out
 */
public record ApprovalPolicy(
        int requiredApprovals,
        List<String> approverRoles,
        boolean autoApproveOnTimeout
) {

    /**
     * Creates a policy with sensible defaults: 1 approval required,
     * no role restrictions, no auto-approve on timeout.
     */
    public ApprovalPolicy() {
        this(1, List.of(), false);
    }

    /**
     * Compact constructor that defensively copies the approver roles list.
     */
    public ApprovalPolicy {
        approverRoles = approverRoles != null ? List.copyOf(approverRoles) : List.of();
    }
}
