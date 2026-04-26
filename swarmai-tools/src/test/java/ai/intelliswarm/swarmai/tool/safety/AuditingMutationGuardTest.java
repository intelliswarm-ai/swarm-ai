package ai.intelliswarm.swarmai.tool.safety;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AuditingMutationGuard Unit Tests")
class AuditingMutationGuardTest {

    private static MutationPlan plan() {
        return MutationPlan.builder()
                .toolName("windows_filesystem")
                .summary("Move 1 file from Desktop to Docs")
                .riskLevel(MutationPlan.RiskLevel.MEDIUM)
                .op("move", "/a.txt")
                .build();
    }

    @Test
    @DisplayName("approve flow writes a REQUEST entry, then a DECISION entry, then forwards the decision")
    void approveFlow(@TempDir Path tmp) throws Exception {
        AuditLogger audit = new AuditLogger(tmp);
        MutationGuard inner = p -> MutationGuard.Decision.approve("alice", "ok");
        AuditingMutationGuard auditing = new AuditingMutationGuard(inner, audit);

        MutationGuard.Decision d = auditing.check(plan());

        assertTrue(d.approved());
        List<Map<String, Object>> entries = audit.readToday();
        assertEquals(2, entries.size());
        assertEquals("REQUEST", entries.get(0).get("event"));
        assertEquals("DECISION", entries.get(1).get("event"));
        @SuppressWarnings("unchecked")
        Map<String, Object> decision = (Map<String, Object>) entries.get(1).get("decision");
        assertEquals(true, decision.get("approved"));
        assertEquals("alice", decision.get("by"));
    }

    @Test
    @DisplayName("reject flow still writes both entries with the rejector and reason")
    void rejectFlow(@TempDir Path tmp) throws Exception {
        AuditLogger audit = new AuditLogger(tmp);
        MutationGuard inner = p -> MutationGuard.Decision.reject("bob", "no thanks");
        AuditingMutationGuard auditing = new AuditingMutationGuard(inner, audit);

        MutationGuard.Decision d = auditing.check(plan());

        assertEquals(false, d.approved());
        List<Map<String, Object>> entries = audit.readToday();
        assertEquals(2, entries.size());
        assertNotNull(entries.get(0).get("plan"));
        @SuppressWarnings("unchecked")
        Map<String, Object> decision = (Map<String, Object>) entries.get(1).get("decision");
        assertEquals(false, decision.get("approved"));
        assertEquals("bob", decision.get("by"));
        assertEquals("no thanks", decision.get("reason"));
    }
}
