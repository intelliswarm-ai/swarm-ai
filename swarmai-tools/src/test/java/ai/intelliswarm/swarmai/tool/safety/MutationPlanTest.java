package ai.intelliswarm.swarmai.tool.safety;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MutationPlan Unit Tests")
class MutationPlanTest {

    @Test
    @DisplayName("builder produces a plan with summary, tool name, ops and risk level")
    void builderHappyPath() {
        MutationPlan plan = MutationPlan.builder()
                .toolName("windows_filesystem")
                .summary("Move 2 files from Desktop to Desktop/Docs")
                .riskLevel(MutationPlan.RiskLevel.MEDIUM)
                .op("move", "C:/Users/me/Desktop/a.txt",
                        Map.of("to", "C:/Users/me/Desktop/Docs/a.txt"))
                .op("move", "C:/Users/me/Desktop/b.txt",
                        Map.of("to", "C:/Users/me/Desktop/Docs/b.txt"))
                .metadata("dryRun", false)
                .build();

        assertEquals("windows_filesystem", plan.toolName());
        assertEquals("Move 2 files from Desktop to Desktop/Docs", plan.summary());
        assertEquals(MutationPlan.RiskLevel.MEDIUM, plan.riskLevel());
        assertEquals(2, plan.opCount());
        assertEquals("move", plan.ops().get(0).type());
        assertEquals(Boolean.FALSE, plan.metadata().get("dryRun"));
    }

    @Test
    @DisplayName("default risk level is MEDIUM when not set on the builder")
    void defaultRiskLevelIsMedium() {
        MutationPlan plan = MutationPlan.builder()
                .toolName("t")
                .summary("s")
                .build();

        assertEquals(MutationPlan.RiskLevel.MEDIUM, plan.riskLevel());
    }

    @Test
    @DisplayName("builder rejects missing tool name and missing summary")
    void builderValidatesRequiredFields() {
        assertThrows(IllegalStateException.class, () -> MutationPlan.builder()
                .summary("s").build());
        assertThrows(IllegalStateException.class, () -> MutationPlan.builder()
                .toolName("t").build());
    }

    @Test
    @DisplayName("ops and metadata returned by getters are immutable")
    void collectionsAreImmutable() {
        MutationPlan plan = MutationPlan.builder()
                .toolName("t")
                .summary("s")
                .op("delete", "a")
                .metadata("k", "v")
                .build();

        assertThrows(UnsupportedOperationException.class,
                () -> plan.ops().add(MutationPlan.Op.of("noop", "x")));
        assertThrows(UnsupportedOperationException.class,
                () -> plan.metadata().put("k2", "v2"));
    }

    @Test
    @DisplayName("Op.details defaults to an empty immutable map when null is passed")
    void opDetailsDefaultsToEmpty() {
        MutationPlan.Op op = new MutationPlan.Op("kill", "1234", null);

        assertTrue(op.details().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> op.details().put("k", "v"));
    }

    @Test
    @DisplayName("builder.ops(list) appends entries without exposing the original list")
    void builderOpsAppendsAndCopies() {
        List<MutationPlan.Op> input = List.of(
                MutationPlan.Op.of("move", "/a"),
                MutationPlan.Op.of("move", "/b"));

        MutationPlan plan = MutationPlan.builder()
                .toolName("t")
                .summary("s")
                .ops(input)
                .build();

        assertEquals(2, plan.opCount());
        assertNotSame(input, plan.ops());
    }

    @Test
    @DisplayName("toString contains tool name, risk level and op count for log-friendly output")
    void toStringIsInformative() {
        MutationPlan plan = MutationPlan.builder()
                .toolName("windows_filesystem")
                .summary("s")
                .riskLevel(MutationPlan.RiskLevel.HIGH)
                .op("delete", "/x")
                .build();

        String s = plan.toString();
        assertTrue(s.contains("windows_filesystem"));
        assertTrue(s.contains("HIGH"));
        assertTrue(s.contains("ops=1"));
    }

    @Test
    @DisplayName("Decision.approve / Decision.reject set fields and never return null reason")
    void decisionFactories() {
        MutationGuard.Decision approved = MutationGuard.Decision.approve("alice", "looks good");
        MutationGuard.Decision rejected = MutationGuard.Decision.reject("bob", null);

        assertTrue(approved.approved());
        assertEquals("alice", approved.decidedBy());
        assertEquals("looks good", approved.reason());

        assertEquals(false, rejected.approved());
        assertEquals("bob", rejected.decidedBy());
        assertSame("", rejected.reason());
    }
}
