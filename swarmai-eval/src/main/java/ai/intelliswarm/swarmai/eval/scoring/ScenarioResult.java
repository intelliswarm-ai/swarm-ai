package ai.intelliswarm.swarmai.eval.scoring;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Result of a single eval scenario execution.
 */
public record ScenarioResult(
        String scenarioId,
        String scenarioName,
        String category,
        boolean passed,
        double score,          // 0.0 - 100.0
        String message,        // Human-readable result description
        Duration duration,
        Instant executedAt,
        Map<String, Object> details
) {
    public static ScenarioResult pass(String id, String name, String category, double score,
                                       String message, Duration duration) {
        return new ScenarioResult(id, name, category, true, score, message, duration,
                Instant.now(), Map.of());
    }

    public static ScenarioResult pass(String id, String name, String category, double score,
                                       String message, Duration duration, Map<String, Object> details) {
        return new ScenarioResult(id, name, category, true, score, message, duration,
                Instant.now(), details);
    }

    public static ScenarioResult fail(String id, String name, String category,
                                       String message, Duration duration) {
        return new ScenarioResult(id, name, category, false, 0.0, message, duration,
                Instant.now(), Map.of());
    }

    public static ScenarioResult fail(String id, String name, String category,
                                       String message, Duration duration, Map<String, Object> details) {
        return new ScenarioResult(id, name, category, false, 0.0, message, duration,
                Instant.now(), details);
    }
}
