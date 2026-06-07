package ai.intelliswarm.swarmai.state.plan;

import ai.intelliswarm.swarmai.base.MockChatClientFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("LlmReplanner Tests")
class LlmReplannerTest {

    private static PlanEntry entry(String id, Risk risk, String summary) {
        return PlanEntry.builder()
                .id(id).proposedBy("tool:x").payload("op-" + id)
                .risk(risk).metadata("summary", summary).build();
    }

    private static String json(String body) {
        // Spring AI's BeanOutputConverter accepts JSON inside ```json fences or raw.
        return body;
    }

    @Test
    @DisplayName("clean response with one new entry yields a single Amendments.newEntry")
    void cleanNewEntryParsed() {
        String response = json("""
                {
                  "newEntries": [
                    {"summary": "verify backup", "risk": "LOW", "rationale": "ensure data is safe"}
                  ],
                  "supersessions": [],
                  "rationale": "added a verification step"
                }
                """);

        ChatClient mock = MockChatClientFactory.withResponse(response);
        LlmReplanner replanner = new LlmReplanner(mock);

        Replanner.Amendments out = replanner.amend(
                EvolvingPlan.empty().with(entry("a", Risk.MEDIUM, "do thing")),
                ReplanContext.of("complete the workflow"));

        assertEquals(1, out.newEntries().size());
        assertEquals(0, out.supersessions().size());
        assertEquals("added a verification step", out.rationale());
        PlanEntry e = out.newEntries().get(0);
        assertEquals(LlmReplanner.PROPOSED_BY, e.proposedBy());
        assertEquals(Risk.LOW, e.risk());
        assertEquals("verify backup", e.metadata().get("summary"));
    }

    @Test
    @DisplayName("response with a supersession parses into Amendments.supersessions with correct supersedes pointer")
    void supersessionParsed() {
        String response = """
                {
                  "newEntries": [],
                  "supersessions": [
                    {"oldId": "a", "replacementSummary": "use rsync instead", "replacementRisk": "MEDIUM", "rationale": "rsync handles partial transfers"}
                  ],
                  "rationale": "swapped tool"
                }
                """;

        ChatClient mock = MockChatClientFactory.withResponse(response);
        LlmReplanner replanner = new LlmReplanner(mock);

        Replanner.Amendments out = replanner.amend(
                EvolvingPlan.empty().with(entry("a", Risk.LOW, "use cp")),
                ReplanContext.of("transfer data"));

        assertEquals(1, out.supersessions().size());
        Replanner.Amendments.Supersession s = out.supersessions().get(0);
        assertEquals("a", s.oldId());
        assertEquals("a", s.replacement().supersedes());
        assertEquals(Risk.MEDIUM, s.replacement().risk());
        assertEquals("use rsync instead", s.replacement().metadata().get("summary"));
    }

    @Test
    @DisplayName("empty amendments object → Amendments.none() with the rationale preserved")
    void emptyAmendmentsRoundTrip() {
        String response = """
                {
                  "newEntries": [],
                  "supersessions": [],
                  "rationale": "plan looks fine"
                }
                """;

        ChatClient mock = MockChatClientFactory.withResponse(response);
        LlmReplanner replanner = new LlmReplanner(mock);

        Replanner.Amendments out = replanner.amend(
                EvolvingPlan.empty(), ReplanContext.of("goal"));

        assertTrue(out.isEmpty());
        assertEquals("plan looks fine", out.rationale());
    }

    @Test
    @DisplayName("malformed JSON response → Amendments.none with parse-error rationale")
    void malformedJsonReturnsNone() {
        String response = "this is not JSON at all";

        ChatClient mock = MockChatClientFactory.withResponse(response);
        LlmReplanner replanner = new LlmReplanner(mock);

        Replanner.Amendments out = replanner.amend(
                EvolvingPlan.empty(), ReplanContext.of("goal"));

        assertTrue(out.isEmpty());
        assertTrue(out.rationale().toLowerCase().contains("parse"),
                "Expected parse-error rationale; got: " + out.rationale());
    }

    @Test
    @DisplayName("empty LLM response → Amendments.none with empty-response rationale")
    void emptyResponseReturnsNone() {
        ChatClient mock = MockChatClientFactory.withResponse("");
        LlmReplanner replanner = new LlmReplanner(mock);

        Replanner.Amendments out = replanner.amend(
                EvolvingPlan.empty(), ReplanContext.of("goal"));

        assertTrue(out.isEmpty());
        assertTrue(out.rationale().toLowerCase().contains("empty"),
                "Expected empty-response rationale; got: " + out.rationale());
    }

