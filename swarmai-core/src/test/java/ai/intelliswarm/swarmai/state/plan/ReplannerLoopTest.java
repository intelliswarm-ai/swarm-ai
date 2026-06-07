package ai.intelliswarm.swarmai.state.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Replanner SPI + ReplannerLoop Tests")
class ReplannerLoopTest {

    private static PlanEntry proposed(String id) {
        return PlanEntry.builder()
                .id(id).proposedBy("tool:x").payload("op-" + id).risk(Risk.LOW).build();
    }

    private static PlanEntry replacement(String newId, String oldId) {
        return PlanEntry.builder()
                .id(newId).proposedBy("replanner").payload("op-" + newId)
                .risk(Risk.LOW).supersedes(oldId).build();
    }

    @Test
    @DisplayName("Amendments.none() is empty and reports rationale")
    void amendmentsNone() {
        Replanner.Amendments none = Replanner.Amendments.none("nothing to do");
        assertTrue(none.isEmpty());
        assertEquals("nothing to do", none.rationale());
    }

    @Test
    @DisplayName("Supersession constructor enforces replacement.supersedes() == oldId")
    void supersessionRequiresMatchingPointer() {
        PlanEntry mismatched = PlanEntry.builder()
                .id("a2").proposedBy("replanner").payload("op")
                .risk(Risk.LOW).supersedes("WRONG").build();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new Replanner.Amendments.Supersession("a", mismatched));
    }

    @Test
    @DisplayName("Loop fires replanner only when trigger says yes")
    void loopRespectsTrigger() {
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        AtomicInteger callCount = new AtomicInteger(0);
        Replanner replanner = (plan, ctx) -> {
            callCount.incrementAndGet();
            return Replanner.Amendments.none();
        };

        ReplannerLoop loop = ReplannerLoop.start(
                channel, replanner, ReplanTriggerPolicy.never(),
                () -> ReplanContext.of("goal"));

        channel.append(proposed("a"));
        channel.markStatus("a", PlanEntry.Status.APPROVED, "", "PLAN_POLICY");
        channel.markStatus("a", PlanEntry.Status.EXECUTED, "ran");

        assertEquals(0, callCount.get(),
                "never() trigger should not have fired the replanner");
        loop.close();
    }

    @Test
    @DisplayName("Loop fires replanner on every trigger when configured to always()")
    void loopFiresOnEveryEvent() {
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        AtomicInteger callCount = new AtomicInteger(0);
        Replanner replanner = (plan, ctx) -> {
            callCount.incrementAndGet();
            return Replanner.Amendments.none();
        };

        ReplannerLoop loop = ReplannerLoop.start(
                channel, replanner, ReplanTriggerPolicy.always(),
                () -> ReplanContext.of("goal"));

        channel.append(proposed("a"));      // 1
        channel.markStatus("a", PlanEntry.Status.APPROVED, "", "PLAN_POLICY"); // 2
        channel.markStatus("a", PlanEntry.Status.EXECUTED, "ran"); // 3

        assertEquals(3, callCount.get());
        loop.close();
    }

    @Test
    @DisplayName("Loop appends new amendment entries via the accessor")
    void loopAppliesNewEntries() {
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        Replanner replanner = (plan, ctx) -> {
            // Only emit on the first call; otherwise we recurse via our own writes.
            if (plan.size() == 1) {
                return Replanner.Amendments.builder()
                        .addEntry(proposed("amend-1"))
                        .rationale("added a step")
                        .build();
            }
            return Replanner.Amendments.none();
        };

        ReplannerLoop.start(channel, replanner, ReplanTriggerPolicy.always(),
                () -> ReplanContext.of("goal"));

        channel.append(proposed("a")); // triggers replanner; replanner adds "amend-1"

        EvolvingPlan plan = channel.get();
        assertEquals(2, plan.size());
        assertTrue(plan.byId("amend-1").isPresent());
    }

    @Test
    @DisplayName("Loop applies supersession amendments correctly (mark old SUPERSEDED, append new)")
    void loopAppliesSupersessions() {
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        channel.append(proposed("a"));
        channel.markStatus("a", PlanEntry.Status.APPROVED, "", "PLAN_POLICY");

        // Replanner condition: when there's just one entry "a" and no replacement yet.
        // When the EXECUTED transition fires the loop, plan still has size 1; the replanner
        // emits a supersession, which the loop applies. Reentrancy guard stops recursion.
        Replanner replanner = (plan, ctx) -> {
            if (plan.size() == 1 && plan.byId("a").isPresent()) {
                return Replanner.Amendments.builder()
                        .supersede("a", replacement("a2", "a"))
                        .rationale("better idea")
                        .build();
            }
            return Replanner.Amendments.none();
        };

        ReplannerLoop.start(channel, replanner,
                ReplanTriggerPolicy.always(), () -> ReplanContext.of("goal"));

        // Trigger by transitioning a; loop fires, replanner emits supersession.
        channel.markStatus("a", PlanEntry.Status.EXECUTED, "ran");

        EvolvingPlan plan = channel.get();
        assertEquals(2, plan.size());
        assertEquals(PlanEntry.Status.SUPERSEDED, plan.byId("a").orElseThrow().status());
        assertEquals(ReplannerLoop.DECIDED_BY_REPLANNER, plan.byId("a").orElseThrow().decidedBy());
        PlanEntry replacement = plan.byId("a2").orElseThrow();
        assertEquals("a", replacement.supersedes());
    }

    @Test
    @DisplayName("Loop's reentrancy guard prevents recursion when its own amendments trigger transitions")
    void loopDoesNotRecurseOnOwnWrites() {
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        AtomicInteger calls = new AtomicInteger();
        Replanner replanner = (plan, ctx) -> {
            int n = calls.incrementAndGet();
            // Always emit something — this would cause runaway loops without the guard.
            return Replanner.Amendments.builder()
                    .addEntry(proposed("amend-" + n))
                    .build();
        };

        ReplannerLoop.start(channel, replanner,
                ReplanTriggerPolicy.always(), () -> ReplanContext.of("goal"));

        channel.append(proposed("seed")); // triggers once
        // The replanner fires once for "seed", appends "amend-1".
        // Without guard: appending "amend-1" would trigger again, creating "amend-2", etc.
        // With guard: the loop ignores its own writes.
        assertEquals(1, calls.get(),
                "Loop should fire exactly once per external transition; got " + calls.get());
    }

    @Test
    @DisplayName("Loop tolerates contextSupplier returning null (no-ops, doesn't crash)")
    void loopHandlesNullContext() {
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        AtomicInteger calls = new AtomicInteger();
        Replanner replanner = (plan, ctx) -> {
            calls.incrementAndGet();
            return Replanner.Amendments.none();
        };

        ReplannerLoop.start(channel, replanner,
                ReplanTriggerPolicy.always(), () -> null);

        channel.append(proposed("a")); // trigger; context null → loop bails before replanner
        assertEquals(0, calls.get());
    }

    @Test
    @DisplayName("Loop close() detaches the listener — no further fires")
    void closeStopsFurtherFires() {
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        AtomicInteger calls = new AtomicInteger();
        Replanner replanner = (plan, ctx) -> {
            calls.incrementAndGet();
            return Replanner.Amendments.none();
        };

        ReplannerLoop loop = ReplannerLoop.start(channel, replanner,
                ReplanTriggerPolicy.always(), () -> ReplanContext.of("goal"));

        channel.append(proposed("a"));
        loop.close();
        channel.append(proposed("b"));

        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("end-to-end: replanner reacts to operator override by proposing a recovery step")
    void recoveryFromRejection() {
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();

        // Replanner: when something gets REJECTED, propose a recovery entry.
        Replanner replanner = (plan, ctx) -> {
            // Look for the most recent REJECTED entry; if so, add a recovery proposal.
            for (int i = plan.entries().size() - 1; i >= 0; i--) {
                PlanEntry e = plan.entries().get(i);
                String recoveryId = "recovery-for-" + e.id();
                if (e.status() == PlanEntry.Status.REJECTED
                        && plan.entries().stream().noneMatch(other ->
                                recoveryId.equals(other.id()))) {
                    return Replanner.Amendments.builder()
                            .addEntry(PlanEntry.builder()
                                    .id("recovery-for-" + e.id())
                                    .proposedBy("replanner")
                                    .payload("recover")
                                    .risk(Risk.LOW)
                                    .metadata("summary", "recovery for " + e.id())
                                    .build())
                            .rationale("recover from rejection")
                            .build();
                }
            }
            return Replanner.Amendments.none();
        };

        ReplannerLoop.start(channel, replanner,
                ReplanTriggerPolicy.onRejection(),
                () -> ReplanContext.of("complete the workflow"));

        // Workflow:
        channel.append(proposed("a"));
        channel.markStatus("a", PlanEntry.Status.APPROVED, "", "PLAN_POLICY");
        // Simulate operator override:
        channel.markStatus("a", PlanEntry.Status.REJECTED, "second thoughts", "alice");

        EvolvingPlan plan = channel.get();
        PlanEntry recovery = plan.byId("recovery-for-a").orElse(null);
        assertNotNull(recovery, "Replanner should have added a recovery entry");
        assertEquals(PlanEntry.Status.PROPOSED, recovery.status());
        assertFalse(plan.byId("a").orElseThrow().isActive(),
                "Original entry should be terminal");
    }
}
