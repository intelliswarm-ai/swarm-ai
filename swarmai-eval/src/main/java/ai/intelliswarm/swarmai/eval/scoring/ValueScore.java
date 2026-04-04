package ai.intelliswarm.swarmai.eval.scoring;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Aggregate Framework Value Score computed from all scenario results.
 * This is the primary quality metric for the framework.
 *
 * @param overallScore      0-100, weighted across categories
 * @param breakdown         Per-category scores
 * @param scenarioResults   Individual scenario results
 * @param passRate          Percentage of scenarios that passed
 * @param totalScenarios    Number of scenarios executed
 * @param failedScenarios   Number of scenarios that failed
 * @param computedAt        When this score was computed
 * @param version           Framework version being evaluated
 */
public record ValueScore(
        double overallScore,
        Map<String, Double> breakdown,
        List<ScenarioResult> scenarioResults,
        double passRate,
        int totalScenarios,
        int failedScenarios,
        Instant computedAt,
        String version
) {
    /** Release gate: score must be >= 70 to ship. */
    public boolean meetsReleaseThreshold() {
        return overallScore >= 70.0;
    }

    /** Returns scenario results that failed. */
    public List<ScenarioResult> failures() {
        return scenarioResults.stream()
                .filter(r -> !r.passed())
                .toList();
    }

    /** Returns scenario results for a specific category. */
    public List<ScenarioResult> byCategory(String category) {
        return scenarioResults.stream()
                .filter(r -> category.equals(r.category()))
                .toList();
    }
}
