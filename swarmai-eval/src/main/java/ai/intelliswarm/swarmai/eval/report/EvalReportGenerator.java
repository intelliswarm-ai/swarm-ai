package ai.intelliswarm.swarmai.eval.report;

import ai.intelliswarm.swarmai.eval.scoring.RegressionDetector;
import ai.intelliswarm.swarmai.eval.scoring.ScenarioResult;
import ai.intelliswarm.swarmai.eval.scoring.ValueScore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Generates JSON and HTML reports from eval results.
 */
public class EvalReportGenerator {

    private static final Logger logger = LoggerFactory.getLogger(EvalReportGenerator.class);
    private static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path outputDir;

    public EvalReportGenerator(Path outputDir) {
        this.outputDir = outputDir;
    }

    /**
     * Generate JSON and HTML reports.
     */
    public void generate(ValueScore score, List<RegressionDetector.Regression> regressions) {
        try {
            Files.createDirectories(outputDir);

            // JSON report
            Path jsonPath = outputDir.resolve("latest-report.json");
            mapper.writeValue(jsonPath.toFile(), Map.of(
                    "version", score.version(),
                    "computedAt", score.computedAt().toString(),
                    "overallScore", score.overallScore(),
                    "passRate", score.passRate(),
                    "totalScenarios", score.totalScenarios(),
                    "failedScenarios", score.failedScenarios(),
                    "meetsReleaseThreshold", score.meetsReleaseThreshold(),
                    "breakdown", score.breakdown(),
                    "regressions", regressions.size(),
                    "scenarios", score.scenarioResults().stream().map(r -> Map.of(
                            "id", r.scenarioId(),
                            "name", r.scenarioName(),
                            "category", r.category(),
                            "passed", r.passed(),
                            "score", r.score(),
                            "message", r.message(),
                            "durationMs", r.duration().toMillis()
                    )).toList()
            ));
            logger.info("JSON report: {}", jsonPath);

            // HTML report
            Path htmlPath = outputDir.resolve("latest-report.html");
            Files.writeString(htmlPath, generateHtml(score, regressions));
            logger.info("HTML report: {}", htmlPath);

        } catch (IOException e) {
            logger.error("Failed to generate report: {}", e.getMessage());
        }
    }

    private String generateHtml(ValueScore score, List<RegressionDetector.Regression> regressions) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset='utf-8'>");
        html.append("<title>SwarmAI Eval Report</title>");
        html.append("<style>body{font-family:system-ui;max-width:900px;margin:0 auto;padding:20px}");
        html.append("table{border-collapse:collapse;width:100%}th,td{border:1px solid #ddd;padding:8px;text-align:left}");
        html.append("th{background:#f4f4f4}.pass{color:green}.fail{color:red}");
        html.append(".score-big{font-size:3em;font-weight:bold;text-align:center;padding:20px}");
        html.append(".gate-pass{color:green}.gate-fail{color:red}</style></head><body>");

        // Header
        html.append("<h1>SwarmAI Self-Evaluation Report</h1>");
        html.append("<p>Version: <strong>").append(score.version()).append("</strong> | ");
        html.append("Date: ").append(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneId.systemDefault()).format(score.computedAt())).append("</p>");

        // Value Score
        String gateClass = score.meetsReleaseThreshold() ? "gate-pass" : "gate-fail";
        html.append("<div class='score-big ").append(gateClass).append("'>")
                .append(score.overallScore()).append("/100</div>");
        html.append("<p style='text-align:center' class='").append(gateClass).append("'>Release Gate: ")
                .append(score.meetsReleaseThreshold() ? "PASSED" : "BLOCKED").append("</p>");

        // Category breakdown
        html.append("<h2>Category Breakdown</h2><table><tr><th>Category</th><th>Score</th></tr>");
        for (Map.Entry<String, Double> entry : score.breakdown().entrySet()) {
            html.append("<tr><td>").append(entry.getKey()).append("</td><td>")
                    .append(Math.round(entry.getValue() * 10.0) / 10.0).append("</td></tr>");
        }
        html.append("</table>");

        // Regressions
        if (!regressions.isEmpty()) {
            html.append("<h2 class='fail'>Regressions (").append(regressions.size()).append(")</h2><ul>");
            for (RegressionDetector.Regression r : regressions) {
                html.append("<li><strong>[").append(r.severity()).append("]</strong> ").append(r.message()).append("</li>");
            }
            html.append("</ul>");
        }

        // Scenario details
        html.append("<h2>Scenario Results (").append(score.totalScenarios()).append(")</h2>");
        html.append("<table><tr><th>Category</th><th>Scenario</th><th>Score</th><th>Status</th><th>Message</th></tr>");
        for (ScenarioResult r : score.scenarioResults()) {
            String cls = r.passed() ? "pass" : "fail";
            html.append("<tr><td>").append(r.category()).append("</td>");
            html.append("<td>").append(r.scenarioName()).append("</td>");
            html.append("<td>").append(r.score()).append("</td>");
            html.append("<td class='").append(cls).append("'>").append(r.passed() ? "PASS" : "FAIL").append("</td>");
            html.append("<td>").append(r.message()).append("</td></tr>");
        }
        html.append("</table>");

        html.append("</body></html>");
        return html.toString();
    }
}
