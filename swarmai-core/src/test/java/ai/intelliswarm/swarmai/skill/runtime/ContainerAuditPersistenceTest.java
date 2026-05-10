package ai.intelliswarm.swarmai.skill.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContainerAuditPersistenceTest {

    @Test
    @DisplayName("encode → decode preserves every field")
    void roundTripStable() {
        ContainerAuditEntry original = new ContainerAuditEntry(
                Instant.parse("2026-05-09T22:30:00Z"),
                "swarmai-skill-abcd1234",
                "swarmai-skill-46e9df8b288f",
                "bridge",
                "512m",
                600,
                87577,
                0,
                "",
                "OK");
        String json = ContainerAuditPersistence.encode(original);
        ContainerAuditEntry decoded = ContainerAuditPersistence.decode(json);
        assertNotNull(decoded);
        assertEquals(original.at(), decoded.at());
        assertEquals(original.containerName(), decoded.containerName());
        assertEquals(original.image(), decoded.image());
        assertEquals(original.network(), decoded.network());
        assertEquals(original.memory(), decoded.memory());
        assertEquals(original.timeoutSeconds(), decoded.timeoutSeconds());
        assertEquals(original.durationMs(), decoded.durationMs());
        assertEquals(original.exitCode(), decoded.exitCode());
        assertEquals(original.policyOutcome(), decoded.policyOutcome());
    }

    @Test
    @DisplayName("encoded line contains every key in stable order")
    void encodedShape() {
        ContainerAuditEntry e = new ContainerAuditEntry(
                Instant.parse("2026-05-09T22:30:00Z"),
                "c1", "img1", "bridge", "256m", 60, 1500, 0, "", "OK");
        String json = ContainerAuditPersistence.encode(e);
        assertTrue(json.startsWith("{\"at\":"));
        assertTrue(json.contains("\"container\":\"c1\""));
        assertTrue(json.contains("\"image\":\"img1\""));
        assertTrue(json.contains("\"network\":\"bridge\""));
        assertTrue(json.contains("\"timeoutSeconds\":60"));
        assertTrue(json.contains("\"durationMs\":1500"));
        assertTrue(json.contains("\"exitCode\":0"));
        assertTrue(json.contains("\"policyOutcome\":\"OK\""));
        assertTrue(json.endsWith("}"));
    }

    @Test
    @DisplayName("escaping handles quotes, backslashes, newlines without corruption")
    void escapeRoundTrip() {
        ContainerAuditEntry e = new ContainerAuditEntry(
                Instant.parse("2026-05-09T22:30:00Z"),
                "c1", "img1", "bridge", null, 60, 100, 1,
                "stderr line 1\nstderr \"line\" 2\n\\path\\to\\file",
                "OK");
        String json = ContainerAuditPersistence.encode(e);
        ContainerAuditEntry back = ContainerAuditPersistence.decode(json);
        assertNotNull(back);
        assertEquals(e.stderrPreview(), back.stderrPreview());
    }

    @Test
    @DisplayName("append + readAll round-trips multiple entries to disk")
    void appendThenRead(@TempDir Path tmp) {
        Path log = tmp.resolve("audit.jsonl");
        ContainerAuditPersistence p = new ContainerAuditPersistence(log);

        ContainerAuditEntry e1 = new ContainerAuditEntry(
                Instant.parse("2026-05-09T22:30:00Z"), "c1", "img1", "bridge",
                "256m", 60, 1500, 0, "", "OK");
        ContainerAuditEntry e2 = new ContainerAuditEntry(
                Instant.parse("2026-05-09T22:31:00Z"), "c2", "img2", "none",
                null, 30, 500, 1, "boom", "OK");
        p.append(e1);
        p.append(e2);

        List<ContainerAuditEntry> read = p.readAll();
        assertEquals(2, read.size());
        assertEquals("c1", read.get(0).containerName());
        assertEquals("c2", read.get(1).containerName());
        assertEquals("boom", read.get(1).stderrPreview());
        assertEquals(1, read.get(1).exitCode());
    }

    @Test
    @DisplayName("readAll on missing file returns empty list (no exception)")
    void readMissingFile(@TempDir Path tmp) {
        ContainerAuditPersistence p = new ContainerAuditPersistence(tmp.resolve("nope.jsonl"));
        assertTrue(p.readAll().isEmpty());
    }

    @Test
    @DisplayName("clear() deletes the log file idempotently")
    void clearIdempotent(@TempDir Path tmp) {
        Path log = tmp.resolve("audit.jsonl");
        ContainerAuditPersistence p = new ContainerAuditPersistence(log);
        p.append(new ContainerAuditEntry(Instant.now(), "c", "i", "n", null, 1, 1, 0, "", "OK"));
        p.clear();
        p.clear(); // second time is fine
        assertTrue(p.readAll().isEmpty());
    }

    @Test
    @DisplayName("default location is ~/.swarmai/container-audit.jsonl")
    void defaultLocation() {
        Path d = ContainerAuditPersistence.defaultLogPath();
        assertTrue(d.toString().endsWith(".swarmai/container-audit.jsonl")
                || d.toString().endsWith(".swarmai\\container-audit.jsonl"),
                "default path: " + d);
    }

    @Test
    @DisplayName("malformed line is skipped, not fatal")
    void malformedLineSkipped(@TempDir Path tmp) throws Exception {
        Path log = tmp.resolve("audit.jsonl");
        java.nio.file.Files.writeString(log,
                "this is not json\n" +
                ContainerAuditPersistence.encode(new ContainerAuditEntry(
                        Instant.parse("2026-05-09T22:30:00Z"), "c1", "img1", "bridge",
                        null, 30, 100, 0, "", "OK")) + "\n" +
                "{broken\n");
        ContainerAuditPersistence p = new ContainerAuditPersistence(log);
        List<ContainerAuditEntry> read = p.readAll();
        // Only the well-formed line survives.
        assertEquals(1, read.size());
        assertEquals("c1", read.get(0).containerName());
    }
}
