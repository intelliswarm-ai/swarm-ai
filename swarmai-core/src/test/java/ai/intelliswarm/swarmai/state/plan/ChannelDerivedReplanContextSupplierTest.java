package ai.intelliswarm.swarmai.state.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ChannelDerivedReplanContextSupplier Tests")
class ChannelDerivedReplanContextSupplierTest {

    private static PlanEntry entry(String id) {
        return PlanEntry.builder()
                .id(id).proposedBy("tool:x").payload("op-" + id)
                .risk(Risk.LOW)
                .metadata("summary", "do " + id)
                .build();
    }

    @Test
    @DisplayName("empty plan yields empty observations and the configured goal")
    void emptyPlan() {
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        var supplier = ChannelDerivedReplanContextSupplier.of(channel, "do the thing");
        ReplanContext ctx = supplier.get();
        assertEquals("do the thing", ctx.goal());
        assertTrue(ctx.observations().isEmpty());
    }

    @Test
    @DisplayName("only terminal statuses (EXECUTED/REJECTED) appear by default")
    void onlyTerminalByDefault() {
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        // a: PROPOSED, b: APPROVED, c: EXECUTED, d: REJECTED, e: SUPERSEDED
        channel.append(entry("a"));
        channel.append(entry("b"));
        channel.markStatus("b", PlanEntry.Status.APPROVED, "ok");
        channel.append(entry("c"));
        channel.markStatus("c", PlanEntry.Status.APPROVED, "ok");
        channel.markStatus("c", PlanEntry.Status.EXECUTED, "ran");
        channel.append(entry("d"));
        channel.markStatus("d", PlanEntry.Status.REJECTED, "denied");
        channel.append(entry("e"));
        channel.markStatus("e", PlanEntry.Status.SUPERSEDED, "replanner");

        var supplier = ChannelDerivedReplanContextSupplier.of(channel, "goal");
        List<String> obs = supplier.get().observations();

        // c (EXECUTED) and d (REJECTED) — chronological order
        assertEquals(2, obs.size());
        assertTrue(obs.get(0).startsWith("executed"));
        assertTrue(obs.get(1).startsWith("rejected"));
    }

    @Test
    @DisplayName("observations are returned in chronological order (oldest first)")
    void chronologicalOrder() {
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        for (int i = 1; i <= 3; i++) {
            channel.append(entry("e" + i));
            channel.markStatus("e" + i, PlanEntry.Status.APPROVED, "");
            channel.markStatus("e" + i, PlanEntry.Status.EXECUTED, "ran-" + i);
        }

        var supplier = ChannelDerivedReplanContextSupplier.of(channel, "goal");
        List<String> obs = supplier.get().observations();

        assertEquals(3, obs.size());
        assertTrue(obs.get(0).contains("ran-1"));
        assertTrue(obs.get(1).contains("ran-2"));
        assertTrue(obs.get(2).contains("ran-3"));
    }

    @Test
    @DisplayName("maxObservations caps the number of observations returned")
    void maxObservationsCap() {
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        for (int i = 1; i <= 8; i++) {
            channel.append(entry("e" + i));
            channel.markStatus("e" + i, PlanEntry.Status.APPROVED, "");
            channel.markStatus("e" + i, PlanEntry.Status.EXECUTED, "ran-" + i);
        }

        var supplier = ChannelDerivedReplanContextSupplier.builder(channel)
                .goal("goal").maxObservations(3).build();
        List<String> obs = supplier.get().observations();

        // Cap=3 → keep the 3 most recent terminal entries (e6, e7, e8)
        assertEquals(3, obs.size());
        assertTrue(obs.get(0).contains("ran-6"));
        assertTrue(obs.get(1).contains("ran-7"));
        assertTrue(obs.get(2).contains("ran-8"));
    }

    @Test
    @DisplayName("maxObservations=0 returns no observations even when plan is non-empty")
    void zeroCapReturnsNoObservations() {
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        channel.append(entry("a"));
        channel.markStatus("a", PlanEntry.Status.APPROVED, "");
        channel.markStatus("a", PlanEntry.Status.EXECUTED, "ran");

        var supplier = ChannelDerivedReplanContextSupplier.builder(channel)
                .goal("goal").maxObservations(0).build();
        assertTrue(supplier.get().observations().isEmpty());
    }

    @Test
    @DisplayName("trackedStatuses can include SUPERSEDED for replanners that care")
    void trackSupersededStatus() {
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        channel.append(entry("a"));
        channel.markStatus("a", PlanEntry.Status.APPROVED, "");
        channel.markStatus("a", PlanEntry.Status.SUPERSEDED, "by replanner");

        var supplier = ChannelDerivedReplanContextSupplier.builder(channel)
                .goal("goal")
                .trackedStatuses(EnumSet.of(PlanEntry.Status.SUPERSEDED))
                .build();
        List<String> obs = supplier.get().observations();
        assertEquals(1, obs.size());
        assertTrue(obs.get(0).startsWith("superseded"));
    }

