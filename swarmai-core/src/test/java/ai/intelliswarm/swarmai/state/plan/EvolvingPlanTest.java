package ai.intelliswarm.swarmai.state.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("EvolvingPlan + Reducer Tests")
class EvolvingPlanTest {

    private static PlanEntry proposed(String id, String by, String payload, Risk risk) {
        return PlanEntry.builder()
                .id(id).proposedBy(by).payload(payload).risk(risk).build();
    }

    @Nested
    @DisplayName("EvolvingPlan structure")
    class Structure {

        @Test
        @DisplayName("empty plan has no entries and no active set")
        void emptyPlan() {
            EvolvingPlan p = EvolvingPlan.empty();
            assertTrue(p.isEmpty());
            assertEquals(0, p.size());
            assertTrue(p.active().isEmpty());
        }

        @Test
        @DisplayName("with(entry) appends a fresh id and preserves order")
        void withAppendsFreshIds() {
            EvolvingPlan p = EvolvingPlan.empty()
                    .with(proposed("a", "tool:x", "step1", Risk.LOW))
                    .with(proposed("b", "tool:y", "step2", Risk.MEDIUM));
            assertEquals(2, p.size());
            assertEquals("a", p.entries().get(0).id());
            assertEquals("b", p.entries().get(1).id());
        }

        @Test
        @DisplayName("with(entry) on existing id with same body replaces in place (status update)")
        void withReplacesExistingId() {
            PlanEntry seed = proposed("a", "tool:x", "step1", Risk.LOW);
            EvolvingPlan p = EvolvingPlan.empty()
                    .with(seed)
                    .with(seed.withStatus(PlanEntry.Status.APPROVED, "ok"));

            assertEquals(1, p.size());
            assertEquals(PlanEntry.Status.APPROVED, p.entries().get(0).status());
            assertEquals("ok", p.entries().get(0).reason());
        }

        @Test
        @DisplayName("with(entry) on existing id with different body throws")
        void withDifferentBodyThrows() {
            EvolvingPlan p = EvolvingPlan.empty()
                    .with(proposed("a", "tool:x", "step1", Risk.LOW));
            PlanEntry conflicting = proposed("a", "tool:y", "step1", Risk.LOW);

            assertThrows(IllegalArgumentException.class, () -> p.with(conflicting));
        }

        @Test
        @DisplayName("active() includes PROPOSED and APPROVED, excludes terminal states")
        void activeFiltersTerminal() {
            EvolvingPlan p = EvolvingPlan.empty()
                    .with(proposed("a", "tool:x", "ax", Risk.LOW))
                    .with(proposed("b", "tool:x", "bx", Risk.LOW))
                    .with(proposed("c", "tool:x", "cx", Risk.LOW))
                    .with(proposed("d", "tool:x", "dx", Risk.LOW))
                    .markStatus("a", PlanEntry.Status.APPROVED, "")
                    .markStatus("b", PlanEntry.Status.EXECUTED, "")
                    .markStatus("c", PlanEntry.Status.REJECTED, "")
                    .markStatus("d", PlanEntry.Status.SUPERSEDED, "");

            List<PlanEntry> active = p.active();
            assertEquals(1, active.size());
            assertEquals("a", active.get(0).id());
        }

        @Test
        @DisplayName("supersede marks old entry SUPERSEDED and appends replacement")
        void supersedeChain() {
            PlanEntry old = proposed("a", "tool:x", "old", Risk.LOW);
            PlanEntry replacement = PlanEntry.builder()
                    .id("a2").proposedBy("replanner").payload("new")
                    .risk(Risk.MEDIUM).supersedes("a").build();

            EvolvingPlan p = EvolvingPlan.empty().with(old).supersede("a", replacement);

            assertEquals(2, p.size());
            assertEquals(PlanEntry.Status.SUPERSEDED, p.byId("a").orElseThrow().status());
            assertEquals("a", p.byId("a2").orElseThrow().supersedes());
        }