    @Test
    @DisplayName("entries with blank summary are dropped silently")
    void blankSummaryEntriesDropped() {
        String response = """
                {
                  "newEntries": [
                    {"summary": "", "risk": "LOW"},
                    {"summary": "real step", "risk": "LOW"}
                  ],
                  "supersessions": [],
                  "rationale": "added one step"
                }
                """;

        ChatClient mock = MockChatClientFactory.withResponse(response);
        LlmReplanner replanner = new LlmReplanner(mock);

        Replanner.Amendments out = replanner.amend(
                EvolvingPlan.empty(), ReplanContext.of("goal"));

        assertEquals(1, out.newEntries().size());
        assertEquals("real step", out.newEntries().get(0).metadata().get("summary"));
    }

    @Test
    @DisplayName("unknown risk strings normalise to Risk.UNKNOWN")
    void unknownRiskFallsBack() {
        String response = """
                {
                  "newEntries": [
                    {"summary": "step", "risk": "moderate-ish", "rationale": "..."}
                  ],
                  "supersessions": [],
                  "rationale": "ok"
                }
                """;

        ChatClient mock = MockChatClientFactory.withResponse(response);
        LlmReplanner replanner = new LlmReplanner(mock);

        Replanner.Amendments out = replanner.amend(
                EvolvingPlan.empty(), ReplanContext.of("goal"));

        assertEquals(1, out.newEntries().size());
        assertEquals(Risk.UNKNOWN, out.newEntries().get(0).risk());
    }

    @Test
    @DisplayName("lower-case risk strings normalise correctly (case-insensitive)")
    void lowercaseRiskAccepted() {
        String response = """
                {
                  "newEntries": [
                    {"summary": "step", "risk": "low", "rationale": "..."}
                  ],
                  "supersessions": [],
                  "rationale": "ok"
                }
                """;

        ChatClient mock = MockChatClientFactory.withResponse(response);
        LlmReplanner replanner = new LlmReplanner(mock);

        Replanner.Amendments out = replanner.amend(
                EvolvingPlan.empty(), ReplanContext.of("goal"));

        assertEquals(Risk.LOW, out.newEntries().get(0).risk());
    }

    @Test
    @DisplayName("supersessions with blank oldId or replacementSummary are dropped")
    void blankSupersessionFieldsDropped() {
        String response = """
                {
                  "newEntries": [],
                  "supersessions": [
                    {"oldId": "", "replacementSummary": "x", "replacementRisk": "LOW"},
                    {"oldId": "a", "replacementSummary": "", "replacementRisk": "LOW"},
                    {"oldId": "a", "replacementSummary": "valid", "replacementRisk": "LOW"}
                  ],
                  "rationale": "..."
                }
                """;

        ChatClient mock = MockChatClientFactory.withResponse(response);
        LlmReplanner replanner = new LlmReplanner(mock);

        Replanner.Amendments out = replanner.amend(
                EvolvingPlan.empty(), ReplanContext.of("goal"));

        assertEquals(1, out.supersessions().size());
        assertEquals("valid", out.supersessions().get(0).replacement().metadata().get("summary"));
    }

    @Test
    @DisplayName("end-to-end: ReplannerLoop + LlmReplanner emits amendments based on observation")
    void endToEndWithLoop() {
        String response = """
                {
                  "newEntries": [
                    {"summary": "investigate failure", "risk": "LOW", "rationale": "step a failed"}
                  ],
                  "supersessions": [],
                  "rationale": "react to failure"
                }
                """;

        ChatClient mock = MockChatClientFactory.withResponse(response);
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        LlmReplanner replanner = new LlmReplanner(mock);

        ReplannerLoop.start(channel, replanner,
                ReplanTriggerPolicy.onRejection(),
                () -> ReplanContext.builder()
                        .goal("complete the workflow")
                        .addObservation("entry a was rejected")
                        .build());

        // Workflow: append a, approve, then reject
        channel.append(entry("a", Risk.LOW, "step a"));
        channel.markStatus("a", PlanEntry.Status.APPROVED, "ok", "PLAN_POLICY");
        channel.markStatus("a", PlanEntry.Status.REJECTED, "operator rejected", "alice");

        // Loop fired the LLM replanner; one new entry should have been appended.
        EvolvingPlan plan = channel.get();
        List<PlanEntry> proposed = plan.withStatus(PlanEntry.Status.PROPOSED);
        assertFalse(proposed.isEmpty(), "Expected at least one PROPOSED amendment");
        PlanEntry amendment = proposed.get(0);
        assertEquals(LlmReplanner.PROPOSED_BY, amendment.proposedBy());
        assertNotNull(amendment.metadata().get("summary"));
        assertTrue(((String) amendment.metadata().get("summary")).contains("investigate"),
                "Amendment summary should reflect the LLM's response");
    }
}
