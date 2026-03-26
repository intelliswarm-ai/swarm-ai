package ai.intelliswarm.swarmai.governance;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Mutable, thread-safe representation of an approval request.
 * Created when a workflow hits an approval gate and updated when
 * the request is approved, rejected, or times out.
 */
public class ApprovalRequest {

    private final String requestId;
    private final String gateId;
    private final String swarmId;
    private final String taskId;
    private final String tenantId;
    private final String requestedBy;
    private final Instant requestedAt;
    private final Map<String, Object> context;

    private volatile ApprovalStatus status;
    private volatile String decidedBy;
    private volatile Instant decidedAt;
    private volatile String reason;

    private final Object lock = new Object();

    public ApprovalRequest(String gateId, String swarmId, String taskId,
                           String tenantId, String requestedBy,
                           Map<String, Object> context) {
        this.requestId = UUID.randomUUID().toString();
        this.gateId = gateId;
        this.swarmId = swarmId;
        this.taskId = taskId;
        this.tenantId = tenantId;
        this.requestedBy = requestedBy;
        this.requestedAt = Instant.now();
        this.context = context != null ? new HashMap<>(context) : new HashMap<>();
        this.status = ApprovalStatus.PENDING;
    }

    // ============================================
    // Thread-safe status transitions
    // ============================================

    /**
     * Approves this request. Only transitions from PENDING.
     *
     * @param approver the identity of the approver
     * @param reason   optional reason for the approval
     * @return true if the transition was successful, false if already decided
     */
    public boolean approve(String approver, String reason) {
        synchronized (lock) {
            if (status != ApprovalStatus.PENDING) {
                return false;
            }
            this.status = ApprovalStatus.APPROVED;
            this.decidedBy = approver;
            this.decidedAt = Instant.now();
            this.reason = reason;
            return true;
        }
    }

    /**
     * Rejects this request. Only transitions from PENDING.
     *
     * @param approver the identity of the rejector
     * @param reason   optional reason for the rejection
     * @return true if the transition was successful, false if already decided
     */
    public boolean reject(String approver, String reason) {
        synchronized (lock) {
            if (status != ApprovalStatus.PENDING) {
                return false;
            }
            this.status = ApprovalStatus.REJECTED;
            this.decidedBy = approver;
            this.decidedAt = Instant.now();
            this.reason = reason;
            return true;
        }
    }

    /**
     * Marks this request as timed out. Only transitions from PENDING.
     *
     * @param autoApproved true if the policy dictates auto-approval on timeout
     * @return true if the transition was successful, false if already decided
     */
    public boolean timeout(boolean autoApproved) {
        synchronized (lock) {
            if (status != ApprovalStatus.PENDING) {
                return false;
            }
            this.status = ApprovalStatus.TIMED_OUT;
            this.decidedBy = "SYSTEM";
            this.decidedAt = Instant.now();
            this.reason = autoApproved
                    ? "Auto-approved on timeout per policy"
                    : "Rejected on timeout per policy";
            return true;
        }
    }

    /**
     * Returns whether this request has been decided (approved, rejected, or timed out).
     */
    public boolean isDecided() {
        return status != ApprovalStatus.PENDING;
    }

    /**
     * Returns whether this request was effectively approved (either directly or via auto-approve on timeout).
     */
    public boolean isEffectivelyApproved() {
        synchronized (lock) {
            if (status == ApprovalStatus.APPROVED) {
                return true;
            }
            if (status == ApprovalStatus.TIMED_OUT && reason != null && reason.contains("Auto-approved")) {
                return true;
            }
            return false;
        }
    }

    // ============================================
    // Getters (volatile fields are safe to read without synchronization)
    // ============================================

    public String getRequestId() {
        return requestId;
    }

    public String getGateId() {
        return gateId;
    }

    public String getSwarmId() {
        return swarmId;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Map<String, Object> getContext() {
        return Collections.unmodifiableMap(context);
    }

    public ApprovalStatus getStatus() {
        return status;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getReason() {
        return reason;
    }

    @Override
    public String toString() {
        return "ApprovalRequest{" +
                "requestId='" + requestId + '\'' +
                ", gateId='" + gateId + '\'' +
                ", swarmId='" + swarmId + '\'' +
                ", status=" + status +
                ", tenantId='" + tenantId + '\'' +
                '}';
    }
}
