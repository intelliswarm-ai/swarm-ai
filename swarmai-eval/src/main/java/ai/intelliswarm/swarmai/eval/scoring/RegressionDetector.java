package ai.intelliswarm.swarmai.eval.scoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Compares the current value score against the previous run to detect regressions.
 */
public class RegressionDetector {

    private static final Logger logger = LoggerFactory.getLogger(RegressionDetector.class);

    /** Threshold: overall score drop > this triggers a regression alert. */
    private static final double OVERALL_REGRESSION_THRESHOLD = 5.0;

    /** Threshold: category score drop > this triggers a category regression alert. */
    private static final double CATEGORY_REGRESSION_THRESHOLD = 10.0;

    /**
     * Detect regressions between current and previous score.
     */
    public List<Regression> detect(ValueScore current, ScoreHistory.HistoryEntry previous) {
        List<Regression> regressions = new ArrayList<>();

        // Overall score regression
        double overallDelta = current.overallScore() - previous.valueScore();
        if (overallDelta < -OVERALL_REGRESSION_THRESHOLD) {
            Regression r = new Regression(
                    "OVERALL", Severity.CRITICAL,
                    String.format("Overall value score dropped %.1f points (%.1f -> %.1f)",
                            Math.abs(overallDelta), previous.valueScore(), current.overallScore()),
                    previous.valueScore(), current.overallScore()
            );
            regressions.add(r);
            logger.error("[REGRESSION] {}", r.message());
        }

        // Per-category regressions
        for (Map.Entry<String, Double> entry : current.breakdown().entrySet()) {
            String category = entry.getKey();
            double currentCatScore = entry.getValue();
            Double previousCatScore = previous.breakdown().get(category);

            if (previousCatScore != null) {
                double catDelta = currentCatScore - previousCatScore;
                if (catDelta < -CATEGORY_REGRESSION_THRESHOLD) {
                    Regression r = new Regression(
                            category, Severity.HIGH,
                            String.format("%s category dropped %.1f points (%.1f -> %.1f)",
                                    category, Math.abs(catDelta), previousCatScore, currentCatScore),
                            previousCatScore, currentCatScore
                    );
                    regressions.add(r);
                    logger.warn("[REGRESSION] {}", r.message());
                }
            }
        }

        // New failures (scenarios that passed before but fail now)
        if (current.failedScenarios() > previous.failedScenarios()) {
            int newFailures = current.failedScenarios() - previous.failedScenarios();
            Regression r = new Regression(
                    "FAILURES", Severity.HIGH,
                    String.format("%d new scenario failures (was %d, now %d)",
                            newFailures, previous.failedScenarios(), current.failedScenarios()),
                    (double) previous.failedScenarios(), (double) current.failedScenarios()
            );
            regressions.add(r);
            logger.warn("[REGRESSION] {}", r.message());
        }

        if (regressions.isEmpty()) {
            logger.info("No regressions detected (delta: {})", String.format("%+.1f", overallDelta));
        }

        return regressions;
    }

    /**
     * Detect regressions using history. Returns empty if no prior run exists.
     */
    public List<Regression> detect(ValueScore current, ScoreHistory history) {
        Optional<ScoreHistory.HistoryEntry> previous = history.latest();
        if (previous.isEmpty()) {
            logger.info("No previous score history -- skipping regression detection");
            return List.of();
        }
        return detect(current, previous.get());
    }

    public record Regression(
            String category,
            Severity severity,
            String message,
            double previousValue,
            double currentValue
    ) {}

    public enum Severity {
        CRITICAL, HIGH, MEDIUM, LOW
    }
}
