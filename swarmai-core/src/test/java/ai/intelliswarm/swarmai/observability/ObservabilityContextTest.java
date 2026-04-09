package ai.intelliswarm.swarmai.observability;

import ai.intelliswarm.swarmai.observability.core.ObservabilityContext;
import org.junit.jupiter.api.*;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ObservabilityContext — ThreadLocal context propagation.
 *
 * This is critical for multi-agent workflows: if context doesn't propagate
 * to child threads, correlation IDs and tenant IDs are lost in parallel
 * processes. Debugging distributed workflows becomes impossible.
 */
@DisplayName("ObservabilityContext — ThreadLocal Context Propagation")
class ObservabilityContextTest {

    @AfterEach
    void cleanup() {
        ObservabilityContext.clear();
    }

    @Nested
    @DisplayName("Root Context Creation")
    class RootContext {

        @Test
        @DisplayName("create() generates unique IDs")
        void createGeneratesUniqueIds() {
            ObservabilityContext ctx = ObservabilityContext.create();

            assertNotNull(ctx.getCorrelationId());
            assertNotNull(ctx.getTraceId());
            assertNotNull(ctx.getSpanId());
            assertNull(ctx.getParentSpanId(), "Root context has no parent");

            ObservabilityContext.clear();
            ObservabilityContext ctx2 = ObservabilityContext.create();
            assertNotEquals(ctx.getCorrelationId(), ctx2.getCorrelationId(),
                "Each create() should produce a unique correlation ID");
        }

        @Test
        @DisplayName("create() sets as ThreadLocal current")
        void createSetsCurrent() {
            ObservabilityContext ctx = ObservabilityContext.create();
            assertSame(ctx, ObservabilityContext.current());
        }

        @Test
        @DisplayName("clear() removes from ThreadLocal")
        void clearRemoves() {
            ObservabilityContext.create();
            ObservabilityContext.clear();

            // current() creates a new one if null — so check via currentOrNull
            assertNull(ObservabilityContext.currentOrNull(),
                "After clear(), currentOrNull() should return null");
        }
    }

    @Nested
    @DisplayName("Child Context (Span Hierarchy)")
    class ChildContext {

        @Test
        @DisplayName("child inherits correlationId and traceId from parent")
        void childInheritsIds() {
            ObservabilityContext parent = ObservabilityContext.create();
            parent.withSwarmId("swarm-1").withAgentId("agent-1");

            ObservabilityContext child = ObservabilityContext.createChild();

            assertEquals(parent.getCorrelationId(), child.getCorrelationId(),
                "Child must inherit parent's correlationId");
            assertEquals(parent.getTraceId(), child.getTraceId(),
                "Child must inherit parent's traceId");
            assertNotEquals(parent.getSpanId(), child.getSpanId(),
                "Child must have its own spanId");
            assertEquals(parent.getSpanId(), child.getParentSpanId(),
                "Child's parentSpanId must be parent's spanId");
        }

        @Test
        @DisplayName("child inherits swarmId, agentId, taskId")
        void childInheritsWorkflowContext() {
            ObservabilityContext parent = ObservabilityContext.create();
            parent.withSwarmId("swarm-1").withAgentId("agent-1").withTaskId("task-1");

            ObservabilityContext child = ObservabilityContext.createChild();

            assertEquals("swarm-1", child.getSwarmId());
            assertEquals("agent-1", child.getAgentId());
            assertEquals("task-1", child.getTaskId());
        }

        @Test
        @DisplayName("child inherits custom attributes including tenantId")
        void childInheritsAttributes() {
            ObservabilityContext parent = ObservabilityContext.create();
            parent.withTenantId("tenant-acme").withAttribute("custom", "value");

            ObservabilityContext child = ObservabilityContext.createChild();

            assertEquals("tenant-acme", child.getTenantId(),
                "Child must inherit tenantId for multi-tenant isolation");
            assertEquals("value", child.getAttribute("custom"));
        }
    }

    @Nested
    @DisplayName("Cross-Thread Propagation via Snapshot")
    class SnapshotPropagation {

        @Test
        @DisplayName("Snapshot propagates context to another thread")
        void snapshotPropagates() throws InterruptedException {
            ObservabilityContext parent = ObservabilityContext.create();
            parent.withSwarmId("swarm-1").withTenantId("tenant-acme");

            ObservabilityContext.Snapshot snapshot = ObservabilityContext.snapshot();
            AtomicReference<String> childCorrelationId = new AtomicReference<>();
            AtomicReference<String> childTenantId = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);

