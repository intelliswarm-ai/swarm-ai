package ai.intelliswarm.swarmai.eval.scoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * Persists value scores across runs to track trends.
 * Stored as JSON at eval-results/history.json.
 */
public class ScoreHistory {

    private static final Logger logger = LoggerFactory.getLogger(ScoreHistory.class);
    private static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path historyFile;

    public ScoreHistory(Path historyFile) {
        this.historyFile = historyFile;
    }

    /**
     * Load history from disk. Returns empty list if file doesn't exist.
     */
    public List<HistoryEntry> load() {
        if (!Files.exists(historyFile)) {
            return new ArrayList<>();
        }
        try {
            HistoryWrapper wrapper = mapper.readValue(historyFile.toFile(), HistoryWrapper.class);
            return wrapper.runs != null ? new ArrayList<>(wrapper.runs) : new ArrayList<>();
        } catch (IOException e) {
            logger.warn("Failed to load score history from {}: {}", historyFile, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Append a new score to history and persist.
     */
    public void append(ValueScore score) {
        List<HistoryEntry> history = load();
        history.add(HistoryEntry.from(score));
        save(history);
        logger.info("Score history updated: {} entries, latest score: {}",
                history.size(), score.overallScore());
    }

    /**
     * Get the most recent entry, or empty if no history.
     */
    public Optional<HistoryEntry> latest() {
        List<HistoryEntry> history = load();
        return history.isEmpty() ? Optional.empty() : Optional.of(history.get(history.size() - 1));
    }

    private void save(List<HistoryEntry> history) {
        try {
            Files.createDirectories(historyFile.getParent());
            mapper.writeValue(historyFile.toFile(), new HistoryWrapper(history));
        } catch (IOException e) {
            logger.error("Failed to save score history to {}: {}", historyFile, e.getMessage());
        }
    }

    public record HistoryEntry(
            Instant date,
            String version,
            double valueScore,
            Map<String, Double> breakdown,
            int totalScenarios,
            int failedScenarios,
            double passRate
    ) {
        public static HistoryEntry from(ValueScore score) {
            return new HistoryEntry(
                    score.computedAt(),
                    score.version(),
                    score.overallScore(),
                    score.breakdown(),
                    score.totalScenarios(),
                    score.failedScenarios(),
                    score.passRate()
            );
        }
    }

    // Jackson wrapper
    static class HistoryWrapper {
        public List<HistoryEntry> runs;
        public HistoryWrapper() {}
        public HistoryWrapper(List<HistoryEntry> runs) { this.runs = runs; }
    }
}
