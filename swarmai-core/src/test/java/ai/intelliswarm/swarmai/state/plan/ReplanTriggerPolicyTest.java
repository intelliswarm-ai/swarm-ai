package ai.intelliswarm.swarmai.state.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ReplanTriggerPolicy Tests")
class ReplanTriggerPolicyTest {

    private static PlanEntry entry(String id, PlanEntry.Status status) {
        return PlanEntry.builder()
                .id(id).proposedBy("tool:x").payload("op")
                .risk(Risk.LOW).status(status).build();
    }

    private static ReplanTriggerPolicy.ReplanTriggerEvent append(String id) {
        return new ReplanTriggerPolicy.ReplanTriggerEvent(null, entry(id, PlanEntry.Status.PROPOSED));
    }

    private static ReplanTriggerPolicy.ReplanTriggerEvent transition(
            PlanEntry.Status from, PlanEntry.Status to) {
        return new ReplanTriggerPolicy.ReplanTriggerEvent(
                entry("a", from), entry("a", to));
    }

    @Test
    @DisplayName("never() always returns false")
    void neverReturnsFalse() {
        ReplanTriggerPolicy p = ReplanTriggerPolicy.never();
        assertFalse(p.shouldReplan(EvolvingPlan.empty(), append("a")));
        assertFalse(p.shouldReplan(EvolvingPlan.empty(),
                transition(PlanEntry.Status.APPROVED, PlanEntry.Status.EXECUTED)));
    }

    @Test
    @DisplayName("always() always returns true")
    void alwaysReturnsTrue() {
        ReplanTriggerPolicy p = ReplanTriggerPolicy.always();
        assertTrue(p.shouldReplan(EvolvingPlan.empty(), append("a")));
        assertTrue(p.shouldReplan(EvolvingPlan.empty(),
                transition(PlanEntry.Status.APPROVED, PlanEntry.Status.EXECUTED)));
    }

    @Test
    @DisplayName("everyNSteps(3) fires on every 3rd non-append event")
    void everyNStepsFiresAtCadence() {
        ReplanTriggerPolicy p = ReplanTriggerPolicy.everyNSteps(3);
        // appends shouldn't be counted
        assertFalse(p.shouldReplan(EvolvingPlan.empty(), append("a")));
        assertFalse(p.shouldReplan(EvolvingPlan.empty(), append("b")));
        // transitions: 1, 2 → false; 3 → true; 4, 5 → false; 6 → true
        assertFalse(p.shouldReplan(EvolvingPlan.empty(),
                transition(PlanEntry.Status.PROPOSED, PlanEntry.Status.APPROVED)));
        assertFalse(p.shouldReplan(EvolvingPlan.empty(),
                transition(PlanEntry.Status.PROPOSED, PlanEntry.Status.APPROVED)));
        assertTrue(p.shouldReplan(EvolvingPlan.empty(),
                transition(PlanEntry.Status.PROPOSED, PlanEntry.Status.APPROVED)));
        assertFalse(p.shouldReplan(EvolvingPlan.empty(),
                transition(PlanEntry.Status.PROPOSED, PlanEntry.Status.APPROVED)));
        assertFalse(p.shouldReplan(EvolvingPlan.empty(),
                transition(PlanEntry.Status.PROPOSED, PlanEntry.Status.APPROVED)));
        assertTrue(p.shouldReplan(EvolvingPlan.empty(),
                transition(PlanEntry.Status.PROPOSED, PlanEntry.Status.APPROVED)));
    }

    @Test
    @DisplayName("everyNSteps(0 or negative) is rejected")
    void everyNStepsRejectsBadValues() {
        assertThrows(IllegalArgumentException.class, () -> ReplanTriggerPolicy.everyNSteps(0));
        assertThrows(IllegalArgumentException.class, () -> ReplanTriggerPolicy.everyNSteps(-1));
    }