        @Test
        @DisplayName("markStatus on unknown id throws")
        void markStatusUnknownIdThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> EvolvingPlan.empty().markStatus("nope", PlanEntry.Status.APPROVED, ""));
        }
    }

    @Nested
    @DisplayName("EvolvingPlanReducer")
    class Reducing {

        @Test
        @DisplayName("merging empty into populated returns the populated side")
        void emptyIntoPopulated() {
            EvolvingPlan populated = EvolvingPlan.empty()
                    .with(proposed("a", "tool:x", "ax", Risk.LOW));
            EvolvingPlan merged = EvolvingPlanReducer.instance()
                    .apply(populated, EvolvingPlan.empty());
            assertSame(populated, merged);
        }

        @Test
        @DisplayName("merging populated into empty returns the populated side")
        void populatedIntoEmpty() {
            EvolvingPlan populated = EvolvingPlan.empty()
                    .with(proposed("a", "tool:x", "ax", Risk.LOW));
            EvolvingPlan merged = EvolvingPlanReducer.instance()
                    .apply(EvolvingPlan.empty(), populated);
            assertSame(populated, merged);
        }

        @Test
        @DisplayName("merging two disjoint plans appends entries from new onto old")
        void disjointMerge() {
            EvolvingPlan oldP = EvolvingPlan.empty()
                    .with(proposed("a", "tool:x", "ax", Risk.LOW));
            EvolvingPlan newP = EvolvingPlan.empty()
                    .with(proposed("b", "tool:y", "by", Risk.MEDIUM));

            EvolvingPlan merged = EvolvingPlanReducer.instance().apply(oldP, newP);

            assertEquals(2, merged.size());
            assertEquals("a", merged.entries().get(0).id());
            assertEquals("b", merged.entries().get(1).id());
        }

        @Test
        @DisplayName("merging an entry status update applies the new status to the existing entry")
        void overlappingStatusMerge() {
            PlanEntry seed = proposed("a", "tool:x", "ax", Risk.LOW);
            EvolvingPlan oldP = EvolvingPlan.empty().with(seed);
            EvolvingPlan newP = EvolvingPlan.empty()
                    .with(seed.withStatus(PlanEntry.Status.APPROVED, "ok"));

            EvolvingPlan merged = EvolvingPlanReducer.instance().apply(oldP, newP);

            assertEquals(1, merged.size());
            assertEquals(PlanEntry.Status.APPROVED, merged.entries().get(0).status());
        }

        @Test
        @DisplayName("merging null on either side is safe and returns the other side")
        void nullSafeMerge() {
            EvolvingPlan p = EvolvingPlan.empty()
                    .with(proposed("a", "tool:x", "ax", Risk.LOW));
            assertEquals(p, EvolvingPlanReducer.instance().apply(null, p));
            assertEquals(p, EvolvingPlanReducer.instance().apply(p, null));
            assertTrue(EvolvingPlanReducer.instance().apply(null, null).isEmpty());
        }
    }

    @Nested
    @DisplayName("PlanEntry semantics")
    class EntrySemantics {

        @Test
        @DisplayName("withStatus produces a new entry with same id and updated status/reason")
        void withStatusPreservesIdentity() {
            PlanEntry e = proposed("a", "tool:x", "ax", Risk.LOW);
            PlanEntry approved = e.withStatus(PlanEntry.Status.APPROVED, "ok");
            assertEquals(e.id(), approved.id());
            assertEquals(PlanEntry.Status.APPROVED, approved.status());
            assertEquals("ok", approved.reason());
            assertFalse(approved.isActive() && approved.status() == PlanEntry.Status.PROPOSED);
            assertTrue(approved.isActive());
        }

        @Test
        @DisplayName("EXECUTED, REJECTED, SUPERSEDED are terminal; PROPOSED, APPROVED are active")
        void terminalVsActive() {
            PlanEntry e = proposed("a", "tool:x", "ax", Risk.LOW);
            assertTrue(e.isActive());
            assertFalse(e.isTerminal());
            assertTrue(e.withStatus(PlanEntry.Status.EXECUTED, "").isTerminal());
            assertTrue(e.withStatus(PlanEntry.Status.REJECTED, "").isTerminal());
            assertTrue(e.withStatus(PlanEntry.Status.SUPERSEDED, "").isTerminal());
            assertTrue(e.withStatus(PlanEntry.Status.APPROVED, "").isActive());
        }
    }
}
