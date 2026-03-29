package ai.intelliswarm.swarmai.governance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("InMemoryApprovalGateHandler Tests")
class InMemoryApprovalGateHandlerTest {

    private InMemoryApprovalGateHandler handler;
    private ApplicationEventPublisher mockPublisher;

    @BeforeEach
    void setUp() {
        // Use a mock publisher that does nothing (events may reference enum values not yet defined)
        mockPublisher = mock(ApplicationEventPublisher.class);
        handler = new InMemoryApprovalGateHandler(mockPublisher);
    }

    private ApprovalGate createGate(String name, GateTrigger trigger, Duration timeout, boolean autoApprove) {
        return ApprovalGate.builder()
                .name(name)
                .description("Test gate: " + name)
                .trigger(trigger)
                .timeout(timeout)
                .policy(new ApprovalPolicy(1, List.of(), autoApprove))
                .build();
    }

    private GovernanceContext createContext(String swarmId, String taskId, String tenantId) {
        return GovernanceContext.of(swarmId, taskId, tenantId);
    }

    @Nested
    @DisplayName("Approve unblocks waiting thread")
    class ApproveTests {

        @Test
        @DisplayName("approve() unblocks requestApproval() and returns APPROVED status")
        void approve_unblocksWaitingThread() throws Exception {
            ApprovalGate gate = createGate("test-gate", GateTrigger.BEFORE_TASK, Duration.ofMinutes(5), false);
            GovernanceContext context = createContext("swarm-1", "task-1", "tenant-1");

            AtomicReference<ApprovalRequest> resultRef = new AtomicReference<>();
            CountDownLatch requestCreated = new CountDownLatch(1);

            // Start requestApproval in a separate thread (it blocks)
            CompletableFuture<Void> asyncOp = CompletableFuture.runAsync(() -> {
                // Signal that the thread is about to block
                requestCreated.countDown();
                ApprovalRequest result = handler.requestApproval(gate, context);
                resultRef.set(result);
            });

            // Wait for the request to be created
            assertTrue(requestCreated.await(2, TimeUnit.SECONDS), "Request should be created");

            // Give the handler a moment to register the future
            Thread.sleep(100);

            // Find the pending request and approve it
            List<ApprovalRequest> pending = handler.getPendingRequests();
            assertFalse(pending.isEmpty(), "Should have at least one pending request");

            String requestId = pending.get(0).getRequestId();
            handler.approve(requestId, "admin", "Looks good");

            // Wait for the async operation to complete
            asyncOp.get(5, TimeUnit.SECONDS);

            ApprovalRequest result = resultRef.get();
            assertNotNull(result);
            assertEquals(ApprovalStatus.APPROVED, result.getStatus());
            assertEquals("admin", result.getDecidedBy());
            assertEquals("Looks good", result.getReason());
            assertTrue(result.isEffectivelyApproved());
        }

        @Test
        @DisplayName("approve() on already decided request is a no-op")
        void approve_alreadyDecided_isNoOp() throws Exception {
            ApprovalGate gate = createGate("test-gate", GateTrigger.BEFORE_TASK, Duration.ofMinutes(5), false);
            GovernanceContext context = createContext("swarm-1", "task-1", "tenant-1");

            CountDownLatch requestCreated = new CountDownLatch(1);

            CompletableFuture<Void> asyncOp = CompletableFuture.runAsync(() -> {
                requestCreated.countDown();
                handler.requestApproval(gate, context);
            });

            assertTrue(requestCreated.await(2, TimeUnit.SECONDS));
            Thread.sleep(100);

            List<ApprovalRequest> pending = handler.getPendingRequests();
            String requestId = pending.get(0).getRequestId();

            handler.approve(requestId, "admin", "First approval");
            asyncOp.get(5, TimeUnit.SECONDS);

            // Second approve should be a no-op
            handler.approve(requestId, "other-admin", "Second approval");

            Optional<ApprovalRequest> request = handler.getRequest(requestId);
            assertTrue(request.isPresent());
            assertEquals("admin", request.get().getDecidedBy());
        }
    }

    @Nested
    @DisplayName("Reject unblocks waiting thread")
    class RejectTests {

