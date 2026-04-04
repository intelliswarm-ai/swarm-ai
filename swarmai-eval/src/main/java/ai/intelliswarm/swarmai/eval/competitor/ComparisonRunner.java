package ai.intelliswarm.swarmai.eval.competitor;

import ai.intelliswarm.swarmai.eval.report.EvalReportGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Runs objective comparisons across frameworks for a given application.
 * Collects results, identifies gaps, and generates improvement issues.
 *
 * <p>Usage:
 * <pre>
 * ComparisonRunner runner = new ComparisonRunner("vuln-patcher", resultsDir);
 * runner.addResult(swarmaiResult);
 * runner.addResult(langgraphResult);
 * runner.addResult(crewaiResult);
 * ComparisonReport report = runner.generateReport();
 * </pre>
 */
public class ComparisonRunner {

    private static final Logger logger = LoggerFactory.getLogger(ComparisonRunner.class);
    private static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final String application;
    private final Path outputDir;
    private final List<ComparisonResult> results = new ArrayList<>();

    public ComparisonRunner(String application, Path outputDir) {
        this.application = application;
        this.outputDir = outputDir;
    }

    public void addResult(ComparisonResult result) {
        results.add(result);
        logger.info("Added comparison result: {} on {} ({}ms, {}% success)",
                result.framework(), result.application(),
                result.executionTimeMs(), result.successRate());
    }

    /**
     * Generate comparison report and identify improvement areas.
     */
    public ComparisonReport generateReport() {
        if (results.isEmpty()) {
            return new ComparisonReport(application, List.of(), List.of(), Map.of());
        }

        // Find SwarmAI result
        ComparisonResult swarmai = results.stream()
                .filter(r -> "SwarmAI".equalsIgnoreCase(r.framework()))
                .findFirst()
                .orElse(null);

        // Identify gaps where SwarmAI underperforms
        List<ImprovementIssue> issues = new ArrayList<>();
        if (swarmai != null) {
            for (ComparisonResult competitor : results) {
                if ("SwarmAI".equalsIgnoreCase(competitor.framework())) continue;
                issues.addAll(identifyGaps(swarmai, competitor));
            }
        }

        // Compute per-metric winners
        Map<String, String> metricWinners = computeWinners();

        ComparisonReport report = new ComparisonReport(application, results, issues, metricWinners);

        // Persist
        saveReport(report);

        // Log summary
        logger.info("=== COMPARISON REPORT: {} ===", application);
        for (ComparisonResult r : results) {
            logger.info("  {}: {}ms, ${}, {}% success, enterprise={}/100",
                    r.framework(), r.executionTimeMs(),
                    String.format("%.4f", r.costUsd()),
                    r.successRate(), Math.round(r.enterpriseScore()));
        }
        if (!issues.isEmpty()) {
            logger.warn("  {} improvement issues identified:", issues.size());
            for (ImprovementIssue issue : issues) {
                logger.warn("    [{}] {}", issue.priority(), issue.title());
            }
        }

        return report;
    }

