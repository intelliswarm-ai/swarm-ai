package ai.intelliswarm.swarmai.governance;

/**
 * Exception thrown when a governance gate rejects a workflow operation.
 * This exception signals that the workflow should not proceed through the gate.
 */
public class GovernanceException extends RuntimeException {

    private final String gateId;
    private final String requestId;
    private final ApprovalStatus status;

    public GovernanceException(String message, String gateId, String requestId, ApprovalStatus status) {
        super(message);
        this.gateId = gateId;
        this.requestId = requestId;
        this.status = status;
    }

    public GovernanceException(String message, String gateId, String requestId, ApprovalStatus status, Throwable cause) {
        super(message, cause);
        this.gateId = gateId;
        this.requestId = requestId;
        this.status = status;
    }

    public String getGateId() {
        return gateId;
    }

    public String getRequestId() {
        return requestId;
    }

    public ApprovalStatus getStatus() {
        return status;
    }
}
