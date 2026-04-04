package ai.intelliswarm.swarmai.eval;

import ai.intelliswarm.swarmai.eval.scenario.*;
import ai.intelliswarm.swarmai.eval.scoring.*;
import ai.intelliswarm.swarmai.eval.report.EvalReportGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Main entry point for the SwarmAI self-evaluation system.
 *
 * Executes all eval scenarios, computes the Framework Value Score,
 * detects regressions, and generates reports.
 *
 * <p>Usage:
 * <pre>
 * EvalSwarmRunner runner = new EvalSwarmRunner("1.0.0-SNAPSHOT");
 * ValueScore score = runner.run();
 * System.out.println("Value Score: " + score.overallScore());
 * </pre>
 */
public class EvalSwarmRunner {

    private static final Logger logger = LoggerFactory.getLogger(EvalSwarmRunner.class);

    private final String version;
    private final Path resultsDir;
    private final ValueScorer scorer;
    private final ScoreHistory history;
    private final RegressionDetector regressionDetector;
    private final EvalReportGenerator reportGenerator;

    public EvalSwarmRunner(String version) {
        this(version, Path.of("eval-results"));
    }

    public EvalSwarmRunner(String version, Path resultsDir) {
        this.version = version;
        this.resultsDir = resultsDir;
        this.scorer = new ValueScorer();
        this.history = new ScoreHistory(resultsDir.resolve("history.json"));
        this.regressionDetector = new RegressionDetector();
        this.reportGenerator = new EvalReportGenerator(resultsDir);
    }

    /**
     * Run all eval scenarios and return the computed value score.
     */
    public ValueScore run() {
        logger.info("=".repeat(70));
        logger.info("SWARMAI SELF-EVALUATION");
        logger.info("Version: {}", version);
        logger.info("=".repeat(70));

        // Collect all scenarios
        List<EvalScenario> scenarios = collectScenarios();
        logger.info("Loaded {} scenarios across {} categories",
                scenarios.size(), scenarios.stream().map(EvalScenario::category).distinct().count());

        // Execute all scenarios
        List<ScenarioResult> results = new ArrayList<>();
        for (EvalScenario scenario : scenarios) {
            results.add(scenario.execute());
        }

        // Compute value score
        ValueScore score = scorer.compute(results, version);

        // Detect regressions
        List<RegressionDetector.Regression> regressions = regressionDetector.detect(score, history);

        // Save to history
        history.append(score);

        // Generate report
        reportGenerator.generate(score, regressions);

        // Summary
        logger.info("");
        logger.info("=".repeat(70));
        logger.info("SELF-EVALUATION COMPLETE");
        logger.info("=".repeat(70));
        logger.info("Framework Value Score: {}/100", score.overallScore());
        logger.info("Pass Rate:            {}%", score.passRate());
        logger.info("Scenarios:            {}/{} passed", score.totalScenarios() - score.failedScenarios(), score.totalScenarios());
        logger.info("Regressions:          {}", regressions.size());
        logger.info("Release Gate:         {}", score.meetsReleaseThreshold() ? "PASSED" : "BLOCKED");
        logger.info("=".repeat(70));

        if (!score.failures().isEmpty()) {
            logger.warn("Failed scenarios:");
            for (ScenarioResult fail : score.failures()) {
                logger.warn("  [{}] {} -- {}", fail.category(), fail.scenarioName(), fail.message());
            }
        }

        return score;
    }

    /**
     * Collect all registered scenarios from all categories.
     */
    private List<EvalScenario> collectScenarios() {
        List<EvalScenario> all = new ArrayList<>();
        all.addAll(CoreScenarios.all());
        all.addAll(EnterpriseScenarios.all());
        all.addAll(ResilienceScenarios.all());
        all.addAll(DslScenarios.all());
        return all;
    }

    /**
     * CLI entry point.
     */
    public static void main(String[] args) {
        String version = args.length > 0 ? args[0] : "1.0.0-SNAPSHOT";
        EvalSwarmRunner runner = new EvalSwarmRunner(version);
        ValueScore score = runner.run();
        System.exit(score.meetsReleaseThreshold() ? 0 : 1);
    }
}
