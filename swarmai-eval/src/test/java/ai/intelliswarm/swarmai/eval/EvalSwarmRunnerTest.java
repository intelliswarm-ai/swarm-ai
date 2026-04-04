package ai.intelliswarm.swarmai.eval;

import ai.intelliswarm.swarmai.eval.scoring.ValueScore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("EvalSwarmRunner")
class EvalSwarmRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("runs all scenarios and produces a value score")
    void runProducesValueScore() {
        EvalSwarmRunner runner = new EvalSwarmRunner("1.0.0-SNAPSHOT", tempDir);
        ValueScore score = runner.run();

        assertNotNull(score);
        assertTrue(score.totalScenarios() > 0, "Should have at least 1 scenario");
        assertTrue(score.overallScore() >= 0 && score.overallScore() <= 100,
                "Score should be 0-100, was: " + score.overallScore());
        assertNotNull(score.breakdown());
        assertFalse(score.breakdown().isEmpty(), "Should have category breakdown");
    }

    @Test
    @DisplayName("all current scenarios pass (value score meets release threshold)")
    void allScenariosPass() {
        EvalSwarmRunner runner = new EvalSwarmRunner("1.0.0-SNAPSHOT", tempDir);
        ValueScore score = runner.run();

        assertEquals(0, score.failedScenarios(),
                "Expected 0 failures but got " + score.failedScenarios() + ": " +
                        score.failures().stream()
                                .map(f -> f.scenarioName() + ": " + f.message())
                                .toList());
        assertTrue(score.meetsReleaseThreshold(),
                "Value score " + score.overallScore() + " should meet release threshold (70)");
    }

    @Test
    @DisplayName("generates report files")
    void generatesReports() {
        EvalSwarmRunner runner = new EvalSwarmRunner("1.0.0-SNAPSHOT", tempDir);
        runner.run();

        assertTrue(tempDir.resolve("history.json").toFile().exists(), "Should create history.json");
        assertTrue(tempDir.resolve("latest-report.json").toFile().exists(), "Should create JSON report");
        assertTrue(tempDir.resolve("latest-report.html").toFile().exists(), "Should create HTML report");
    }

    @Test
    @DisplayName("score history tracks across runs")
    void scoreHistoryTracks() {
        EvalSwarmRunner runner = new EvalSwarmRunner("1.0.0-SNAPSHOT", tempDir);
        runner.run();
        runner.run(); // Second run should detect no regressions

        assertTrue(tempDir.resolve("history.json").toFile().exists());
        // History should have 2 entries
        assertTrue(tempDir.resolve("history.json").toFile().length() > 100,
                "History file should have content from 2 runs");
    }
}
