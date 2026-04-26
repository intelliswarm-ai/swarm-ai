package ai.intelliswarm.swarmai.tool.safety;

import ai.intelliswarm.swarmai.governance.ApprovalGate;
import ai.intelliswarm.swarmai.governance.ApprovalPolicy;
import ai.intelliswarm.swarmai.governance.ApprovalRequest;
import ai.intelliswarm.swarmai.governance.ApprovalStatus;
import ai.intelliswarm.swarmai.governance.GateTrigger;
import ai.intelliswarm.swarmai.governance.GovernanceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ConsoleApprovalGateHandler Unit Tests")
class ConsoleApprovalGateHandlerTest {

    private ConsoleApprovalGateHandler handler;
    private ByteArrayOutputStream outBuf;

    @AfterEach
    void tearDown() {
        if (handler != null) handler.shutdown();
    }

    private ConsoleApprovalGateHandler newHandler(Reader in) {
        outBuf = new ByteArrayOutputStream();
        handler = new ConsoleApprovalGateHandler(in, new PrintStream(outBuf, true, StandardCharsets.UTF_8));
        return handler;
    }

    private static ApprovalGate gate(Duration timeout, boolean autoApproveOnTimeout) {
        return ApprovalGate.builder()
                .name("tool:windows_filesystem")
                .description("Move 1 file from Desktop to Desktop/Docs")
                .trigger(GateTrigger.BEFORE_TASK)
                .timeout(timeout)
                .policy(new ApprovalPolicy(1, List.of(), autoApproveOnTimeout))
                .build();
    }

    private static GovernanceContext ctx() {
        return new GovernanceContext("tool-mutation", "windows_filesystem",
                "tenant-a", 0,
                Map.of(
                        "tool", "windows_filesystem",
                        "riskLevel", "MEDIUM",
                        "summary", "Move 1 file from Desktop to Desktop/Docs",
                        "ops", List.of(Map.of("type", "move", "target", "/a.txt",
                                "details", Map.of("to", "/Docs/a.txt")))));
    }

    @Test
    @DisplayName("'y' input approves the request and identifies the approver as 'console'")
    void yesApproves() {
        ApprovalRequest req = newHandler(new StringReader("y\n"))
                .requestApproval(gate(Duration.ofSeconds(2), false), ctx());

        assertEquals(ApprovalStatus.APPROVED, req.getStatus());
        assertEquals("console", req.getDecidedBy());
        assertTrue(outBuf.toString(StandardCharsets.UTF_8).contains("APPROVED"));
    }

    @Test
    @DisplayName("'no' input rejects the request")
    void noRejects() {
        ApprovalRequest req = newHandler(new StringReader("no\n"))
                .requestApproval(gate(Duration.ofSeconds(2), false), ctx());

        assertEquals(ApprovalStatus.REJECTED, req.getStatus());
        assertEquals("console", req.getDecidedBy());
    }

    @Test
    @DisplayName("unrecognised input is fail-closed and rejects with explanatory reason")
    void unrecognisedRejects() {
        ApprovalRequest req = newHandler(new StringReader("maybe\n"))
                .requestApproval(gate(Duration.ofSeconds(2), false), ctx());

        assertEquals(ApprovalStatus.REJECTED, req.getStatus());
        assertTrue(req.getReason().toLowerCase().contains("unrecognised"));
    }

    @Test
    @DisplayName("EOF on stdin is fail-closed and rejects with SYSTEM as approver")
    void eofRejects() {
        ApprovalRequest req = newHandler(new StringReader(""))
                .requestApproval(gate(Duration.ofSeconds(2), false), ctx());

        assertEquals(ApprovalStatus.REJECTED, req.getStatus());
        assertEquals("SYSTEM", req.getDecidedBy());
        assertTrue(req.getReason().toLowerCase().contains("eof")
                || req.getReason().toLowerCase().contains("closed"));
    }