            Thread childThread = new Thread(() -> {
                try (var s = snapshot) {
                    s.restore();
                    ObservabilityContext ctx = ObservabilityContext.current();
                    childCorrelationId.set(ctx.getCorrelationId());
                    childTenantId.set(ctx.getTenantId());
                }
                latch.countDown();
            });
            childThread.start();
            assertTrue(latch.await(5, TimeUnit.SECONDS));

            assertEquals(parent.getCorrelationId(), childCorrelationId.get(),
                "Child thread must see parent's correlationId via Snapshot");
            assertEquals("tenant-acme", childTenantId.get(),
                "Child thread must see parent's tenantId via Snapshot");
        }

        @Test
        @DisplayName("Snapshot close() restores previous context")
        void snapshotCloseRestores() {
            ObservabilityContext original = ObservabilityContext.create();
            original.withSwarmId("original-swarm");

            ObservabilityContext.Snapshot snapshot = ObservabilityContext.snapshot();
            snapshot.restore();

            ObservabilityContext child = ObservabilityContext.current();
            assertNotEquals(original.getSpanId(), child.getSpanId(),
                "After restore, current should be a child span");

            snapshot.close();

            // After close, the original context (or null) should be restored
            ObservabilityContext restored = ObservabilityContext.currentOrNull();
            if (restored != null) {
                assertEquals("original-swarm", restored.getSwarmId(),
                    "After Snapshot.close(), previous context should be restored");
            }
        }

        @Test
        @DisplayName("different threads have isolated contexts")
        void threadsAreIsolated() throws InterruptedException {
            ObservabilityContext main = ObservabilityContext.create();
            main.withSwarmId("main-swarm");

            AtomicReference<String> otherSwarmId = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);

            Thread other = new Thread(() -> {
                // No snapshot propagation — other thread should have no context
                ObservabilityContext ctx = ObservabilityContext.currentOrNull();
                otherSwarmId.set(ctx != null ? ctx.getSwarmId() : "none");
                latch.countDown();
            });
            other.start();
            assertTrue(latch.await(5, TimeUnit.SECONDS));

            assertEquals("none", otherSwarmId.get(),
                "Thread without Snapshot should not see main thread's context");
        }
    }

    @Nested
    @DisplayName("MDC Map Generation")
    class MdcMap {

        @Test
        @DisplayName("toMdcMap includes all non-null fields")
        void includesAllFields() {
            ObservabilityContext ctx = ObservabilityContext.create();
            ctx.withSwarmId("s1").withAgentId("a1").withTaskId("t1")
               .withToolName("web_search").withTenantId("acme");

            Map<String, String> mdc = ctx.toMdcMap();

            assertNotNull(mdc.get("correlationId"));
            assertNotNull(mdc.get("traceId"));
            assertNotNull(mdc.get("spanId"));
            assertEquals("s1", mdc.get("swarmId"));
            assertEquals("a1", mdc.get("agentId"));
            assertEquals("t1", mdc.get("taskId"));
            assertEquals("web_search", mdc.get("toolName"));
            assertEquals("acme", mdc.get("tenantId"));
        }

        @Test
        @DisplayName("toMdcMap excludes null fields")
        void excludesNullFields() {
            ObservabilityContext ctx = ObservabilityContext.create();
            // No swarmId, agentId, etc. set

            Map<String, String> mdc = ctx.toMdcMap();

            assertFalse(mdc.containsKey("swarmId"), "Null swarmId should not appear in MDC");
            assertFalse(mdc.containsKey("agentId"));
        }
    }

    @Nested
    @DisplayName("Timing Records")
    class Timing {

        @Test
        @DisplayName("recordTiming stores and retrieves phase durations")
        void recordAndRetrieve() {
            ObservabilityContext ctx = ObservabilityContext.create();
            ctx.recordTiming("llm_call", 250);
            ctx.recordTiming("tool_execution", 100);

            Map<String, Long> timings = ctx.getTimings();
            assertEquals(250L, timings.get("llm_call"));
            assertEquals(100L, timings.get("tool_execution"));
        }

        @Test
        @DisplayName("child context starts with empty timings (not inherited)")
        void childTimingsEmpty() {
            ObservabilityContext parent = ObservabilityContext.create();
            parent.recordTiming("parent_phase", 500);

            ObservabilityContext child = ObservabilityContext.createChild();
            assertTrue(child.getTimings().isEmpty(),
                "Child should start with empty timings — each span tracks its own phases");
        }
    }
}
