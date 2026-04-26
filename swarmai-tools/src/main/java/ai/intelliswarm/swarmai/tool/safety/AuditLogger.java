package ai.intelliswarm.swarmai.tool.safety;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Append-only JSONL audit log for tool mutations. One line per logged event:
 *
 * <pre>{@code
 * {"ts":"2026-04-26T20:14:55.123Z","event":"REQUEST","plan":{...}}
 * {"ts":"...","event":"DECISION","plan":{...},"decision":{"approved":true,"by":"console","reason":"..."}}
 * {"ts":"...","event":"OUTCOME","planSummary":"Move ...","outcome":"ok: Moved ..."}
 * }</pre>
 *
 * <p>Files live under a configured {@code logDir} as {@code audit-YYYY-MM-DD.log}
 * so the file rotates at UTC midnight. Writes are synchronous and fsync-on-close
 * (each call opens the file, appends one line, closes) — good for a low-volume
 * mutation log; if you ever push thousands of events per second, swap in a
 * proper buffered async sink.
 */
public class AuditLogger {

    private static final Logger logger = LoggerFactory.getLogger(AuditLogger.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.of("UTC"));

    private final Path logDir;
    private final Clock clock;

    public AuditLogger(Path logDir) {
        this(logDir, Clock.systemUTC());
    }

    public AuditLogger(Path logDir, Clock clock) {
        this.logDir = Objects.requireNonNull(logDir, "logDir");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Log that a tool is about to ask for approval for the given plan. */
    public void logRequest(MutationPlan plan) {
        if (plan == null) return;
        Map<String, Object> entry = baseEntry("REQUEST");
        entry.put("plan", planToMap(plan));
        write(entry);
    }

    /** Log the outcome of an approval decision. */
    public void logDecision(MutationPlan plan, MutationGuard.Decision decision) {
        if (plan == null || decision == null) return;
        Map<String, Object> entry = baseEntry("DECISION");
        entry.put("plan", planToMap(plan));
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("approved", decision.approved());
        d.put("by", decision.decidedBy());
        d.put("reason", decision.reason());
        entry.put("decision", d);
        write(entry);
    }

    /** Log the result of executing the plan (after a successful approval). */
    public void logOutcome(MutationPlan plan, String outcome) {
        if (plan == null) return;
        Map<String, Object> entry = baseEntry("OUTCOME");
        entry.put("tool", plan.toolName());
        entry.put("planSummary", plan.summary());
        entry.put("outcome", outcome == null ? "" : outcome);
        write(entry);
    }

    /** The file the next log line will land in, given the current clock. Useful for tests. */
    public Path currentLogFile() {
        return logDir.resolve("audit-" + DATE.format(clock.instant()) + ".log");
    }

    // ---- internals ----

    private Map<String, Object> baseEntry(String event) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("ts", clock.instant().toString());
        e.put("event", event);
        return e;
    }

    private static Map<String, Object> planToMap(MutationPlan plan) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tool", plan.toolName());
        m.put("riskLevel", plan.riskLevel().name());
        m.put("summary", plan.summary());
        m.put("opCount", plan.opCount());
        m.put("ops", plan.ops().stream().map(op -> {
            Map<String, Object> opMap = new LinkedHashMap<>();
            opMap.put("type", op.type());
            opMap.put("target", op.target());
            if (!op.details().isEmpty()) opMap.put("details", op.details());
            return opMap;
        }).collect(Collectors.toUnmodifiableList()));
        if (!plan.metadata().isEmpty()) m.put("metadata", plan.metadata());
        return m;
    }

    private void write(Map<String, Object> entry) {
        try {
            Files.createDirectories(logDir);
            String line = MAPPER.writeValueAsString(entry) + System.lineSeparator();
            Files.writeString(currentLogFile(), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (JsonProcessingException e) {
            logger.warn("Failed to serialise audit entry: {}", e.getMessage());
        } catch (IOException e) {
            logger.warn("Failed to write audit entry to {}: {}", logDir, e.getMessage());
        }
    }

    /** Read all entries from today's log file, parsed back into maps. Convenience for tests/diagnostics. */
    public List<Map<String, Object>> readToday() throws IOException {
        Path file = currentLogFile();
        if (!Files.exists(file)) return List.of();
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .filter(s -> !s.isBlank())
                .map(line -> {
                    try {
                        return MAPPER.readValue(line, new TypeReference<Map<String, Object>>() {});
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .collect(Collectors.toUnmodifiableList());
    }
}