    @Test
    @DisplayName("timeout without auto-approve marks the request TIMED_OUT and isEffectivelyApproved=false")
    void timeoutWithoutAutoApprove() throws IOException {
        try (HangingReader hr = new HangingReader()) {
            ApprovalRequest req = newHandler(hr)
                    .requestApproval(gate(Duration.ofMillis(150), false), ctx());

            assertEquals(ApprovalStatus.TIMED_OUT, req.getStatus());
            assertFalse(req.isEffectivelyApproved());
        }
    }

    @Test
    @DisplayName("timeout with auto-approve marks TIMED_OUT and isEffectivelyApproved=true")
    void timeoutWithAutoApprove() throws IOException {
        try (HangingReader hr = new HangingReader()) {
            ApprovalRequest req = newHandler(hr)
                    .requestApproval(gate(Duration.ofMillis(150), true), ctx());

            assertEquals(ApprovalStatus.TIMED_OUT, req.getStatus());
            assertTrue(req.isEffectivelyApproved());
        }
    }

    @Test
    @DisplayName("the printed prompt includes tool, risk level, summary, ops and the [y/N] question")
    void promptContainsKeyFields() {
        newHandler(new StringReader("y\n"))
                .requestApproval(gate(Duration.ofSeconds(2), false), ctx());

        String printed = outBuf.toString(StandardCharsets.UTF_8);
        assertTrue(printed.contains("SwarmAI Approval Request"), printed);
        assertTrue(printed.contains("windows_filesystem"), printed);
        assertTrue(printed.contains("MEDIUM"), printed);
        assertTrue(printed.contains("Move 1 file from Desktop to Desktop/Docs"), printed);
        assertTrue(printed.contains("move /a.txt"), printed);
        assertTrue(printed.contains("[y/N]"), printed);
    }

    @Test
    @DisplayName("two concurrent requests are serialised so prompts do not interleave")
    void concurrentRequestsSerialised() throws Exception {
        // Two lines, one decision per request.
        Reader in = new StringReader("y\nn\n");
        ConsoleApprovalGateHandler h = newHandler(in);

        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<ApprovalRequest> f1 = CompletableFuture.supplyAsync(() -> {
            await(start);
            return h.requestApproval(gate(Duration.ofSeconds(2), false), ctx());
        });
        CompletableFuture<ApprovalRequest> f2 = CompletableFuture.supplyAsync(() -> {
            await(start);
            return h.requestApproval(gate(Duration.ofSeconds(2), false), ctx());
        });
        start.countDown();

        ApprovalRequest r1 = f1.get(5, TimeUnit.SECONDS);
        ApprovalRequest r2 = f2.get(5, TimeUnit.SECONDS);

        // One was approved, the other rejected — order is undefined since
        // the lock acquisition order between the two threads is not deterministic.
        long approved = List.of(r1, r2).stream()
                .filter(r -> r.getStatus() == ApprovalStatus.APPROVED).count();
        long rejected = List.of(r1, r2).stream()
                .filter(r -> r.getStatus() == ApprovalStatus.REJECTED).count();
        assertEquals(1, approved);
        assertEquals(1, rejected);
    }

    @Test
    @DisplayName("getRequest / getPendingRequests reflect the request lifecycle")
    void lookupsReflectLifecycle() {
        ConsoleApprovalGateHandler h = newHandler(new StringReader("y\n"));
        ApprovalRequest req = h.requestApproval(gate(Duration.ofSeconds(2), false), ctx());

        assertTrue(h.getRequest(req.getRequestId()).isPresent());
        // Now decided, so no longer pending.
        assertEquals(0, h.getPendingRequests().size());
        assertEquals(0, h.getPendingRequests("tenant-a").size());
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** Reader whose read calls block until {@link #close()} is called or the calling thread is interrupted. */
    private static final class HangingReader extends Reader {
        private final CountDownLatch latch = new CountDownLatch(1);

        @Override
        public int read(char[] cbuf, int off, int len) throws IOException {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
            return -1;
        }

        @Override
        public void close() {
            latch.countDown();
        }
    }
}