    private List<ImprovementIssue> identifyGaps(ComparisonResult swarmai, ComparisonResult competitor) {
        List<ImprovementIssue> issues = new ArrayList<>();

        // Latency gap
        if (swarmai.executionTimeMs() > competitor.executionTimeMs() * 1.10) {
            double gap = ((double) swarmai.executionTimeMs() / competitor.executionTimeMs() - 1) * 100;
            issues.add(new ImprovementIssue(
                    String.format("[COMPARISON] %s: SwarmAI %.0f%% slower than %s",
                            application, gap, competitor.framework()),
                    gap > 20 ? Priority.CRITICAL : gap > 10 ? Priority.HIGH : Priority.MEDIUM,
                    "performance", "executionTimeMs",
                    swarmai.executionTimeMs(), competitor.executionTimeMs(),
                    competitor.framework()
            ));
        }

        // Token efficiency gap
        if (swarmai.totalTokens() > competitor.totalTokens() * 1.15) {
            double gap = ((double) swarmai.totalTokens() / competitor.totalTokens() - 1) * 100;
            issues.add(new ImprovementIssue(
                    String.format("[COMPARISON] %s: SwarmAI uses %.0f%% more tokens than %s",
                            application, gap, competitor.framework()),
                    gap > 30 ? Priority.HIGH : Priority.MEDIUM,
                    "efficiency", "totalTokens",
                    swarmai.totalTokens(), competitor.totalTokens(),
                    competitor.framework()
            ));
        }

        // Success rate gap
        if (swarmai.successRate() < competitor.successRate() - 5) {
            issues.add(new ImprovementIssue(
                    String.format("[COMPARISON] %s: SwarmAI %.1f%% success vs %s %.1f%%",
                            application, swarmai.successRate(),
                            competitor.framework(), competitor.successRate()),
                    Priority.CRITICAL,
                    "reliability", "successRate",
                    swarmai.successRate(), competitor.successRate(),
                    competitor.framework()
            ));
        }

        // Quality gap
        if (swarmai.outputQualityScore() < competitor.outputQualityScore() - 0.5) {
            issues.add(new ImprovementIssue(
                    String.format("[COMPARISON] %s: SwarmAI quality %.1f vs %s %.1f",
                            application, swarmai.outputQualityScore(),
                            competitor.framework(), competitor.outputQualityScore()),
                    Priority.HIGH,
                    "quality", "outputQualityScore",
                    swarmai.outputQualityScore(), competitor.outputQualityScore(),
                    competitor.framework()
            ));
        }

        return issues;
    }

    private Map<String, String> computeWinners() {
        Map<String, String> winners = new LinkedHashMap<>();

        // Fastest
        results.stream().min(Comparator.comparingLong(ComparisonResult::executionTimeMs))
                .ifPresent(r -> winners.put("Fastest", r.framework()));

        // Most token efficient
        results.stream().min(Comparator.comparingLong(ComparisonResult::totalTokens))
                .ifPresent(r -> winners.put("Most Token Efficient", r.framework()));

        // Cheapest
        results.stream().min(Comparator.comparingDouble(ComparisonResult::costUsd))
                .ifPresent(r -> winners.put("Cheapest", r.framework()));

        // Highest success rate
        results.stream().max(Comparator.comparingDouble(ComparisonResult::successRate))
                .ifPresent(r -> winners.put("Most Reliable", r.framework()));

        // Best quality
        results.stream().max(Comparator.comparingDouble(ComparisonResult::outputQualityScore))
                .ifPresent(r -> winners.put("Best Quality", r.framework()));

        // Most enterprise-ready
        results.stream().max(Comparator.comparingDouble(ComparisonResult::enterpriseScore))
                .ifPresent(r -> winners.put("Most Enterprise-Ready", r.framework()));

        // Fewest lines of code
        results.stream().filter(r -> r.linesOfCode() > 0)
                .min(Comparator.comparingInt(ComparisonResult::linesOfCode))
                .ifPresent(r -> winners.put("Least Code", r.framework()));

        return winners;
    }

    private void saveReport(ComparisonReport report) {
        try {
            Files.createDirectories(outputDir);
            Path jsonPath = outputDir.resolve(application + "-comparison.json");
            mapper.writeValue(jsonPath.toFile(), report);
            logger.info("Comparison report saved: {}", jsonPath);
        } catch (IOException e) {
            logger.error("Failed to save comparison report: {}", e.getMessage());
        }
    }

    public record ComparisonReport(
            String application,
            List<ComparisonResult> results,
            List<ImprovementIssue> improvementIssues,
            Map<String, String> metricWinners
    ) {}

    public record ImprovementIssue(
            String title,
            Priority priority,
            String category,
            String metric,
            double swarmaiValue,
            double competitorValue,
            String competitorName
    ) {}

    public enum Priority {
        CRITICAL, HIGH, MEDIUM, LOW
    }
}
