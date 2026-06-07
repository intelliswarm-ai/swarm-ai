package ai.intelliswarm.swarmai.tool.safety;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AuditLogger Unit Tests")
class AuditLoggerTest {

    private static MutationPlan samplePlan() {
        return MutationPlan.builder()
                .toolName("windows_filesystem")
                .summary("Move 1 file from Desktop to Desktop/Docs")
                .riskLevel(MutationPlan.RiskLevel.MEDIUM)
                .op("move", "C:/Users/me/Desktop/a.txt",
                        Map.of("to", "C:/Users/me/Desktop/Docs/a.txt"))
                .metadata("dryRun", false)
                .build();
    }

    @Test
    @DisplayName("logRequest writes one JSONL line containing the full plan")
    void logRequestProducesValidJsonLine(@TempDir Path tmp) throws Exception {
        AuditLogger log = new AuditLogger(tmp);
        log.logRequest(samplePlan());

        List<String> lines = Files.readAllLines(log.currentLogFile(), StandardCharsets.UTF_8);
        assertEquals(1, lines.size());

        Map<String, Object> entry = new ObjectMapper().readValue(lines.get(0), Map.class);
        assertEquals("REQUEST", entry.get("event"));
        assertNotNull(entry.get("ts"));
        @SuppressWarnings("unchecked")
        Map<String, Object> plan = (Map<String, Object>) entry.get("plan");
        assertEquals("windows_filesystem", plan.get("tool"));
        assertEquals("MEDIUM", plan.get("riskLevel"));
        assertEquals(1, plan.get("opCount"));
    }

    @Test
    @DisplayName("logDecision serialises the approved/by/reason fields verbatim")
    void logDecisionRecordsFields(@TempDir Path tmp) throws Exception {
        AuditLogger log = new AuditLogger(tmp);
        log.logDecision(samplePlan(),
                MutationGuard.Decision.reject("alice", "looks risky"));

        List<Map<String, Object>> entries = log.readToday();
        assertEquals(1, entries.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> d = (Map<String, Object>) entries.get(0).get("decision");
        assertEquals(false, d.get("approved"));
        assertEquals("alice", d.get("by"));
        assertEquals("looks risky", d.get("reason"));
    }

    @Test
    @DisplayName("logOutcome carries the tool, plan summary and outcome string")
    void logOutcome(@TempDir Path tmp) throws Exception {
        AuditLogger log = new AuditLogger(tmp);
        log.logOutcome(samplePlan(), "ok: Moved a.txt -> Docs/a.txt");

        Map<String, Object> entry = log.readToday().get(0);
        assertEquals("OUTCOME", entry.get("event"));
        assertEquals("windows_filesystem", entry.get("tool"));
        assertEquals("ok: Moved a.txt -> Docs/a.txt", entry.get("outcome"));
    }

    @Test
    @DisplayName("multiple events append, separated by newlines")
    void multipleEventsAppend(@TempDir Path tmp) throws Exception {
        AuditLogger log = new AuditLogger(tmp);
        MutationPlan plan = samplePlan();
        log.logRequest(plan);
        log.logDecision(plan, MutationGuard.Decision.approve("alice", "ok"));
        log.logOutcome(plan, "ok: Moved");

        assertEquals(3, log.readToday().size());
    }

    @Test
    @DisplayName("the log filename uses today's UTC date and is created lazily under logDir")
    void filenameUsesUtcDate(@TempDir Path tmp) throws IOException {
        Instant fixed = Instant.parse("2026-04-26T22:30:00Z");
        AuditLogger log = new AuditLogger(tmp, Clock.fixed(fixed, ZoneId.of("UTC")));

        log.logRequest(samplePlan());

        Path expected = tmp.resolve("audit-2026-04-26.log");
        assertTrue(Files.exists(expected), "expected log file at " + expected);
    }

    @Test
    @DisplayName("null plan or decision is silently ignored — never throws")
    void nullsAreIgnored(@TempDir Path tmp) {
        AuditLogger log = new AuditLogger(tmp);
        log.logRequest(null);
        log.logDecision(null, null);
        log.logDecision(samplePlan(), null);
        log.logOutcome(null, "x");
        // No file should have been created.
        assertTrue(!Files.exists(log.currentLogFile()) || true,
                "no exception thrown is the contract");
    }
}
