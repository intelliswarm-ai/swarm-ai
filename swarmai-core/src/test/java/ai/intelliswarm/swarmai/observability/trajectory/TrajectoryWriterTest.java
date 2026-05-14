package ai.intelliswarm.swarmai.observability.trajectory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TrajectoryWriterTest {

    @Test
    void successEntryAppendsToSuccessFile(@TempDir Path tempDir) throws Exception {
        TrajectoryWriter writer = new TrajectoryWriter(tempDir);
        writer.write(entry("run-1", true, "passed all assertions"));
        writer.write(entry("run-2", true, "passed all assertions"));

        Path successFile = writer.successFile();
        assertThat(successFile).exists();
        List<String> lines = Files.readAllLines(successFile);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).contains("\"runId\":\"run-1\"").contains("\"success\":true");
        assertThat(writer.successCount()).isEqualTo(2);
        assertThat(writer.failureCount()).isZero();
    }

    @Test
    void failureEntryAppendsToFailureFile(@TempDir Path tempDir) throws Exception {
        TrajectoryWriter writer = new TrajectoryWriter(tempDir);
        writer.write(entry("run-bad", false, "assertion failed at step 3"));
        writer.write(entry("run-ok", true, "ok"));

        assertThat(writer.successCount()).isEqualTo(1);
        assertThat(writer.failureCount()).isEqualTo(1);
        assertThat(Files.readAllLines(writer.failureFile())).hasSize(1);
        assertThat(Files.readAllLines(writer.successFile())).hasSize(1);
    }

    @Test
    void writerCreatesBaseDirIfMissing(@TempDir Path tempDir) throws Exception {
        Path nested = tempDir.resolve("not/yet/created");
        TrajectoryWriter writer = new TrajectoryWriter(nested);
        writer.write(entry("r", true, "ok"));
        assertThat(nested.toFile()).exists();
    }

    @Test
    void entriesAreJsonlOnePerLine(@TempDir Path tempDir) throws Exception {
        TrajectoryWriter writer = new TrajectoryWriter(tempDir);
        for (int i = 0; i < 5; i++) {
            writer.write(entry("r-" + i, true, "ok"));
        }
        List<String> lines = Files.readAllLines(writer.successFile());
        assertThat(lines).hasSize(5);
        for (String line : lines) {
            assertThat(line).doesNotContain("\n");
            assertThat(line).startsWith("{").endsWith("}");
        }
    }

    @Test
    void multipleTurnsRoundTripIntoJson(@TempDir Path tempDir) throws Exception {
        TrajectoryEntry entry = new TrajectoryEntry(
                "multi-turn", "agent-1", "Compute DCF for ACME",
                List.of(
                        TrajectoryEntry.Turn.user("What's ACME's intrinsic value?"),
                        TrajectoryEntry.Turn.assistant("Let me fetch the financials."),
                        TrajectoryEntry.Turn.tool("Revenue: $1.2B, EBITDA: $360M"),
                        TrajectoryEntry.Turn.assistant("Implied DCF value: $14B")),
                true, "all assertions passed",
                Instant.parse("2026-05-14T12:00:00Z"),
                Instant.parse("2026-05-14T12:00:42Z"),
                Map.of("model", "claude-opus-4-7", "inputTokens", 1200));
        TrajectoryWriter writer = new TrajectoryWriter(tempDir);
        writer.write(entry);
        String line = Files.readString(writer.successFile()).strip();
        assertThat(line).contains("\"role\":\"user\"")
                .contains("\"role\":\"assistant\"")
                .contains("\"role\":\"tool\"")
                .contains("Revenue: $1.2B")
                .contains("claude-opus-4-7");
    }

    private static TrajectoryEntry entry(String runId, boolean success, String reason) {
        return new TrajectoryEntry(runId, "agent-1", "test goal",
                List.of(TrajectoryEntry.Turn.user("hello"),
                        TrajectoryEntry.Turn.assistant("world")),
                success, reason,
                Instant.now(), Instant.now(), Map.of());
    }
}
