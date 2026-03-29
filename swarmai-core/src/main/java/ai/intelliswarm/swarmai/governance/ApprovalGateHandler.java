package ai.intelliswarm.swarmai.governance;

import java.util.List;
import java.util.Optional;

/**
 * Interface for handling approval gate requests in the governance pipeline.
 * Implementations manage the lifecycle of approval requests: creation, blocking,
 * decision (approve/reject), and timeout handling.
 */
public interface ApprovalGateHandler {

    /**
     * Creates an approval request for the given gate and blocks the calling thread
     * until the request is approved, rejected, or times out.
     *
     * @param gate    the approval gate that was triggered
     * @param context the workflow state at the time the gate was triggered
     * @return the completed approval request with its final status
     */
    ApprovalRequest requestApproval(ApprovalGate gate, GovernanceContext context);

    /**
     * Approves a pending approval request.
     *
     * @param requestId the ID of the request to approve
     * @param approver  the identity of the approver
     * @param reason    optional reason for the approval
     */
    void approve(String requestId, String approver, String reason);

    /**
     * Rejects a pending approval request.
     *
     * @param requestId the ID of the request to reject
     * @param approver  the identity of the rejector
     * @param reason    optional reason for the rejection
     */
    void reject(String requestId, String approver, String reason);

    /**
     * Returns all currently pending approval requests across all tenants.
     *
     * @return list of pending requests
     */
    List<ApprovalRequest> getPendingRequests();

    /**
     * Returns all currently pending approval requests for a specific tenant.
     *
     * @param tenantId the tenant to filter by
     * @return list of pending requests for the tenant
     */
    List<ApprovalRequest> getPendingRequests(String tenantId);

    /**
     * Retrieves a specific approval request by ID.
     *
     * @param requestId the request ID to look up
     * @return the request if found
     */
    Optional<ApprovalRequest> getRequest(String requestId);
}
