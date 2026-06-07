package ai.intelliswarm.swarmai.state.plan;

import ai.intelliswarm.swarmai.state.AgentState;
import ai.intelliswarm.swarmai.state.Channels;
import ai.intelliswarm.swarmai.state.StateSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AgentStateBackedPlanChannelAccessor Tests")
class AgentStateBackedPlanChannelAccessorTest {

    private static StateSchema schema() {
        return StateSchema.builder()
                .channel("plan", Channels.evolvingPlan())
                .build();
    }

    private static AgentState initial() {
        return AgentState.of(schema(), Map.of());
    }

    private static PlanEntry entry(String id, Risk risk) {
        return PlanEntry.builder()
                .id(id).proposedBy("tool:x").payload("op-" + id).risk(risk).build();
    }

    @Test
    @DisplayName("get() returns empty plan when state has no channel value yet")
    void emptyOnFreshState() {
        AtomicReference<AgentState> ref = new AtomicReference<>(initial());
        PlanChannelAccessor accessor =
                new AgentStateBackedPlanChannelAccessor(ref::get, ref::set);
        assertTrue(accessor.get().isEmpty());
    }

    @Test
    @DisplayName("update() reads existing plan, applies mutator, writes new state via setState")
    void updateRoundTripsThroughState() {
        AtomicReference<AgentState> ref = new AtomicReference<>(initial());
        PlanChannelAccessor accessor =
                new AgentStateBackedPlanChannelAccessor(ref::get, ref::set);

        EvolvingPlan after = accessor.update(p -> p.with(entry("a", Risk.LOW)));

        assertEquals(1, after.size());
        // setState was invoked — ref now holds the updated AgentState
        EvolvingPlan stored = ref.get().<EvolvingPlan>value("plan").orElseThrow();
        assertSame(after, stored);
    }

    @Test
    @DisplayName("multiple updates compose: each one sees the prior plan via getState")
    void updatesCompose() {
        AtomicReference<AgentState> ref = new AtomicReference<>(initial());
        PlanChannelAccessor accessor =
                new AgentStateBackedPlanChannelAccessor(ref::get, ref::set);

        accessor.append(entry("a", Risk.LOW));
        accessor.append(entry("b", Risk.MEDIUM));
        accessor.markStatus("a", PlanEntry.Status.APPROVED, "");

        EvolvingPlan plan = accessor.get();
        assertEquals(2, plan.size());
        assertEquals(PlanEntry.Status.APPROVED, plan.byId("a").orElseThrow().status());
        assertEquals(PlanEntry.Status.PROPOSED, plan.byId("b").orElseThrow().status());
    }

    @Test
    @DisplayName("listener fires (null, current) on append and (previous, current) on transition")
    void listenersFireOnTransitions() {
        AtomicReference<AgentState> ref = new AtomicReference<>(initial());
        AgentStateBackedPlanChannelAccessor accessor =
                new AgentStateBackedPlanChannelAccessor(ref::get, ref::set);

        List<String> events = new ArrayList<>();
        accessor.addListener((prev, curr) ->
                events.add((prev == null ? "APPEND" : prev.status() + "→" + curr.status()) + ":" + curr.id()));

        accessor.append(entry("a", Risk.LOW));
        accessor.markStatus("a", PlanEntry.Status.APPROVED, "");
        accessor.markStatus("a", PlanEntry.Status.EXECUTED, "");

        assertEquals(List.of("APPEND:a", "PROPOSED→APPROVED:a", "APPROVED→EXECUTED:a"), events);
    }

    @Test
    @DisplayName("custom channel key is honoured")
    void customChannelKey() {
        StateSchema customSchema = StateSchema.builder()
                .channel("workflow_plan", Channels.evolvingPlan())
                .build();
        AtomicReference<AgentState> ref = new AtomicReference<>(AgentState.of(customSchema, Map.of()));

        AgentStateBackedPlanChannelAccessor accessor =
                new AgentStateBackedPlanChannelAccessor(ref::get, ref::set, "workflow_plan");

        accessor.append(entry("a", Risk.LOW));

        EvolvingPlan stored = ref.get().<EvolvingPlan>value("workflow_plan").orElseThrow();
        assertEquals(1, stored.size());
    }

    @Test
    @DisplayName("getState returning null throws")
    void nullStateRejected() {
        PlanChannelAccessor accessor =
                new AgentStateBackedPlanChannelAccessor(() -> null, s -> {});
        assertThrows(IllegalStateException.class,
                () -> accessor.update(p -> p.with(entry("a", Risk.LOW))));
    }

    @Test
    @DisplayName("EvolvingPlanReducer composes status updates merged through AgentState")
    void reducerHandlesConcurrentLikePartials() {
        // Two accessors over the same state holder simulate two nodes contributing partials.
        // Node A appends entry "a" PROPOSED. Node B sees the appended entry and approves it.
        AtomicReference<AgentState> ref = new AtomicReference<>(initial());
        PlanChannelAccessor a = new AgentStateBackedPlanChannelAccessor(ref::get, ref::set);
        PlanChannelAccessor b = new AgentStateBackedPlanChannelAccessor(ref::get, ref::set);

        a.append(entry("a", Risk.LOW));
        b.markStatus("a", PlanEntry.Status.APPROVED, "node-b");

        EvolvingPlan stored = ref.get().<EvolvingPlan>value("plan").orElseThrow();
        assertEquals(1, stored.size());
        assertEquals(PlanEntry.Status.APPROVED, stored.byId("a").orElseThrow().status());
    }
}
