package ai.intelliswarm.swarmai.eval.scoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Computes the Framework Value Score from individual scenario results.
 *
 * Weighting:
 *   - Core capabilities:      25%
 *   - Multi-agent orchestration: 25%
 *   - Enterprise readiness:   20%
 *   - Resilience:             15%
 *   - DSL & configuration:    15%
 */
public class ValueScorer {

    private static final Logger logger = LoggerFactory.getLogger(ValueScorer.class);

    private static final Map<String, Double> CATEGORY_WEIGHTS = Map.of(
            "CORE", 0.25,
            "ORCHESTRATION", 0.25,
            "ENTERPRISE", 0.20,
            "RESILIENCE", 0.15,
            "DSL", 0.15
    );

    /**
     * Compute the aggregate value score from scenario results.
     */
    public ValueScore compute(List<ScenarioResult> results, String version) {
        if (results.isEmpty()) {
            return new ValueScore(0.0, Map.of(), results, 0.0, 0, 0,
                    Instant.now(), version);
        }

        // Group by category
        Map<String, List<ScenarioResult>> byCategory = results.stream()
                .collect(Collectors.groupingBy(ScenarioResult::category));

        // Compute per-category average score
        Map<String, Double> breakdown = new LinkedHashMap<>();
        for (Map.Entry<String, List<ScenarioResult>> entry : byCategory.entrySet()) {
            double avg = entry.getValue().stream()
                    .mapToDouble(ScenarioResult::score)
                    .average()
                    .orElse(0.0);
            breakdown.put(entry.getKey(), avg);
        }

        // Compute weighted overall score
        double overallScore = 0.0;
        double totalWeight = 0.0;
        for (Map.Entry<String, Double> entry : breakdown.entrySet()) {
            double weight = CATEGORY_WEIGHTS.getOrDefault(entry.getKey(), 0.10);
            overallScore += entry.getValue() * weight;
            totalWeight += weight;
        }
        if (totalWeight > 0) {
            overallScore = overallScore / totalWeight;
        }

        int total = results.size();
        int failed = (int) results.stream().filter(r -> !r.passed()).count();
        double passRate = total > 0 ? ((total - failed) * 100.0 / total) : 0.0;

        ValueScore score = new ValueScore(
                Math.round(overallScore * 10.0) / 10.0,
                breakdown,
                results,
                Math.round(passRate * 10.0) / 10.0,
                total,
                failed,
                Instant.now(),
                version
        );

        logger.info("Framework Value Score: {} (pass rate: {}%, {}/{} scenarios passed)",
                score.overallScore(), score.passRate(), total - failed, total);

        for (Map.Entry<String, Double> entry : breakdown.entrySet()) {
            logger.info("  {}: {}", entry.getKey(), Math.round(entry.getValue() * 10.0) / 10.0);
        }

        if (!score.meetsReleaseThreshold()) {
            logger.warn("VALUE SCORE BELOW RELEASE THRESHOLD (70): {}", score.overallScore());
        }

        return score;
    }
}