        @Test
        @DisplayName("reject() unblocks requestApproval() and returns REJECTED status")
        void reject_unblocksWaitingThread() throws Exception {
            ApprovalGate gate = createGate("review-gate", GateTrigger.AFTER_TASK, Duration.ofMinutes(5), false);
            GovernanceContext context = createContext("swarm-2", "task-2", "tenant-1");

            AtomicReference<ApprovalRequest> resultRef = new AtomicReference<>();
            CountDownLatch requestCreated = new CountDownLatch(1);

            CompletableFuture<Void> asyncOp = CompletableFuture.runAsync(() -> {
                requestCreated.countDown();
                ApprovalRequest result = handler.requestApproval(gate, context);
                resultRef.set(result);
            });

            assertTrue(requestCreated.await(2, TimeUnit.SECONDS));
            Thread.sleep(100);

            List<ApprovalRequest> pending = handler.getPendingRequests();
            assertFalse(pending.isEmpty());

            String requestId = pending.get(0).getRequestId();
            handler.reject(requestId, "reviewer", "Quality too low");

            asyncOp.get(5, TimeUnit.SECONDS);

            ApprovalRequest result = resultRef.get();
            assertNotNull(result);
            assertEquals(ApprovalStatus.REJECTED, result.getStatus());
            assertEquals("reviewer", result.getDecidedBy());
            assertEquals("Quality too low", result.getReason());
            assertFalse(result.isEffectivelyApproved());
        }
    }

    @Nested
    @DisplayName("Timeout handling")
    class TimeoutTests {

        @Test
        @DisplayName("timeout with auto-approve returns TIMED_OUT status and is effectively approved")
        void timeout_withAutoApprove_isEffectivelyApproved() {
            ApprovalGate gate = createGate("auto-gate", GateTrigger.BEFORE_TASK,
                    Duration.ofMillis(200), true);
            GovernanceContext context = createContext("swarm-3", "task-3", "tenant-2");

            ApprovalRequest result = handler.requestApproval(gate, context);

            assertEquals(ApprovalStatus.TIMED_OUT, result.getStatus());
            assertTrue(result.isEffectivelyApproved(),
                    "Should be effectively approved due to auto-approve policy");
            assertEquals("SYSTEM", result.getDecidedBy());
            assertTrue(result.getReason().contains("Auto-approved"));
        }

        @Test
        @DisplayName("timeout without auto-approve returns TIMED_OUT status and is not approved")
        void timeout_withoutAutoApprove_isNotApproved() {
            ApprovalGate gate = createGate("strict-gate", GateTrigger.BEFORE_TASK,
                    Duration.ofMillis(200), false);
            GovernanceContext context = createContext("swarm-4", "task-4", "tenant-2");

            ApprovalRequest result = handler.requestApproval(gate, context);

            assertEquals(ApprovalStatus.TIMED_OUT, result.getStatus());
            assertFalse(result.isEffectivelyApproved(),
                    "Should not be approved when auto-approve is disabled");
            assertEquals("SYSTEM", result.getDecidedBy());
            assertTrue(result.getReason().contains("Rejected"));
        }
    }

    @Nested
    @DisplayName("Pending requests listing")
    class PendingRequestsTests {

        @Test
        @DisplayName("returns all pending requests across tenants")
        void getPendingRequests_returnsAllPending() throws Exception {
            ApprovalGate gate = createGate("list-gate", GateTrigger.BEFORE_TASK,
                    Duration.ofMinutes(5), false);

            // Create multiple pending requests from different threads
            CountDownLatch allCreated = new CountDownLatch(3);

            for (int i = 0; i < 3; i++) {
                final int idx = i;
                CompletableFuture.runAsync(() -> {
                    allCreated.countDown();
                    GovernanceContext ctx = createContext("swarm-" + idx, "task-" + idx, "tenant-" + idx);
                    handler.requestApproval(gate, ctx);
                });
            }

            assertTrue(allCreated.await(2, TimeUnit.SECONDS));
            Thread.sleep(200);

            List<ApprovalRequest> pending = handler.getPendingRequests();
            assertEquals(3, pending.size(), "Should have 3 pending requests");
        }

