package ai.intelliswarm.swarmai.tool.safety;

import ai.intelliswarm.swarmai.governance.ApprovalGate;
import ai.intelliswarm.swarmai.governance.ApprovalGateHandler;
import ai.intelliswarm.swarmai.governance.ApprovalRequest;
import ai.intelliswarm.swarmai.governance.GateTrigger;
import ai.intelliswarm.swarmai.governance.GovernanceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("SupervisedMutationGuard Unit Tests")
class SupervisedMutationGuardTest {

    private ApprovalGateHandler handler;
    private SupervisedMutationGuard guard;

    @BeforeEach
    void setUp() {
        handler = mock(ApprovalGateHandler.class);
        guard = new SupervisedMutationGuard(handler, Duration.ofSeconds(10), "tenant-a");
    }

    private static MutationPlan samplePlan() {
        return MutationPlan.builder()
                .toolName("windows_filesystem")
                .summary("Move 1 file from Desktop to Desktop/Docs")
                .riskLevel(MutationPlan.RiskLevel.MEDIUM)
                .op("move", "C:/Users/me/Desktop/a.txt",
                        Map.of("to", "C:/Users/me/Desktop/Docs/a.txt"))
                .metadata("dryRun", false)
                .build();
    }

    private static ApprovalRequest pendingRequest() {
        return new ApprovalRequest("gate-1", "swarm-1", "task-1",
                "tenant-a", "test", Map.of());
    }

    @Test
    @DisplayName("approved request maps to Decision.approved=true with approver and reason preserved")
    void approvedRequestProducesApproveDecision() {
        ApprovalRequest req = pendingRequest();
        req.approve("alice", "ok");
        when(handler.requestApproval(any(), any())).thenReturn(req);

        MutationGuard.Decision decision = guard.check(samplePlan());

        assertTrue(decision.approved());
        assertEquals("alice", decision.decidedBy());
        assertEquals("ok", decision.reason());
    }

    @Test
    @DisplayName("rejected request maps to Decision.approved=false with rejector and reason")
    void rejectedRequestProducesRejectDecision() {
        ApprovalRequest req = pendingRequest();
        req.reject("bob", "looks risky");
        when(handler.requestApproval(any(), any())).thenReturn(req);

        MutationGuard.Decision decision = guard.check(samplePlan());

        assertFalse(decision.approved());
        assertEquals("bob", decision.decidedBy());
        assertEquals("looks risky", decision.reason());
    }

    @Test
    @DisplayName("timed-out request without auto-approve maps to a SYSTEM rejection")
    void timedOutWithoutAutoApproveIsRejected() {
        ApprovalRequest req = pendingRequest();
        req.timeout(false);
        when(handler.requestApproval(any(), any())).thenReturn(req);

        MutationGuard.Decision decision = guard.check(samplePlan());

        assertFalse(decision.approved());
        assertEquals("SYSTEM", decision.decidedBy());
        assertTrue(decision.reason().toLowerCase().contains("timeout"));
    }

    @Test
    @DisplayName("timed-out request with auto-approve maps to a SYSTEM approval")
    void timedOutWithAutoApproveIsApproved() {
        ApprovalRequest req = pendingRequest();
        req.timeout(true);
        when(handler.requestApproval(any(), any())).thenReturn(req);

        MutationGuard.Decision decision = guard.check(samplePlan());

        assertTrue(decision.approved());
        assertEquals("SYSTEM", decision.decidedBy());
        assertTrue(decision.reason().toLowerCase().contains("auto-approved"));
    }

    @Test
    @DisplayName("the gate sent to the handler carries the tool name, summary, BEFORE_TASK trigger and the configured timeout")
    void gateIsBuiltFromPlan() {
        ApprovalRequest req = pendingRequest();
        req.approve("alice", "ok");
        when(handler.requestApproval(any(), any())).thenReturn(req);

        guard.check(samplePlan());

        ArgumentCaptor<ApprovalGate> gateCap = ArgumentCaptor.forClass(ApprovalGate.class);
        ArgumentCaptor<GovernanceContext> ctxCap = ArgumentCaptor.forClass(GovernanceContext.class);
        verify(handler).requestApproval(gateCap.capture(), ctxCap.capture());

        ApprovalGate gate = gateCap.getValue();
        assertEquals("tool:windows_filesystem", gate.name());
        assertEquals("Move 1 file from Desktop to Desktop/Docs", gate.description());
        assertEquals(GateTrigger.BEFORE_TASK, gate.trigger());
        assertEquals(Duration.ofSeconds(10), gate.timeout());
        assertNotNull(gate.gateId());
    }

    @Test
    @DisplayName("the governance context carries tenant id, tool name, risk level, op count and op summaries")
    void contextCarriesPlanMetadata() {
        ApprovalRequest req = pendingRequest();
        req.approve("alice", "ok");
        when(handler.requestApproval(any(), any())).thenReturn(req);

        guard.check(samplePlan());

        ArgumentCaptor<GovernanceContext> ctxCap = ArgumentCaptor.forClass(GovernanceContext.class);
        verify(handler).requestApproval(any(), ctxCap.capture());
        GovernanceContext ctx = ctxCap.getValue();

        assertEquals("tenant-a", ctx.tenantId());
        assertEquals("windows_filesystem", ctx.taskId());

        Map<String, Object> meta = ctx.metadata();
        assertEquals("windows_filesystem", meta.get("tool"));
        assertEquals("MEDIUM", meta.get("riskLevel"));
        assertEquals(1, meta.get("opCount"));
        assertEquals("Move 1 file from Desktop to Desktop/Docs", meta.get("summary"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ops = (List<Map<String, Object>>) meta.get("ops");
        assertEquals(1, ops.size());
        assertEquals("move", ops.get(0).get("type"));
        assertEquals("C:/Users/me/Desktop/a.txt", ops.get(0).get("target"));

        @SuppressWarnings("unchecked")
        Map<String, Object> toolMeta = (Map<String, Object>) meta.get("toolMetadata");
        assertEquals(Boolean.FALSE, toolMeta.get("dryRun"));
    }

    @Test
    @DisplayName("null tenant id falls back to \"default\"")
    void nullTenantFallsBackToDefault() {
        SupervisedMutationGuard g = new SupervisedMutationGuard(handler, Duration.ofSeconds(1), null);
        ApprovalRequest req = pendingRequest();
        req.approve("a", "");
        when(handler.requestApproval(any(), any())).thenReturn(req);

        g.check(samplePlan());

        ArgumentCaptor<GovernanceContext> ctxCap = ArgumentCaptor.forClass(GovernanceContext.class);
        verify(handler).requestApproval(any(), ctxCap.capture());
        assertEquals("default", ctxCap.getValue().tenantId());
    }

    @Test
    @DisplayName("null plan throws NullPointerException without calling the handler")
    void nullPlanIsRejectedEarly() {
        assertThrows(NullPointerException.class, () -> guard.check(null));
    }
}
