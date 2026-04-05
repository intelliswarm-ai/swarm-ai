package ai.intelliswarm.swarmai.selfimproving;

import ai.intelliswarm.swarmai.selfimproving.aggregator.ImprovementAggregator;
import ai.intelliswarm.swarmai.selfimproving.classifier.ImprovementClassifier;
import ai.intelliswarm.swarmai.selfimproving.collector.ImprovementCollector;
import ai.intelliswarm.swarmai.selfimproving.config.SelfImprovementConfig;
import ai.intelliswarm.swarmai.selfimproving.extractor.PatternExtractor;
import ai.intelliswarm.swarmai.selfimproving.model.*;
import ai.intelliswarm.swarmai.selfimproving.model.ExecutionTrace.*;
import ai.intelliswarm.swarmai.selfimproving.phase.ImprovementPhase;
import ai.intelliswarm.swarmai.selfimproving.phase.ImprovementPhase.ImprovementResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ImprovementPhaseTest {

    private SelfImprovementConfig config;
    private ImprovementCollector collector;
    private PatternExtractor extractor;
    private ImprovementClassifier classifier;
    private ImprovementAggregator aggregator;
    private ImprovementPhase phase;

    @BeforeEach
    void setUp() {
        config = new SelfImprovementConfig();
        config.setEnabled(true);
        config.setReservePercent(0.10);
        config.setMinObservations(1); // lower for testing
        config.setMinCrossWorkflowEvidence(1);

        collector = new ImprovementCollector();
        extractor = new PatternExtractor(config);
        classifier = new ImprovementClassifier(config);
        aggregator = new ImprovementAggregator(config);
        phase = new ImprovementPhase(config, collector, extractor, classifier, aggregator);
    }

    @Test
    void shouldCollectObservationsFromFailedTasks() {
        ExecutionTrace trace = buildTraceWithFailures();
        ImprovementResult result = phase.execute(trace, null, "test-swarm");

        assertTrue(result.totalObservations() > 0, "Should collect observations from failures");
    }

    @Test
    void shouldCollectObservationsFromExpensiveTasks() {
        ExecutionTrace trace = buildTraceWithExpensiveTask();
        ImprovementResult result = phase.execute(trace, null, "test-swarm");

        assertTrue(result.totalObservations() > 0, "Should detect expensive tasks");
    }

    @Test
    void shouldDetectConvergencePatterns() {
        ExecutionTrace trace = buildTraceWithEarlyConvergence();
        ImprovementResult result = phase.execute(trace, null, "test-swarm");

        assertTrue(result.totalObservations() > 0, "Should detect convergence patterns");
    }

    @Test
    void shouldDetectAntiPatterns() {
        ExecutionTrace trace = buildTraceWithSpinningAgent();
        ImprovementResult result = phase.execute(trace, null, "test-swarm");

        assertTrue(result.totalObservations() > 0, "Should detect agent spinning anti-pattern");
    }

    @Test
    void shouldProduceResultEvenWithNoIssues() {
        ExecutionTrace trace = buildCleanTrace();
        ImprovementResult result = phase.execute(trace, null, "test-swarm");

        assertNotNull(result);
        assertEquals("test-swarm", result.swarmId());
        assertEquals(0, result.totalObservations());
    }

    @Test
    void shouldSkipTransientFailures() {
        ExecutionTrace trace = buildTraceWithTransientFailure();
        ImprovementResult result = phase.execute(trace, null, "test-swarm");

        // Transient failures (timeout, rate limit) should NOT produce observations
        assertEquals(0, result.totalObservations());
    }

    @Test
    void shouldReserve10PercentBudget() {
        assertEquals(0.10, config.getReservePercent());
    }

    @Test
    void shouldHaveCorrectPriorityAllocation() {
        assertEquals(0.30, config.getPriority1FixFailuresPercent());
        assertEquals(0.25, config.getPriority2OptimizePercent());
        assertEquals(0.20, config.getPriority3PromotePercent());
        assertEquals(0.15, config.getPriority4DetectGapsPercent());
        assertEquals(0.10, config.getPriority5ExplorePercent());

        double total = config.getPriority1FixFailuresPercent()
                + config.getPriority2OptimizePercent()
                + config.getPriority3PromotePercent()
                + config.getPriority4DetectGapsPercent()
                + config.getPriority5ExplorePercent();
        assertEquals(1.0, total, 0.001, "Priority allocations must sum to 100%");
    }

    // --- Test helpers ---

    private WorkflowShape defaultShape() {
        return new WorkflowShape(3, 2, false, false, false,
                Set.of("WEB", "DATA"), "SEQUENTIAL", 2, 2.0, true, false);
    }

    private ExecutionTrace buildTraceWithFailures() {
        return ExecutionTrace.builder()
                .swarmId("test-swarm")
                .workflowShape(defaultShape())
                .totalPromptTokens(50000)
                .totalCompletionTokens(10000)
                .modelName("gpt-4o")
                .addTaskTrace(new TaskTrace("task-1", "Researcher", false,
                        30000, 5000, 3, List.of("web_search"), "Missing skill for data analysis", Duration.ofSeconds(30)))
                .addTaskTrace(new TaskTrace("task-2", "Writer", true,
                        20000, 5000, 2, List.of("file_write"), null, Duration.ofSeconds(15)))
                .iterationCount(3)
                .convergedAtIteration(3)
                .totalDuration(Duration.ofMinutes(2))
                .build();
    }

    private ExecutionTrace buildTraceWithExpensiveTask() {
        return ExecutionTrace.builder()
                .swarmId("test-swarm")
                .workflowShape(defaultShape())
                .totalPromptTokens(100000)
                .totalCompletionTokens(20000)
                .modelName("gpt-4o")
                .addTaskTrace(new TaskTrace("task-1", "Researcher", true,
                        90000, 15000, 8, List.of("web_search", "web_scrape"), null, Duration.ofMinutes(3)))
                .addTaskTrace(new TaskTrace("task-2", "Writer", true,
                        10000, 5000, 2, List.of("file_write"), null, Duration.ofSeconds(15)))
                .iterationCount(1)
                .convergedAtIteration(1)
                .totalDuration(Duration.ofMinutes(4))
                .build();
    }

    private ExecutionTrace buildTraceWithEarlyConvergence() {
        return ExecutionTrace.builder()
                .swarmId("test-swarm")
                .workflowShape(new WorkflowShape(3, 2, true, false, false,
                        Set.of("WEB"), "SELF_IMPROVING", 2, 1.0, true, false))
                .totalPromptTokens(60000)
                .totalCompletionTokens(15000)
                .modelName("gpt-4o")
                .addTaskTrace(new TaskTrace("task-1", "Researcher", true,
                        40000, 10000, 3, List.of("web_search"), null, Duration.ofSeconds(45)))
                .iterationCount(5)
                .convergedAtIteration(2)
                .totalDuration(Duration.ofMinutes(3))
                .build();
    }

    private ExecutionTrace buildTraceWithSpinningAgent() {
        return ExecutionTrace.builder()
                .swarmId("test-swarm")
                .workflowShape(defaultShape())
                .totalPromptTokens(80000)
                .totalCompletionTokens(20000)
                .modelName("gpt-4o")
                .addTaskTrace(new TaskTrace("task-1", "Analyst", true,
                        70000, 18000, 8, List.of(), null, Duration.ofMinutes(2))) // 8 turns, NO tools
                .iterationCount(1)
                .convergedAtIteration(1)
                .totalDuration(Duration.ofMinutes(3))
                .build();
    }

    private ExecutionTrace buildCleanTrace() {
        return ExecutionTrace.builder()
                .swarmId("test-swarm")
                .workflowShape(defaultShape())
                .totalPromptTokens(20000)
                .totalCompletionTokens(5000)
                .modelName("gpt-4o")
                .addTaskTrace(new TaskTrace("task-1", "Worker", true,
                        20000, 5000, 2, List.of("calculator"), null, Duration.ofSeconds(10)))
                .iterationCount(0)
                .convergedAtIteration(0)
                .totalDuration(Duration.ofSeconds(15))
                .build();
    }

    private ExecutionTrace buildTraceWithTransientFailure() {
        return ExecutionTrace.builder()
                .swarmId("test-swarm")
                .workflowShape(defaultShape())
                .totalPromptTokens(10000)
                .totalCompletionTokens(2000)
                .modelName("gpt-4o")
                .addTaskTrace(new TaskTrace("task-1", "Worker", false,
                        10000, 2000, 1, List.of("http_request"), "Connection timeout after 30s", Duration.ofSeconds(30)))
                .iterationCount(1)
                .convergedAtIteration(1)
                .totalDuration(Duration.ofSeconds(35))
                .build();
    }
}
