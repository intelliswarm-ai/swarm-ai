package ai.intelliswarm.swarmai.agent.subagent;

import ai.intelliswarm.swarmai.state.plan.InMemoryPlanChannelAccessor;
import ai.intelliswarm.swarmai.state.plan.PlanEntry;
import ai.intelliswarm.swarmai.state.plan.Risk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PlanAttachingSubagentSpawner Tests")
class PlanAttachingSubagentSpawnerTest {

    private static PlanEntry entry(String id) {
        return PlanEntry.builder()
                .id(id).proposedBy("tool:x").payload("op").risk(Risk.MEDIUM)
                .metadata("summary", "do " + id)
                .build();
    }

    /** Test stub spawner: returns a pre-completed subagent with the canned result. */
    private static SubagentSpawner stubSpawner(SubagentResult canned) {
        return spec -> new ImmediateSubagent(spec.id(), canned);
    }

    private static final class ImmediateSubagent implements Subagent {
        private final String id;
        private final SubagentResult result;

        ImmediateSubagent(String id, SubagentResult result) {
            this.id = id;
            this.result = result;
        }

        @Override public String id() { return id; }
        @Override public SubagentStatus status() { return result.status(); }
        @Override public SubagentResult awaitResult(Duration waitUntil) { return result; }
        @Override public void cancel() { }
    }

    @Test
    @DisplayName("legacy: spec without attachToEntryId is a pass-through (no plan mutation)")
    void noAttachKeyPassesThrough() {
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        channel.append(entry("a"));

        SubagentResult canned = SubagentResult.success("sub-1", "verdict text",
                SubagentResult.metricsBuilder().put("durationMs", 100L).build());
        PlanAttachingSubagentSpawner spawner = new PlanAttachingSubagentSpawner(
                stubSpawner(canned), channel);

        SubagentSpec spec = SubagentSpec.builder()
                .id("sub-1").type("auditor")
                .userPrompt("audit me")
                .build();

        Subagent sub = spawner.spawn(spec);
        SubagentResult r = sub.awaitResult(Duration.ofSeconds(1));
        assertEquals(SubagentStatus.COMPLETED, r.status());

        // Channel entry's metadata is untouched
        PlanEntry a = channel.get().byId("a").orElseThrow();
        assertNull(a.metadata().get("subagent.auditor"));
    }