        @Test
        @DisplayName("approved requests are no longer pending")
        void getPendingRequests_excludesApproved() throws Exception {
            ApprovalGate gate = createGate("filter-gate", GateTrigger.BEFORE_TASK,
                    Duration.ofMinutes(5), false);

            CountDownLatch created = new CountDownLatch(2);

            CompletableFuture.runAsync(() -> {
                created.countDown();
                handler.requestApproval(gate, createContext("swarm-a", "task-a", "tenant-1"));
            });

            CompletableFuture.runAsync(() -> {
                created.countDown();
                handler.requestApproval(gate, createContext("swarm-b", "task-b", "tenant-1"));
            });

            assertTrue(created.await(2, TimeUnit.SECONDS));
            Thread.sleep(200);

            List<ApprovalRequest> pending = handler.getPendingRequests();
            assertEquals(2, pending.size());

            // Approve one
            handler.approve(pending.get(0).getRequestId(), "admin", "ok");
            Thread.sleep(50);

            List<ApprovalRequest> stillPending = handler.getPendingRequests();
            assertEquals(1, stillPending.size());
        }
    }

    @Nested
    @DisplayName("Tenant-filtered pending requests")
    class TenantFilteredTests {

        @Test
        @DisplayName("filters pending requests by tenant ID")
        void getPendingRequests_filtersByTenant() throws Exception {
            ApprovalGate gate = createGate("tenant-gate", GateTrigger.BEFORE_TASK,
                    Duration.ofMinutes(5), false);

            CountDownLatch created = new CountDownLatch(3);

            CompletableFuture.runAsync(() -> {
                created.countDown();
                handler.requestApproval(gate, createContext("swarm-1", "task-1", "acme-corp"));
            });

            CompletableFuture.runAsync(() -> {
                created.countDown();
                handler.requestApproval(gate, createContext("swarm-2", "task-2", "acme-corp"));
            });

            CompletableFuture.runAsync(() -> {
                created.countDown();
                handler.requestApproval(gate, createContext("swarm-3", "task-3", "other-corp"));
            });

            assertTrue(created.await(2, TimeUnit.SECONDS));
            Thread.sleep(200);

            List<ApprovalRequest> acmeRequests = handler.getPendingRequests("acme-corp");
            assertEquals(2, acmeRequests.size(), "Should have 2 requests for acme-corp");

            List<ApprovalRequest> otherRequests = handler.getPendingRequests("other-corp");
            assertEquals(1, otherRequests.size(), "Should have 1 request for other-corp");

            List<ApprovalRequest> noRequests = handler.getPendingRequests("nonexistent");
            assertEquals(0, noRequests.size(), "Should have 0 requests for nonexistent tenant");
        }
    }

    @Nested
    @DisplayName("Request retrieval")
    class RequestRetrievalTests {

        @Test
        @DisplayName("getRequest returns empty for nonexistent ID")
        void getRequest_nonexistent_returnsEmpty() {
            Optional<ApprovalRequest> result = handler.getRequest("nonexistent-id");
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("getRequest returns the request after timeout")
        void getRequest_afterTimeout_returnsRequest() {
            ApprovalGate gate = createGate("retrieve-gate", GateTrigger.BEFORE_TASK,
                    Duration.ofMillis(100), true);
            GovernanceContext context = createContext("swarm-r", "task-r", "tenant-r");

            ApprovalRequest result = handler.requestApproval(gate, context);

            Optional<ApprovalRequest> retrieved = handler.getRequest(result.getRequestId());
            assertTrue(retrieved.isPresent());
            assertEquals(result.getRequestId(), retrieved.get().getRequestId());
            assertEquals(ApprovalStatus.TIMED_OUT, retrieved.get().getStatus());
        }
    }

    @Nested
    @DisplayName("Null event publisher")
    class NullPublisherTests {

        @Test
        @DisplayName("works correctly with null event publisher")
        void requestApproval_withNullPublisher_works() {
            InMemoryApprovalGateHandler nullHandler = new InMemoryApprovalGateHandler(null);
            ApprovalGate gate = createGate("no-pub-gate", GateTrigger.BEFORE_TASK,
                    Duration.ofMillis(100), true);
            GovernanceContext context = createContext("swarm-np", "task-np", "tenant-np");

            // Should not throw even without an event publisher
            ApprovalRequest result = nullHandler.requestApproval(gate, context);
            assertNotNull(result);
            assertEquals(ApprovalStatus.TIMED_OUT, result.getStatus());
            assertTrue(result.isEffectivelyApproved());
        }
    }
}
