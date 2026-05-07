package ai.intelliswarm.swarmai.state.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PlanContextRenderer Tests")
class PlanContextRendererTest {

    private static PlanEntry entry(String id, Risk risk, String summary) {
        PlanEntry.Builder b = PlanEntry.builder()
                .id(id).proposedBy("tool:os_filesystem")
                .payload("op-" + id).risk(risk);
        if (summary != null) b.metadata("summary", summary);
        return b.build();
    }

    @Test
    @DisplayName("empty plan renders as empty string (no leaked header)")
    void emptyPlanRendersEmpty() {
        assertEquals("", new PlanContextRenderer().render(EvolvingPlan.empty()));
    }

    @Test
    @DisplayName("a single PROPOSED entry produces header + one section + the entry line")
    void singleProposedEntry() {
        EvolvingPlan plan = EvolvingPlan.empty()
                .with(entry("a", Risk.LOW, "create dir"));
        String out = new PlanContextRenderer().render(plan);

        assertTrue(out.startsWith("## Current plan"), "Header missing");
        assertTrue(out.contains("### proposed (1)"), "Section header missing: " + out);
        assertTrue(out.contains("`a`"), "Entry id missing: " + out);
        assertTrue(out.contains("[LOW]"), "Risk tag missing: " + out);
        assertTrue(out.contains("create dir"), "Summary missing: " + out);
    }

    @Test
    @DisplayName("entries are grouped by status and ordered: APPROVED, PROPOSED, EXECUTED, REJECTED")
    void groupedAndOrdered() {
        EvolvingPlan plan = EvolvingPlan.empty()
                .with(entry("p1", Risk.LOW, "p1-summary"))
                .with(entry("a1", Risk.MEDIUM, "a1-summary"))
                .with(entry("e1", Risk.LOW, "e1-summary"))
                .with(entry("r1", Risk.HIGH, "r1-summary"))
                .markStatus("a1", PlanEntry.Status.APPROVED, "ok", "PLAN_POLICY")
                .markStatus("e1", PlanEntry.Status.APPROVED, "ok", "PLAN_POLICY")
                .markStatus("e1", PlanEntry.Status.EXECUTED, "ran")
                .markStatus("r1", PlanEntry.Status.REJECTED, "denied", "alice");

        String out = new PlanContextRenderer().render(plan);
        int approvedIdx = out.indexOf("### approved");
        int proposedIdx = out.indexOf("### proposed");
        int executedIdx = out.indexOf("### executed");
        int rejectedIdx = out.indexOf("### rejected");

        assertTrue(approvedIdx > 0);
        assertTrue(proposedIdx > approvedIdx);
        assertTrue(executedIdx > proposedIdx);
        assertTrue(rejectedIdx > executedIdx);
    }

    @Test
    @DisplayName("non-PROPOSED entries show decidedBy attribution and reason")
    void decidedByAndReasonRendered() {
        EvolvingPlan plan = EvolvingPlan.empty()
                .with(entry("a", Risk.LOW, "do-thing"))
                .markStatus("a", PlanEntry.Status.APPROVED, "policy auto-approved", "PLAN_POLICY");

        String out = new PlanContextRenderer().render(plan);
        assertTrue(out.contains("approved by PLAN_POLICY"),
                "decidedBy attribution missing: " + out);
        assertTrue(out.contains("policy auto-approved"),
                "reason missing: " + out);
    }

    @Test
    @DisplayName("SUPERSEDED entries are omitted by default; included when configured")
    void supersededHiddenByDefault() {
        EvolvingPlan plan = EvolvingPlan.empty()
                .with(entry("a", Risk.LOW, "old"))
                .markStatus("a", PlanEntry.Status.APPROVED, "", "PLAN_POLICY")
                .markStatus("a", PlanEntry.Status.SUPERSEDED, "replanner", "REPLANNER")
                .with(PlanEntry.builder()
                        .id("a2").proposedBy("replanner").payload("new")
                        .risk(Risk.LOW).supersedes("a")
                        .metadata("summary", "new")
                        .build());

        String defaultOut = new PlanContextRenderer().render(plan);
        assertFalse(defaultOut.contains("### superseded"),
                "Default renderer should omit superseded section: " + defaultOut);

        String inclusiveOut = new PlanContextRenderer("## Current plan",
                /*includeSuperseded*/ true, 20).render(plan);
        assertTrue(inclusiveOut.contains("### superseded"),
                "Inclusive renderer should include superseded section: " + inclusiveOut);
    }

    @Test
    @DisplayName("supersedes pointer is rendered for replacement entries")
    void supersedesPointerRendered() {
        EvolvingPlan plan = EvolvingPlan.empty()
                .with(PlanEntry.builder()
                        .id("a2").proposedBy("replanner").payload("new")
                        .risk(Risk.LOW).supersedes("a")
                        .metadata("summary", "new")
                        .build());

        String out = new PlanContextRenderer().render(plan);
        assertTrue(out.contains("supersedes a"),
                "Supersedes pointer missing: " + out);
    }

    @Test
    @DisplayName("output is deterministic — re-rendering an unchanged plan produces the same string")
    void renderIsDeterministic() {
        EvolvingPlan plan = EvolvingPlan.empty()
                .with(entry("a", Risk.LOW, "first"))
                .with(entry("b", Risk.MEDIUM, "second"));

        PlanContextRenderer r = new PlanContextRenderer();
        String first = r.render(plan);
        String second = r.render(plan);
        assertEquals(first, second);
    }

    @Test
    @DisplayName("section truncation kicks in at maxEntriesPerSection")
    void sectionTruncation() {
        EvolvingPlan plan = EvolvingPlan.empty();
        for (int i = 0; i < 25; i++) {
            plan = plan.with(entry("e" + i, Risk.LOW, "summary-" + i));
        }
        String out = new PlanContextRenderer("## Plan", false, 5).render(plan);
        assertTrue(out.contains("… and 20 more"),
                "Truncation indicator missing: " + out);
    }

    @Test
    @DisplayName("rejects maxEntriesPerSection < 1")
    void rejectsBadMax() {
        assertThrows(IllegalArgumentException.class,
                () -> new PlanContextRenderer("h", false, 0));
    }
}