    @Test
    @DisplayName("with attachToEntryId set, the subagent's result attaches to the target entry's metadata")
    void attachOnSuccess() {
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        channel.append(entry("prod-cut"));

        SubagentResult canned = SubagentResult.success("sub-1",
                "Recommend ROLLBACK due to error spike",
                SubagentResult.metricsBuilder()
                        .put("durationMs", 4186L)
                        .put("type", "auditor")
                        .build());
        PlanAttachingSubagentSpawner spawner = new PlanAttachingSubagentSpawner(
                stubSpawner(canned), channel);

        SubagentSpec spec = SubagentSpec.builder()
                .id("sub-1").type("auditor")
                .userPrompt("should we ship?")
                .parentMetadata(PlanAttachingSubagentSpawner.ATTACH_KEY, "prod-cut")
                .build();

        Subagent sub = spawner.spawn(spec);
        sub.awaitResult(Duration.ofSeconds(1));

        PlanEntry prodCut = channel.get().byId("prod-cut").orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> attached = (Map<String, Object>) prodCut.metadata().get("subagent.auditor");
        assertNotNull(attached, "Attachment should land under 'subagent.auditor'");
        assertEquals("sub-1", attached.get("id"));
        assertEquals("COMPLETED", attached.get("status"));
        assertEquals("Recommend ROLLBACK due to error spike", attached.get("output"));
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) attached.get("metrics");
        assertEquals(4186L, metrics.get("durationMs"));
        assertEquals("auditor", metrics.get("type"));
    }

    @Test
    @DisplayName("preserves the original entry's other metadata — only adds the subagent. key")
    void preservesOtherMetadata() {
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        // Entry has its own metadata before attachment
        PlanEntry original = PlanEntry.builder()
                .id("e1").proposedBy("tool:x").payload("op").risk(Risk.LOW)
                .metadata("summary", "do thing")
                .metadata("apply", true)
                .build();
        channel.append(original);

        SubagentResult canned = SubagentResult.success("sub-1", "ok", Map.of());
        PlanAttachingSubagentSpawner spawner = new PlanAttachingSubagentSpawner(
                stubSpawner(canned), channel);

        SubagentSpec spec = SubagentSpec.builder()
                .id("sub-1").type("reviewer")
                .userPrompt("x")
                .parentMetadata(PlanAttachingSubagentSpawner.ATTACH_KEY, "e1")
                .build();

        spawner.spawn(spec).awaitResult(Duration.ofSeconds(1));

        PlanEntry after = channel.get().byId("e1").orElseThrow();
        assertEquals("do thing", after.metadata().get("summary"));
        assertEquals(Boolean.TRUE, after.metadata().get("apply"));
        assertNotNull(after.metadata().get("subagent.reviewer"));
    }

    @Test
    @DisplayName("preserves the original entry's status and decidedBy — attachment is metadata-only")
    void preservesEntryStatus() {
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        channel.append(entry("e1"));
        channel.markStatus("e1", PlanEntry.Status.APPROVED, "operator", "alice");

        SubagentResult canned = SubagentResult.success("sub-1", "ok", Map.of());
        PlanAttachingSubagentSpawner spawner = new PlanAttachingSubagentSpawner(
                stubSpawner(canned), channel);

        SubagentSpec spec = SubagentSpec.builder()
                .id("sub-1").type("auditor").userPrompt("x")
                .parentMetadata(PlanAttachingSubagentSpawner.ATTACH_KEY, "e1")
                .build();

        spawner.spawn(spec).awaitResult(Duration.ofSeconds(1));

        PlanEntry after = channel.get().byId("e1").orElseThrow();
        assertEquals(PlanEntry.Status.APPROVED, after.status(),
                "Subagent attachment must not change the entry's lifecycle status");
        assertEquals("alice", after.decidedBy());
    }

    @Test
    @DisplayName("missing target entry — attachment is silently skipped, awaitResult still returns the result")
    void missingTargetEntryNoOp() {
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        // No entry "nope" in the channel

        SubagentResult canned = SubagentResult.success("sub-1", "ok", Map.of());
        PlanAttachingSubagentSpawner spawner = new PlanAttachingSubagentSpawner(
                stubSpawner(canned), channel);

        SubagentSpec spec = SubagentSpec.builder()
                .id("sub-1").type("auditor").userPrompt("x")
                .parentMetadata(PlanAttachingSubagentSpawner.ATTACH_KEY, "nope")
                .build();

        SubagentResult r = spawner.spawn(spec).awaitResult(Duration.ofSeconds(1));
        assertEquals(SubagentStatus.COMPLETED, r.status(),
                "awaitResult must return the result even if attachment fails");
    }

    @Test
    @DisplayName("blank attachToEntryId is treated as no attachment (pass-through)")
    void blankAttachIdPassesThrough() {
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        channel.append(entry("e1"));

        SubagentResult canned = SubagentResult.success("sub-1", "ok", Map.of());
        PlanAttachingSubagentSpawner spawner = new PlanAttachingSubagentSpawner(
                stubSpawner(canned), channel);

        SubagentSpec spec = SubagentSpec.builder()
                .id("sub-1").type("auditor").userPrompt("x")
                .parentMetadata(PlanAttachingSubagentSpawner.ATTACH_KEY, "")
                .build();

        spawner.spawn(spec).awaitResult(Duration.ofSeconds(1));

        PlanEntry after = channel.get().byId("e1").orElseThrow();
        assertNull(after.metadata().get("subagent.auditor"),
                "Blank attachToEntryId should not attach anything");
    }

    @Test
    @DisplayName("attachment is idempotent — second awaitResult does not double-write")
    void attachmentIsIdempotent() {
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        channel.append(entry("e1"));

        SubagentResult canned = SubagentResult.success("sub-1", "first", Map.of());
        PlanAttachingSubagentSpawner spawner = new PlanAttachingSubagentSpawner(
                stubSpawner(canned), channel);

        SubagentSpec spec = SubagentSpec.builder()
                .id("sub-1").type("auditor").userPrompt("x")
                .parentMetadata(PlanAttachingSubagentSpawner.ATTACH_KEY, "e1")
                .build();

        Subagent sub = spawner.spawn(spec);
        sub.awaitResult(Duration.ofSeconds(1));   // attach
        sub.awaitResult(Duration.ofSeconds(1));   // no-op
        sub.awaitResult(Duration.ofSeconds(1));   // no-op

        PlanEntry after = channel.get().byId("e1").orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> attached = (Map<String, Object>) after.metadata().get("subagent.auditor");
        assertNotNull(attached);
        assertEquals("first", attached.get("output"),
                "Attachment must reflect the first-observed result; subsequent calls are no-ops");
    }

    @Test
    @DisplayName("FAILED subagent results are also attached (so the failure shows up in the audit trail)")
    void attachOnFailure() {
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        channel.append(entry("e1"));

        SubagentResult failed = SubagentResult.failure("sub-1", "RuntimeException: boom",
                SubagentResult.metricsBuilder().put("errorClass", "java.lang.RuntimeException").build());
        PlanAttachingSubagentSpawner spawner = new PlanAttachingSubagentSpawner(
                stubSpawner(failed), channel);

        SubagentSpec spec = SubagentSpec.builder()
                .id("sub-1").type("auditor").userPrompt("x")
                .parentMetadata(PlanAttachingSubagentSpawner.ATTACH_KEY, "e1")
                .build();

        spawner.spawn(spec).awaitResult(Duration.ofSeconds(1));

        PlanEntry after = channel.get().byId("e1").orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> attached = (Map<String, Object>) after.metadata().get("subagent.auditor");
        assertNotNull(attached);
        assertEquals("FAILED", attached.get("status"));
        assertTrue(((String) attached.get("output")).contains("boom"));
    }

    @Test
    @DisplayName("attachments to multiple subagents on the same entry don't collide (different type → different key)")
    void multipleSubagentsCoexist() {
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        channel.append(entry("e1"));

        SubagentResult auditOk = SubagentResult.success("sub-a", "audit ok", Map.of());
        SubagentResult reviewOk = SubagentResult.success("sub-r", "review ok", Map.of());

        PlanAttachingSubagentSpawner audit = new PlanAttachingSubagentSpawner(
                stubSpawner(auditOk), channel);
        PlanAttachingSubagentSpawner review = new PlanAttachingSubagentSpawner(
                stubSpawner(reviewOk), channel);

        SubagentSpec auditSpec = SubagentSpec.builder()
                .id("sub-a").type("auditor").userPrompt("x")
                .parentMetadata(PlanAttachingSubagentSpawner.ATTACH_KEY, "e1").build();
        SubagentSpec reviewSpec = SubagentSpec.builder()
                .id("sub-r").type("reviewer").userPrompt("x")
                .parentMetadata(PlanAttachingSubagentSpawner.ATTACH_KEY, "e1").build();

        audit.spawn(auditSpec).awaitResult(Duration.ofSeconds(1));
        review.spawn(reviewSpec).awaitResult(Duration.ofSeconds(1));

        PlanEntry after = channel.get().byId("e1").orElseThrow();
        assertNotNull(after.metadata().get("subagent.auditor"));
        assertNotNull(after.metadata().get("subagent.reviewer"));
    }

    @Test
    @DisplayName("PlanEntry.withMetadata replaces the map; withMetadataPut merges one key")
    void planEntryMetadataApi() {
        PlanEntry e = entry("a");
        // Original has summary="do a"
        PlanEntry replaced = e.withMetadata(Map.of("only", "key"));
        assertEquals(Map.of("only", "key"), replaced.metadata());

        PlanEntry merged = e.withMetadataPut("extra", 42);
        assertEquals("do a", merged.metadata().get("summary"));
        assertEquals(42, merged.metadata().get("extra"));

        // status, decidedBy, etc. preserved
        assertEquals(e.status(), merged.status());
        assertEquals(e.proposedBy(), merged.proposedBy());
    }

    @Test
    @DisplayName("withMetadataPut on null key throws")
    void withMetadataPutNullKey() {
        PlanEntry e = entry("a");
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
                () -> e.withMetadataPut(null, "x"));
    }
}