    @Test
    @DisplayName("onRejection() fires only when current is REJECTED")
    void onRejectionFiresOnRejected() {
        ReplanTriggerPolicy p = ReplanTriggerPolicy.onRejection();
        assertTrue(p.shouldReplan(EvolvingPlan.empty(),
                transition(PlanEntry.Status.APPROVED, PlanEntry.Status.REJECTED)));
        assertFalse(p.shouldReplan(EvolvingPlan.empty(),
                transition(PlanEntry.Status.APPROVED, PlanEntry.Status.EXECUTED)));
        assertFalse(p.shouldReplan(EvolvingPlan.empty(), append("a")));
    }

    @Test
    @DisplayName("onTerminal() fires on EXECUTED, REJECTED, SUPERSEDED")
    void onTerminalFiresAcrossTerminalStates() {
        ReplanTriggerPolicy p = ReplanTriggerPolicy.onTerminal();
        assertTrue(p.shouldReplan(EvolvingPlan.empty(),
                transition(PlanEntry.Status.APPROVED, PlanEntry.Status.EXECUTED)));
        assertTrue(p.shouldReplan(EvolvingPlan.empty(),
                transition(PlanEntry.Status.APPROVED, PlanEntry.Status.REJECTED)));
        assertTrue(p.shouldReplan(EvolvingPlan.empty(),
                transition(PlanEntry.Status.APPROVED, PlanEntry.Status.SUPERSEDED)));
        assertFalse(p.shouldReplan(EvolvingPlan.empty(),
                transition(PlanEntry.Status.PROPOSED, PlanEntry.Status.APPROVED)));
    }

    @Test
    @DisplayName("any() fires when any composed trigger fires")
    void anyComposition() {
        ReplanTriggerPolicy p = ReplanTriggerPolicy.any(
                ReplanTriggerPolicy.never(),
                ReplanTriggerPolicy.onRejection());
        assertTrue(p.shouldReplan(EvolvingPlan.empty(),
                transition(PlanEntry.Status.APPROVED, PlanEntry.Status.REJECTED)));
        assertFalse(p.shouldReplan(EvolvingPlan.empty(),
                transition(PlanEntry.Status.APPROVED, PlanEntry.Status.EXECUTED)));
    }

    @Test
    @DisplayName("all() fires only when every composed trigger fires")
    void allComposition() {
        ReplanTriggerPolicy p = ReplanTriggerPolicy.all(
                ReplanTriggerPolicy.always(),
                ReplanTriggerPolicy.onRejection());
        assertTrue(p.shouldReplan(EvolvingPlan.empty(),
                transition(PlanEntry.Status.APPROVED, PlanEntry.Status.REJECTED)));
        assertFalse(p.shouldReplan(EvolvingPlan.empty(),
                transition(PlanEntry.Status.APPROVED, PlanEntry.Status.EXECUTED)));
    }

    @Test
    @DisplayName("all() with empty list is false (no policies, nothing to fire)")
    void allWithEmptyIsFalse() {
        ReplanTriggerPolicy p = ReplanTriggerPolicy.all();
        assertFalse(p.shouldReplan(EvolvingPlan.empty(), append("a")));
    }

    @Test
    @DisplayName("ReplanTriggerEvent reports isAppend / isTerminal correctly")
    void eventClassifiers() {
        assertTrue(append("a").isAppend());
        assertFalse(append("a").isTerminal());

        ReplanTriggerPolicy.ReplanTriggerEvent terminal =
                transition(PlanEntry.Status.APPROVED, PlanEntry.Status.EXECUTED);
        assertFalse(terminal.isAppend());
        assertTrue(terminal.isTerminal());

        ReplanTriggerPolicy.ReplanTriggerEvent active =
                transition(PlanEntry.Status.PROPOSED, PlanEntry.Status.APPROVED);
        assertFalse(active.isAppend());
        assertFalse(active.isTerminal());
        assertEquals(PlanEntry.Status.APPROVED, active.current().status());
    }
}
