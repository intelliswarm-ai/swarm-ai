package ai.intelliswarm.swarmai.skill.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only on-disk persistence for {@link ContainerAuditEntry}.
 *
 * <p>Each entry is one line of compact JSON, so the log is greppable,
 * machine-parsable, and rotation-friendly without bringing in a JSON
 * library: we hand-encode a fixed-shape record. Reads parse the same
 * shape back deterministically.
 *
 * <h2>Default location</h2>
 * {@code ~/.swarmai/container-audit.jsonl} (override the default with
 * {@link #ContainerAuditPersistence(Path)}).
 *
 * <h2>Concurrency</h2>
 * Append writes use {@link StandardOpenOption#APPEND} which is atomic for
 * line-sized writes on POSIX/NTFS. Multiple JVMs writing to the same file
 * will not corrupt each other for typical entry sizes (well under PIPE_BUF).
 *
 * <h2>Rotation</h2>
 * Out of scope here — operators rotate via logrotate or cron the same way
 * they rotate any append-only log.
 */
public final class ContainerAuditPersistence {

    private static final Logger logger = LoggerFactory.getLogger(ContainerAuditPersistence.class);

    /** Default audit log location: {@code ~/.swarmai/container-audit.jsonl}. */
    public static Path defaultLogPath() {
        return Path.of(System.getProperty("user.home"), ".swarmai", "container-audit.jsonl");
    }

    private final Path logPath;

    public ContainerAuditPersistence() {
        this(defaultLogPath());
    }

    public ContainerAuditPersistence(Path logPath) {
        this.logPath = logPath;
    }

    public Path logPath() {
        return logPath;
    }

    /** Append one entry as a single JSON line. Best-effort — never throws. */
    public void append(ContainerAuditEntry entry) {
        try {
            ensureParent();
            String line = encode(entry) + "\n";
            Files.writeString(logPath, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            logger.warn("Failed to persist container audit entry to {}: {}", logPath, e.getMessage());
        }
    }

    /** Read every entry, oldest-first. Empty list if the file doesn't exist. */
    public List<ContainerAuditEntry> readAll() {
        if (!Files.exists(logPath)) return List.of();
        List<ContainerAuditEntry> entries = new ArrayList<>();
        try (var lines = Files.lines(logPath, StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                if (line.isBlank()) return;
                ContainerAuditEntry e = decode(line);
                if (e != null) entries.add(e);
            });
        } catch (IOException e) {
            logger.warn("Failed to read audit log {}: {}", logPath, e.getMessage());
        }
        return entries;
    }

    /** Wipe the audit log on disk — useful in tests + for explicit reset. */
    public void clear() {
        try {
            Files.deleteIfExists(logPath);
        } catch (IOException e) {
            logger.warn("Failed to clear audit log {}: {}", logPath, e.getMessage());
        }
    }

    private void ensureParent() throws IOException {
        Path parent = logPath.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
    }

    // ---------------------------------------------------------------------
    // Hand-rolled JSON encode/decode — fixed shape, no library dependency.
    // The encoder emits 11 keys in stable order so the file is greppable;
    // the decoder is tolerant of missing keys (defaults applied).
    // ---------------------------------------------------------------------

    static String encode(ContainerAuditEntry e) {
        StringBuilder sb = new StringBuilder("{");
        appendKv(sb, "at", e.at().toString(), true);
        appendKv(sb, "container", e.containerName(), false);
        appendKv(sb, "image", e.image(), false);
        appendKv(sb, "network", e.network(), false);
        appendKv(sb, "memory", e.memory() == null ? "" : e.memory(), false);
        appendKvNum(sb, "timeoutSeconds", e.timeoutSeconds());
        appendKvNum(sb, "durationMs", e.durationMs());
        appendKvNum(sb, "exitCode", e.exitCode());
        appendKv(sb, "stderrPreview", e.stderrPreview(), false);
        appendKv(sb, "policyOutcome", e.policyOutcome(), false);
        sb.append("}");
        return sb.toString();
    }

    static ContainerAuditEntry decode(String json) {
        try {
            // Strip outer braces, then parse simple key:value pairs.
            String body = json.trim();
            if (!body.startsWith("{") || !body.endsWith("}")) return null;
            body = body.substring(1, body.length() - 1);

            Instant at = Instant.now();
            String container = "", image = "", network = "", memory = null;
            String stderrPreview = "", policyOutcome = "";
            long timeoutSeconds = 0, durationMs = 0;
            int exitCode = 0;

            // Simple state machine: scan for "key":<value>, where value is
            // either a quoted string (with backslash-escaped quotes) or a number.
            int i = 0;
            while (i < body.length()) {
                while (i < body.length() && (body.charAt(i) == ',' || body.charAt(i) == ' ')) i++;
                if (i >= body.length()) break;
                if (body.charAt(i) != '"') break;
                int keyStart = i + 1;
                int keyEnd = body.indexOf('"', keyStart);
                if (keyEnd < 0) break;
                String key = body.substring(keyStart, keyEnd);
                i = keyEnd + 1;
                while (i < body.length() && (body.charAt(i) == ':' || body.charAt(i) == ' ')) i++;
                String value;
                if (i < body.length() && body.charAt(i) == '"') {
                    // Quoted string — find unescaped closing quote.
                    int valStart = i + 1;
                    int valEnd = valStart;
                    StringBuilder sb = new StringBuilder();
                    while (valEnd < body.length()) {
                        char c = body.charAt(valEnd);
                        if (c == '\\' && valEnd + 1 < body.length()) {
                            char n = body.charAt(valEnd + 1);
                            switch (n) {
                                case '"' -> sb.append('"');
                                case '\\' -> sb.append('\\');
                                case 'n' -> sb.append('\n');
                                case 't' -> sb.append('\t');
                                default -> sb.append(n);
                            }
                            valEnd += 2;
                        } else if (c == '"') {
                            break;
                        } else {
                            sb.append(c);
                            valEnd++;
                        }
                    }
                    value = sb.toString();
                    i = valEnd + 1;
                } else {
                    // Number / null — read until comma or end.
                    int valStart = i;
                    while (i < body.length() && body.charAt(i) != ',') i++;
                    value = body.substring(valStart, i).trim();
                }
                switch (key) {
                    case "at" -> { try { at = Instant.parse(value); } catch (Exception ignored) {} }
                    case "container" -> container = value;
                    case "image" -> image = value;
                    case "network" -> network = value;
                    case "memory" -> memory = value.isEmpty() ? null : value;
                    case "stderrPreview" -> stderrPreview = value;
                    case "policyOutcome" -> policyOutcome = value;
                    case "timeoutSeconds" -> timeoutSeconds = parseLong(value);
                    case "durationMs" -> durationMs = parseLong(value);
                    case "exitCode" -> exitCode = (int) parseLong(value);
                }
            }
            return new ContainerAuditEntry(
                    at, container, image, network, memory,
                    timeoutSeconds, durationMs, exitCode,
                    stderrPreview, policyOutcome);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void appendKv(StringBuilder sb, String key, String value, boolean first) {
        if (!first) sb.append(',');
        sb.append('"').append(key).append('"').append(':').append('"').append(escape(value)).append('"');
    }

    private static void appendKvNum(StringBuilder sb, String key, long value) {
        sb.append(',').append('"').append(key).append('"').append(':').append(value);
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0L; }
    }
}
