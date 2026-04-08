package ai.intelliswarm.swarmai.rl.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates summary reports from benchmark results.
 * Produces JSON summaries and algorithm ranking tables.
 */
public class BenchmarkReportGenerator {

    /**
     * Generates a JSON summary report from multiple BenchmarkMetrics runs.
     */
    public static String generateSummaryJson(Map<String, List<BenchmarkMetrics>> resultsByAlgorithm) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"generated_at\": \"").append(Instant.now()).append("\",\n");
        json.append("  \"algorithms\": [\n");

        List<String> algoNames = new ArrayList<>(resultsByAlgorithm.keySet());
        for (int i = 0; i < algoNames.size(); i++) {
            String algo = algoNames.get(i);
            List<BenchmarkMetrics> runs = resultsByAlgorithm.get(algo);

            double[] regrets = runs.stream().mapToDouble(BenchmarkMetrics::getCumulativeRegret).toArray();
            double[] simpleRegrets = runs.stream().mapToDouble(BenchmarkMetrics::getSimpleRegret).toArray();
            double[] optRates = runs.stream().mapToDouble(BenchmarkMetrics::getOptimalActionRate).toArray();
            double[] decLatencies = runs.stream().mapToDouble(BenchmarkMetrics::getAvgDecisionLatencyMicros).toArray();
            int[] convSteps = runs.stream().mapToInt(m -> m.getConvergenceStep(100)).toArray();

            json.append("    {\n");
            json.append("      \"name\": \"").append(algo).append("\",\n");
            json.append("      \"runs\": ").append(runs.size()).append(",\n");
            json.append("      \"cumulative_regret\": ").append(formatStats(regrets)).append(",\n");
            json.append("      \"simple_regret\": ").append(formatStats(simpleRegrets)).append(",\n");
            json.append("      \"optimal_action_rate\": ").append(formatStats(optRates)).append(",\n");
            json.append("      \"convergence_step\": ").append(formatIntStats(convSteps)).append(",\n");
            json.append("      \"decision_latency_us\": ").append(formatStats(decLatencies)).append("\n");
            json.append("    }");
            if (i < algoNames.size() - 1) json.append(",");
            json.append("\n");
        }

        json.append("  ],\n");

        // Rankings
        json.append("  \"rankings\": {\n");
        json.append("    \"by_cumulative_regret\": ").append(
                rankAlgorithms(resultsByAlgorithm, BenchmarkMetrics::getCumulativeRegret, true)).append(",\n");
        json.append("    \"by_optimal_action_rate\": ").append(
                rankAlgorithms(resultsByAlgorithm, BenchmarkMetrics::getOptimalActionRate, false)).append(",\n");
        json.append("    \"by_decision_latency\": ").append(
                rankAlgorithms(resultsByAlgorithm, BenchmarkMetrics::getAvgDecisionLatencyMicros, true)).append("\n");
        json.append("  }\n");

        json.append("}");
        return json.toString();
    }

    /**
     * Generates a Markdown comparison table.
     */
    public static String generateMarkdownTable(Map<String, List<BenchmarkMetrics>> resultsByAlgorithm) {
        StringBuilder md = new StringBuilder();
        md.append("| Algorithm | Cumulative Regret | Simple Regret | Optimal Rate | Convergence Step | Decision Latency (us) |\n");
        md.append("|-----------|-------------------|---------------|--------------|------------------|-----------------------|\n");

        // Sort by cumulative regret (ascending)
        List<Map.Entry<String, List<BenchmarkMetrics>>> sorted = resultsByAlgorithm.entrySet().stream()
                .sorted(Comparator.comparingDouble(e ->
                        e.getValue().stream().mapToDouble(BenchmarkMetrics::getCumulativeRegret)
                                .average().orElse(Double.MAX_VALUE)))
                .collect(Collectors.toList());

        for (Map.Entry<String, List<BenchmarkMetrics>> entry : sorted) {
            List<BenchmarkMetrics> runs = entry.getValue();
            double[] regrets = runs.stream().mapToDouble(BenchmarkMetrics::getCumulativeRegret).toArray();
            double[] simpleRegrets = runs.stream().mapToDouble(BenchmarkMetrics::getSimpleRegret).toArray();
            double[] optRates = runs.stream().mapToDouble(BenchmarkMetrics::getOptimalActionRate).toArray();
            double[] latencies = runs.stream().mapToDouble(BenchmarkMetrics::getAvgDecisionLatencyMicros).toArray();
            int[] convSteps = runs.stream().mapToInt(m -> m.getConvergenceStep(100)).toArray();

            md.append(String.format("| %s | %.1f +/- %.1f | %.4f | %.3f | %.0f | %.1f |\n",
                    entry.getKey(),
                    mean(regrets), BenchmarkMetrics.bootstrapCI(regrets, 1000),
                    mean(simpleRegrets),
                    mean(optRates),
                    Arrays.stream(convSteps).filter(c -> c > 0).average().orElse(-1),
                    mean(latencies)));
        }

        return md.toString();
    }

    /**
     * Writes a full benchmark report to disk.
     */
    public static void writeReport(Path outputDir,
                                    Map<String, List<BenchmarkMetrics>> results) throws IOException {
        Files.createDirectories(outputDir);

        // JSON summary
        String json = generateSummaryJson(results);
        Files.writeString(outputDir.resolve("summary_report.json"), json);

        // Markdown table
        String markdown = generateMarkdownTable(results);
        Files.writeString(outputDir.resolve("comparison_table.md"), markdown);

        // Detailed CSV (all steps from all runs)
        List<String> csvRows = new ArrayList<>();
        csvRows.add(BenchmarkMetrics.csvHeader());
        for (List<BenchmarkMetrics> runs : results.values()) {
            for (BenchmarkMetrics m : runs) {
                csvRows.addAll(m.toCsvRows());
            }
        }
        Files.write(outputDir.resolve("detailed_results.csv"), csvRows);

        // Summary CSV
        List<String> summaryRows = new ArrayList<>();
        summaryRows.add(BenchmarkMetrics.summaryHeader());
        for (List<BenchmarkMetrics> runs : results.values()) {
            for (BenchmarkMetrics m : runs) {
                summaryRows.add(m.toSummaryRow());
            }
        }
        Files.write(outputDir.resolve("algorithm_comparison_summary.csv"), summaryRows);
    }

    // ======================================================================
    // Internal helpers
    // ======================================================================

    private static String formatStats(double[] values) {
        double mean = mean(values);
        double ci = BenchmarkMetrics.bootstrapCI(values, 1000);
        double min = Arrays.stream(values).min().orElse(0);
        double max = Arrays.stream(values).max().orElse(0);
        return String.format("{\"mean\": %.4f, \"ci95\": %.4f, \"min\": %.4f, \"max\": %.4f}",
                mean, ci, min, max);
    }

    private static String formatIntStats(int[] values) {
        int[] valid = Arrays.stream(values).filter(v -> v >= 0).toArray();
        if (valid.length == 0) return "{\"mean\": -1, \"converged_pct\": 0.0}";
        double mean = Arrays.stream(valid).average().orElse(-1);
        double pct = (double) valid.length / values.length;
        return String.format("{\"mean\": %.1f, \"converged_pct\": %.2f}", mean, pct);
    }

    private static String rankAlgorithms(Map<String, List<BenchmarkMetrics>> results,
                                          java.util.function.ToDoubleFunction<BenchmarkMetrics> metric,
                                          boolean ascending) {
        List<Map.Entry<String, Double>> ranked = results.entrySet().stream()
                .map(e -> Map.entry(e.getKey(),
                        e.getValue().stream().mapToDouble(metric).average().orElse(Double.MAX_VALUE)))
                .sorted(ascending
                        ? Comparator.comparingDouble(Map.Entry::getValue)
                        : Comparator.comparingDouble(Map.Entry<String, Double>::getValue).reversed())
                .collect(Collectors.toList());

        return "[" + ranked.stream()
                .map(e -> String.format("\"%s\"", e.getKey()))
                .collect(Collectors.joining(", ")) + "]";
    }

    private static double mean(double[] values) {
        return Arrays.stream(values).average().orElse(0);
    }
}
