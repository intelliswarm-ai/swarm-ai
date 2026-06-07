package ai.intelliswarm.swarmai.state.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("InMemoryPlanChannelAccessor + PlanOperator Tests")
class InMemoryPlanChannelAccessorTest {

    private static PlanEntry entry(String id, Risk risk) {
        return PlanEntry.builder()
                .id(id).proposedBy("tool:x").payload("op-" + id).risk(risk).build();
    }

    @Test
    @DisplayName("get() and append() round-trip a single entry")
    void appendRoundTrip() {
        InMemoryPlanChannelAccessor a = new InMemoryPlanChannelAccessor();
        a.append(entry("a", Risk.LOW));
        assertEquals(1, a.get().size());
        assertEquals("a", a.get().entries().get(0).id());
    }

    @Test
    @DisplayName("listener fires (null, current) on append and (previous, current) on transition")
    void listenerSeesAppendsAndTransitions() {
        InMemoryPlanChannelAccessor a = new InMemoryPlanChannelAccessor();
        List<String> events = new ArrayList<>();
        a.addListener((prev, curr) ->
                events.add((prev == null ? "APPEND" : prev.status() + "→" + curr.status()) + ":" + curr.id()));

        a.append(entry("a", Risk.LOW));
        a.markStatus("a", PlanEntry.Status.APPROVED, "ok");
        a.markStatus("a", PlanEntry.Status.EXECUTED, "ran");

        assertEquals(List.of("APPEND:a", "PROPOSED→APPROVED:a", "APPROVED→EXECUTED:a"), events);
    }

    @Test
    @DisplayName("listener does not fire when an update produces no status change")
    void noNotificationOnNoOpUpdate() {
        InMemoryPlanChannelAccessor a = new InMemoryPlanChannelAccessor();
        a.append(entry("a", Risk.LOW));

        List<String> events = new ArrayList<>();
        a.addListener((prev, curr) -> events.add("fired"));

        // Replace with same body — channel keeps the entry; no status change → no notification.
        PlanEntry same = entry("a", Risk.LOW);
        a.append(same);

        assertTrue(events.isEmpty());
    }

    @Test
    @DisplayName("update returns the post-mutation plan")
    void updateReturnsResult() {
        InMemoryPlanChannelAccessor a = new InMemoryPlanChannelAccessor();
        EvolvingPlan after = a.update(p -> p.with(entry("a", Risk.LOW)));
        assertEquals(1, after.size());
        assertSame(after, a.get());
    }

    @Test
    @DisplayName("PlanOperator.approve transitions PROPOSED → APPROVED and records actor")
    void operatorApproveRecordsActor() {
        InMemoryPlanChannelAccessor a = new InMemoryPlanChannelAccessor();
        a.append(entry("a", Risk.LOW));
        PlanOperator op = new PlanOperator(a);

        PlanEntry approved = op.approve("a", "alice", "ok");
        assertEquals(PlanEntry.Status.APPROVED, approved.status());
        assertTrue(approved.reason().startsWith("alice"));
    }

    @Test
    @DisplayName("PlanOperator.reject works on both PROPOSED and APPROVED entries (override case)")
    void operatorRejectFromAnyActiveStatus() {
        InMemoryPlanChannelAccessor a = new InMemoryPlanChannelAccessor();
        a.append(entry("a", Risk.LOW));
        a.append(entry("b", Risk.LOW));
        a.markStatus("a", PlanEntry.Status.APPROVED, "policy");
        PlanOperator op = new PlanOperator(a);

        PlanEntry afterA = op.reject("a", "bob", "second thoughts");
        PlanEntry afterB = op.reject("b", "bob", "");

        assertEquals(PlanEntry.Status.REJECTED, afterA.status());
        assertEquals(PlanEntry.Status.REJECTED, afterB.status());
        assertTrue(afterA.reason().contains("bob"));
    }

    @Test
    @DisplayName("PlanOperator throws on unknown id")
    void operatorThrowsOnUnknown() {
        PlanOperator op = new PlanOperator(new InMemoryPlanChannelAccessor());
        assertThrows(IllegalArgumentException.class, () -> op.approve("nope", "a", ""));
    }

    @Test
    @DisplayName("PlanOperator refuses to transition out of a terminal status")
    void operatorRefusesTerminalTransitions() {
        InMemoryPlanChannelAccessor a = new InMemoryPlanChannelAccessor();
        a.append(entry("a", Risk.LOW));
        a.markStatus("a", PlanEntry.Status.EXECUTED, "");
        PlanOperator op = new PlanOperator(a);

        assertThrows(IllegalStateException.class, () -> op.approve("a", "alice", ""));
    }

    @Test
    @DisplayName("PlanOperator.supersede marks old entry SUPERSEDED and appends replacement")
    void operatorSupersede() {
        InMemoryPlanChannelAccessor a = new InMemoryPlanChannelAccessor();
        a.append(entry("a", Risk.LOW));
        PlanOperator op = new PlanOperator(a);

        PlanEntry replacement = PlanEntry.builder()
                .id("a2").proposedBy("replanner").payload("op-a2")
                .risk(Risk.MEDIUM).supersedes("a").build();
        op.supersede("a", replacement, "alice");

        assertEquals(PlanEntry.Status.SUPERSEDED, a.get().byId("a").orElseThrow().status());
        assertEquals(2, a.get().size());
        assertEquals("a", a.get().byId("a2").orElseThrow().supersedes());
    }

    @Test
    @DisplayName("removeListener stops further notifications")
    void removeListenerStops() {
        InMemoryPlanChannelAccessor a = new InMemoryPlanChannelAccessor();
        List<String> events = new ArrayList<>();
        PlanChannelAccessor.PlanListener l = (prev, curr) -> events.add("fired");
        a.addListener(l);
        a.append(entry("a", Risk.LOW));
        a.removeListener(l);
        a.markStatus("a", PlanEntry.Status.APPROVED, "");

        assertEquals(1, events.size());
    }

    @Test
    @DisplayName("update mutator returning null throws NullPointerException")
    void mutatorReturningNullRejected() {
        InMemoryPlanChannelAccessor a = new InMemoryPlanChannelAccessor();
        assertThrows(NullPointerException.class, () -> a.update(p -> null));
        assertNull(null); // satisfy "no unused imports" warnings on assertNull
    }
}
