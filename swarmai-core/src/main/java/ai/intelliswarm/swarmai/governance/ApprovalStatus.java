package ai.intelliswarm.swarmai.governance;

/**
 * Represents the lifecycle status of an approval request.
 */
public enum ApprovalStatus {

    /**
     * The request has been created and is awaiting a decision.
     */
    PENDING,

    /**
     * The request has been approved by an authorized approver.
     */
    APPROVED,

    /**
     * The request has been rejected by an authorized approver.
     */
    REJECTED,

    /**
     * The request timed out before a decision was made.
     * The outcome (approve or reject) depends on the gate's ApprovalPolicy.
     */
    TIMED_OUT
}