    @Test
    @DisplayName("custom formatter replaces the default observation rendering")
    void customFormatter() {
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        channel.append(entry("a"));
        channel.markStatus("a", PlanEntry.Status.APPROVED, "");
        channel.markStatus("a", PlanEntry.Status.EXECUTED, "ran");

        var supplier = ChannelDerivedReplanContextSupplier.builder(channel)
                .goal("goal")
                .formatter(e -> "id=" + e.id() + " status=" + e.status())
                .build();
        List<String> obs = supplier.get().observations();
        assertEquals(1, obs.size());
        assertEquals("id=a status=EXECUTED", obs.get(0));
    }

    @Test
    @DisplayName("default formatter includes summary, decidedBy, and reason")
    void defaultFormatterIncludesKeyFields() {
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        channel.append(entry("a"));
        channel.markStatus("a", PlanEntry.Status.APPROVED, "policy", "PLAN_POLICY");
        channel.markStatus("a", PlanEntry.Status.EXECUTED, "ran in 2s");

        var supplier = ChannelDerivedReplanContextSupplier.of(channel, "goal");
        String obs = supplier.get().observations().get(0);

        // The default formatter weaves these fields together
        assertTrue(obs.contains("executed"));
        assertTrue(obs.contains("do a"));               // summary
        assertTrue(obs.contains("PLAN_POLICY"));        // decidedBy
        assertTrue(obs.contains("ran in 2s"));          // reason
    }

    @Test
    @DisplayName("metadata flows through to the produced ReplanContext")
    void metadataPropagates() {
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        var supplier = ChannelDerivedReplanContextSupplier.builder(channel)
                .goal("goal")
                .metadata("env", "prod")
                .metadata("region", "us-east-1")
                .build();

        ReplanContext ctx = supplier.get();
        assertEquals("prod", ctx.metadata().get("env"));
        assertEquals("us-east-1", ctx.metadata().get("region"));
    }

    @Test
    @DisplayName("supplier is consistent with channel state — re-querying picks up new transitions")
    void supplierTracksLiveChannel() {
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        var supplier = ChannelDerivedReplanContextSupplier.of(channel, "goal");

        assertTrue(supplier.get().observations().isEmpty());

        channel.append(entry("a"));
        channel.markStatus("a", PlanEntry.Status.APPROVED, "");
        channel.markStatus("a", PlanEntry.Status.EXECUTED, "ran");

        assertEquals(1, supplier.get().observations().size());

        channel.append(entry("b"));
        channel.markStatus("b", PlanEntry.Status.APPROVED, "");
        channel.markStatus("b", PlanEntry.Status.REJECTED, "no");

        assertEquals(2, supplier.get().observations().size());
    }

    @Test
    @DisplayName("rejects negative maxObservations and null required fields")
    void invalidConfig() {
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        assertThrows(IllegalArgumentException.class,
                () -> ChannelDerivedReplanContextSupplier.builder(channel)
                        .maxObservations(-1).build());
        assertThrows(NullPointerException.class,
                () -> ChannelDerivedReplanContextSupplier.builder(channel)
                        .formatter(null).build());
        assertThrows(NullPointerException.class,
                () -> ChannelDerivedReplanContextSupplier.builder(null).build());
    }

    @Test
    @DisplayName("end-to-end with ReplannerLoop: replanner sees observations from prior transitions")
    void integratesWithReplannerLoop() {
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        java.util.concurrent.atomic.AtomicReference<List<String>> seenObs =
                new java.util.concurrent.atomic.AtomicReference<>();

        Replanner spy = (plan, ctx) -> {
            seenObs.set(ctx.observations());
            return Replanner.Amendments.none();
        };

        ReplannerLoop loop = ReplannerLoop.start(channel, spy,
                ReplanTriggerPolicy.onRejection(),
                ChannelDerivedReplanContextSupplier.of(channel, "ship safely"));

        // Drive a successful execution + a rejection — the replanner should fire on the rejection
        // and see BOTH transitions in its observations.
        channel.append(entry("a"));
        channel.markStatus("a", PlanEntry.Status.APPROVED, "");
        channel.markStatus("a", PlanEntry.Status.EXECUTED, "first ok");

        channel.append(entry("b"));
        channel.markStatus("b", PlanEntry.Status.APPROVED, "");
        channel.markStatus("b", PlanEntry.Status.REJECTED, "second failed");

        assertFalse(seenObs.get() == null,
                "Replanner should have been called on the rejection");
        assertEquals(2, seenObs.get().size(),
                "Replanner should see both terminal transitions in its observations");
        assertTrue(seenObs.get().get(0).contains("first ok"));
        assertTrue(seenObs.get().get(1).contains("second failed"));

        loop.close();
    }
}
