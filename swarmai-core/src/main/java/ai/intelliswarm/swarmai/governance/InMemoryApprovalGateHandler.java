package ai.intelliswarm.swarmai.governance;

import ai.intelliswarm.swarmai.event.SwarmEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * In-memory implementation of {@link ApprovalGateHandler}.
 *
 * Uses CompletableFuture for the blocking/unblocking pattern:
 * - {@code requestApproval()} creates a request and blocks on a future until decided
 * - {@code approve()}/{@code reject()} complete the future, unblocking the workflow thread
 * - On timeout, the request is auto-approved or auto-rejected based on the gate's policy
 *
 * Thread-safe: all state is stored in ConcurrentHashMaps.
 */
public class InMemoryApprovalGateHandler implements ApprovalGateHandler {

    private static final Logger logger = LoggerFactory.getLogger(InMemoryApprovalGateHandler.class);

    private final ConcurrentHashMap<String, ApprovalRequest> requests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<ApprovalRequest>> pendingFutures = new ConcurrentHashMap<>();
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Creates a handler with optional event publishing.
     *
     * @param eventPublisher Spring event publisher (nullable - events will be skipped if null)
     */
    public InMemoryApprovalGateHandler(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override
    public ApprovalRequest requestApproval(ApprovalGate gate, GovernanceContext context) {
        // Build the approval request
        ApprovalRequest request = new ApprovalRequest(
                gate.gateId(),
                context.swarmId(),
                context.taskId(),
                context.tenantId(),
                "workflow",
                buildRequestContext(gate, context)
        );

        String requestId = request.getRequestId();
        requests.put(requestId, request);

        // Create a future that will be completed when approve/reject is called
        CompletableFuture<ApprovalRequest> future = new CompletableFuture<>();
        pendingFutures.put(requestId, future);

        logger.info("Approval requested: gate='{}', requestId='{}', swarmId='{}', taskId='{}'",
                gate.name(), requestId, context.swarmId(), context.taskId());

        // Publish APPROVAL_REQUESTED event
        publishEvent(request, "APPROVAL_REQUESTED",
                "Approval requested for gate: " + gate.name());

        try {
            // Block until approved, rejected, or timeout
            long timeoutMillis = gate.timeout().toMillis();
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return handleTimeout(request, gate);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Approval request interrupted: requestId='{}'", requestId);
            request.reject("SYSTEM", "Interrupted while waiting for approval");
            pendingFutures.remove(requestId);
            return request;
        } catch (Exception e) {
            logger.error("Error waiting for approval: requestId='{}'", requestId, e);
            request.reject("SYSTEM", "Error: " + e.getMessage());
            pendingFutures.remove(requestId);
            return request;
        }
    }

    @Override
    public void approve(String requestId, String approver, String reason) {
        ApprovalRequest request = requests.get(requestId);
        if (request == null) {
            logger.warn("Approval request not found: requestId='{}'", requestId);
            return;
        }

        if (request.approve(approver, reason)) {
            logger.info("Approval granted: requestId='{}', approver='{}', reason='{}'",
                    requestId, approver, reason);

            publishEvent(request, "APPROVAL_GRANTED",
                    "Approval granted by " + approver + (reason != null ? ": " + reason : ""));

            // Complete the future to unblock the waiting workflow thread
            CompletableFuture<ApprovalRequest> future = pendingFutures.remove(requestId);
            if (future != null) {
                future.complete(request);
            }
        } else {
            logger.warn("Cannot approve request '{}': already in status {}", requestId, request.getStatus());
        }
    }

    @Override
    public void reject(String requestId, String approver, String reason) {
        ApprovalRequest request = requests.get(requestId);
        if (request == null) {
            logger.warn("Approval request not found: requestId='{}'", requestId);
            return;
        }

        if (request.reject(approver, reason)) {
            logger.info("Approval rejected: requestId='{}', approver='{}', reason='{}'",
                    requestId, approver, reason);

            publishEvent(request, "APPROVAL_REJECTED",
                    "Approval rejected by " + approver + (reason != null ? ": " + reason : ""));

            // Complete the future to unblock the waiting workflow thread
            CompletableFuture<ApprovalRequest> future = pendingFutures.remove(requestId);
            if (future != null) {
                future.complete(request);
            }
        } else {
            logger.warn("Cannot reject request '{}': already in status {}", requestId, request.getStatus());
        }
    }

    @Override
    public List<ApprovalRequest> getPendingRequests() {
        return requests.values().stream()
                .filter(r -> r.getStatus() == ApprovalStatus.PENDING)
                .toList();
    }

    @Override
    public List<ApprovalRequest> getPendingRequests(String tenantId) {
        return requests.values().stream()
                .filter(r -> r.getStatus() == ApprovalStatus.PENDING)
                .filter(r -> tenantId != null && tenantId.equals(r.getTenantId()))
                .toList();
    }

    @Override
    public Optional<ApprovalRequest> getRequest(String requestId) {
        return Optional.ofNullable(requests.get(requestId));
    }

    // ============================================
    // Internal helpers
    // ============================================

    private ApprovalRequest handleTimeout(ApprovalRequest request, ApprovalGate gate) {
        boolean autoApprove = gate.policy().autoApproveOnTimeout();
        request.timeout(autoApprove);
        pendingFutures.remove(request.getRequestId());

        if (autoApprove) {
            logger.info("Approval auto-approved on timeout: requestId='{}', gate='{}'",
                    request.getRequestId(), gate.name());
        } else {
            logger.warn("Approval timed out and auto-rejected: requestId='{}', gate='{}'",
                    request.getRequestId(), gate.name());
        }

        publishEvent(request, "APPROVAL_TIMED_OUT",
                "Approval timed out for gate: " + gate.name()
                        + (autoApprove ? " (auto-approved)" : " (auto-rejected)"));

        return request;
    }

    private Map<String, Object> buildRequestContext(ApprovalGate gate, GovernanceContext context) {
        Map<String, Object> ctx = new HashMap<>(context.metadata());
        ctx.put("gateName", gate.name());
        ctx.put("gateDescription", gate.description());
        ctx.put("gateTrigger", gate.trigger().name());
        ctx.put("currentIteration", context.currentIteration());
        return ctx;
    }

    /**
     * Publishes a SwarmEvent if the event publisher is available.
     * Uses a string-based event type approach to avoid compile-time dependency
     * on enum values that may not yet exist in SwarmEvent.Type.
     */
    private void publishEvent(ApprovalRequest request, String eventType, String message) {
        if (eventPublisher == null) {
            return;
        }

        try {
            // Try to resolve the enum value; if it doesn't exist yet, log and skip
            SwarmEvent.Type type = SwarmEvent.Type.valueOf(eventType);
            Map<String, Object> metadata = Map.of(
                    "requestId", request.getRequestId(),
                    "gateId", request.getGateId(),
                    "status", request.getStatus().name(),
                    "tenantId", request.getTenantId() != null ? request.getTenantId() : ""
            );
            eventPublisher.publishEvent(new SwarmEvent(this, type, message, request.getSwarmId(), metadata));
        } catch (IllegalArgumentException e) {
            // Enum value doesn't exist yet - log at debug level and continue
            logger.debug("SwarmEvent.Type.{} not available, skipping event publication: {}",
                    eventType, message);
        }
    }
}
