package ai.intelliswarm.swarmai.selfimproving;

import ai.intelliswarm.swarmai.selfimproving.collector.ImprovementCollector;
import ai.intelliswarm.swarmai.selfimproving.model.*;
import ai.intelliswarm.swarmai.selfimproving.model.ExecutionTrace.*;
import ai.intelliswarm.swarmai.selfimproving.model.SpecificObservation.ObservationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ImprovementCollector — the data source for the entire self-improvement pipeline.
 *
 * If the collector misclassifies an observation, everything downstream is wrong:
 * wrong rules extracted, wrong tiers assigned, wrong improvements shipped.
 *
 * Each test here verifies a specific classification boundary with realistic
 * execution trace data. Tests that pass trivially are not worth writing.
 */
@DisplayName("ImprovementCollector — Observation Extraction from ExecutionTrace")
class ImprovementCollectorTest {

    private ImprovementCollector collector;

    @BeforeEach
    void setUp() {
        collector = new ImprovementCollector();
    }

    // ================================================================
    // FAILURE OBSERVATION — Must distinguish structural failures from
    // transient network errors. Transient failures are noise that
    // should NOT feed into the improvement pipeline.
    // ================================================================

    @Nested
    @DisplayName("Failure Collection")
    class FailureCollection {

        @Test
        @DisplayName("collects structural failures (non-transient)")
        void collectsStructuralFailures() {
            ExecutionTrace trace = traceWithFailedTask(
                "task-1", "Analyst", "NullPointerException in data parser");

            List<SpecificObservation> observations = collector.collect(trace);

            long failures = observations.stream()
                .filter(o -> o.type() == ObservationType.FAILURE).count();
            assertEquals(1, failures, "Structural failure should produce an observation");
        }

        @Test
        @DisplayName("filters out timeout (transient)")
        void filtersTimeout() {
            ExecutionTrace trace = traceWithFailedTask(
                "task-1", "Analyst", "Request timeout after 30 seconds");

            List<SpecificObservation> observations = collector.collect(trace);

            long failures = observations.stream()
                .filter(o -> o.type() == ObservationType.FAILURE).count();
            assertEquals(0, failures,
                "Timeout is transient — should NOT produce a failure observation");
        }

        @Test
        @DisplayName("filters out rate limit (transient)")
        void filtersRateLimit() {
            ExecutionTrace trace = traceWithFailedTask(
                "task-1", "Analyst", "Rate limit exceeded, 429 Too Many Requests");

            List<SpecificObservation> observations = collector.collect(trace);

            long failures = observations.stream()
                .filter(o -> o.type() == ObservationType.FAILURE).count();
            assertEquals(0, failures,
                "Rate limit (429) is transient — should NOT produce a failure observation");
        }

        @Test
        @DisplayName("filters out 503 service unavailable (transient)")
        void filters503() {
            ExecutionTrace trace = traceWithFailedTask(
                "task-1", "Analyst", "HTTP 503 Service Unavailable");

            List<SpecificObservation> observations = collector.collect(trace);

            long failures = observations.stream()
                .filter(o -> o.type() == ObservationType.FAILURE).count();
            assertEquals(0, failures,
                "503 is transient — should NOT produce a failure observation");
        }

        @Test
        @DisplayName("filters out connection refused (transient)")
        void filtersConnectionRefused() {
            ExecutionTrace trace = traceWithFailedTask(
                "task-1", "Analyst", "Connection refused to api.example.com");

            List<SpecificObservation> observations = collector.collect(trace);

            long failures = observations.stream()
                .filter(o -> o.type() == ObservationType.FAILURE).count();
            assertEquals(0, failures,
                "Connection refused is transient — should NOT produce a failure observation");
        }

        @Test
        @DisplayName("collects failure with null reason (not transient)")
        void collectsNullReason() {
            ExecutionTrace trace = traceWithFailedTask("task-1", "Analyst", null);

            List<SpecificObservation> observations = collector.collect(trace);

            long failures = observations.stream()
                .filter(o -> o.type() == ObservationType.FAILURE).count();
            assertEquals(1, failures,
                "Null failure reason is not transient — should produce observation");
        }

        @Test
        @DisplayName("failure observation includes task context in evidence")
        void failureIncludesContext() {
            ExecutionTrace trace = traceWithFailedTask(
                "task-analysis", "Senior Analyst", "Data format mismatch");

            List<SpecificObservation> observations = collector.collect(trace);
            SpecificObservation failure = observations.stream()
                .filter(o -> o.type() == ObservationType.FAILURE)
                .findFirst().orElseThrow();

            assertEquals("task-analysis", failure.evidence().get("task_id"));
            assertEquals("Senior Analyst", failure.evidence().get("agent_role"));
        }
    }

    // ================================================================
    // EXPENSIVE TASK DETECTION — Tasks consuming >40% of total tokens
    // are worth investigating. But single-task workflows should be
    // excluded (a single task always uses 100%).
    // ================================================================

    @Nested
    @DisplayName("Expensive Task Detection")
    class ExpensiveTaskDetection {

        @Test
        @DisplayName("detects task consuming >40% of tokens")
        void detectsExpensiveTask() {
            ExecutionTrace trace = traceBuilder()
                .addTaskTrace(taskTrace("task-1", true, 8000, 4000, 3, List.of("web_search")))
                .addTaskTrace(taskTrace("task-2", true, 1000, 500, 1, List.of("file_read")))
                .totalPromptTokens(9000).totalCompletionTokens(4500)
                .build();

            List<SpecificObservation> observations = collector.collect(trace);

            long expensive = observations.stream()
                .filter(o -> o.type() == ObservationType.EXPENSIVE_TASK).count();
            assertTrue(expensive >= 1,
                "Task consuming 89% of tokens should be flagged as expensive");
        }

        @Test
        @DisplayName("does not flag tasks below 40% threshold")
        void doesNotFlagCheapTask() {
            ExecutionTrace trace = traceBuilder()
                .addTaskTrace(taskTrace("task-1", true, 3000, 1500, 2, List.of()))
                .addTaskTrace(taskTrace("task-2", true, 4000, 2000, 2, List.of()))
                .addTaskTrace(taskTrace("task-3", true, 3000, 1500, 2, List.of()))
                .totalPromptTokens(10000).totalCompletionTokens(5000)
                .build();

            List<SpecificObservation> observations = collector.collect(trace);

            long expensive = observations.stream()
                .filter(o -> o.type() == ObservationType.EXPENSIVE_TASK).count();
            assertEquals(0, expensive,
                "No task exceeds 40% of tokens — none should be flagged");
        }

        @Test
        @DisplayName("skips single-task workflows (always 100%)")
        void skipsSingleTask() {
            ExecutionTrace trace = traceBuilder()
                .addTaskTrace(taskTrace("task-1", true, 5000, 2500, 3, List.of()))
                .totalPromptTokens(5000).totalCompletionTokens(2500)
                .build();

            List<SpecificObservation> observations = collector.collect(trace);

            long expensive = observations.stream()
                .filter(o -> o.type() == ObservationType.EXPENSIVE_TASK).count();
            assertEquals(0, expensive,
                "Single-task workflow should be skipped — 100% is not meaningful");
        }

        @Test
        @DisplayName("does not flag failed tasks as expensive")
        void doesNotFlagFailedTasks() {
            ExecutionTrace trace = traceBuilder()
                .addTaskTrace(taskTrace("task-1", false, 8000, 4000, 5, List.of()))
                .addTaskTrace(taskTrace("task-2", true, 1000, 500, 1, List.of()))
                .totalPromptTokens(9000).totalCompletionTokens(4500)
                .build();

            List<SpecificObservation> observations = collector.collect(trace);

            long expensive = observations.stream()
                .filter(o -> o.type() == ObservationType.EXPENSIVE_TASK).count();
            assertEquals(0, expensive,
                "Failed tasks should not be flagged as expensive — they're failures, not waste");
        }

        @Test
        @DisplayName("expensive task evidence includes cost ratio")
        void evidenceIncludesCostRatio() {
            ExecutionTrace trace = traceBuilder()
                .addTaskTrace(taskTrace("task-1", true, 9000, 4500, 5, List.of("web_search")))
                .addTaskTrace(taskTrace("task-2", true, 500, 250, 1, List.of()))
                .totalPromptTokens(9500).totalCompletionTokens(4750)
                .build();

            List<SpecificObservation> observations = collector.collect(trace);
            SpecificObservation expensive = observations.stream()
                .filter(o -> o.type() == ObservationType.EXPENSIVE_TASK)
                .findFirst().orElseThrow();

            double ratio = (double) expensive.evidence().get("token_ratio");
            assertTrue(ratio > 0.90,
                "Cost ratio should be >90%. Got: " + ratio);
        }
    }

    // ================================================================
    // CONVERGENCE PATTERN DETECTION — Early convergence is a useful
    // default to learn. Exhausted iterations is an anti-pattern.
    // ================================================================

    @Nested
    @DisplayName("Convergence Pattern Detection")
    class ConvergencePatterns {

        @Test
        @DisplayName("detects early convergence (converged before max iterations)")
        void detectsEarlyConvergence() {
            ExecutionTrace trace = traceBuilder()
                .iterationCount(5).convergedAtIteration(2)
                .build();

            List<SpecificObservation> observations = collector.collect(trace);

            long convergence = observations.stream()
                .filter(o -> o.type() == ObservationType.CONVERGENCE_PATTERN).count();
            assertEquals(1, convergence,
                "Converging at iteration 2 of 5 is an early convergence pattern");
        }

        @Test
        @DisplayName("detects exhausted iterations as anti-pattern")
        void detectsExhaustedIterations() {
            ExecutionTrace trace = traceBuilder()
                .iterationCount(5).convergedAtIteration(5)
                .build();

            List<SpecificObservation> observations = collector.collect(trace);

            long antiPatterns = observations.stream()
                .filter(o -> o.type() == ObservationType.ANTI_PATTERN)
                .filter(o -> o.description().contains("exhausted"))
                .count();
            assertEquals(1, antiPatterns,
                "Exhausting all 5 iterations without converging is an anti-pattern");
        }

        @Test
        @DisplayName("does not flag single iteration (not meaningful)")
        void doesNotFlagSingleIteration() {
            ExecutionTrace trace = traceBuilder()
                .iterationCount(1).convergedAtIteration(1)
                .build();

            List<SpecificObservation> observations = collector.collect(trace);

            long exhausted = observations.stream()
                .filter(o -> o.type() == ObservationType.ANTI_PATTERN)
                .filter(o -> o.description().contains("exhausted"))
                .count();
            assertEquals(0, exhausted,
                "Single iteration should not be flagged as exhausted");
        }

        @Test
        @DisplayName("no convergence observations when iterationCount is 0")
        void noObservationsForNonIterative() {
            ExecutionTrace trace = traceBuilder()
                .iterationCount(0).convergedAtIteration(0)
                .build();

            List<SpecificObservation> observations = collector.collect(trace);

            long convergence = observations.stream()
                .filter(o -> o.type() == ObservationType.CONVERGENCE_PATTERN).count();
            long exhausted = observations.stream()
                .filter(o -> o.description().contains("exhausted")).count();
            assertEquals(0, convergence + exhausted,
                "Non-iterative workflows should produce no convergence observations");
        }

        @Test
        @DisplayName("convergence evidence includes iteration details")
        void convergenceEvidenceComplete() {
            ExecutionTrace trace = traceBuilder()
                .iterationCount(5).convergedAtIteration(2)
                .build();

            List<SpecificObservation> observations = collector.collect(trace);
            SpecificObservation conv = observations.stream()
                .filter(o -> o.type() == ObservationType.CONVERGENCE_PATTERN)
                .findFirst().orElseThrow();

            assertEquals(2, conv.evidence().get("converged_at"));
            assertEquals(5, conv.evidence().get("max_iterations"));
        }
    }

    // ================================================================
    // TOOL SELECTION — Flags tools with <50% success rate across 3+ calls.
    // ================================================================

    @Nested
    @DisplayName("Tool Selection Pattern Detection")
    class ToolSelectionPatterns {

        @Test
        @DisplayName("flags tool with low success rate (<50%)")
        void flagsLowSuccessRate() {
            ExecutionTrace trace = traceBuilder()
                .addToolCall(new ToolCallTrace("web_search", "t1", false, 500, 100))
                .addToolCall(new ToolCallTrace("web_search", "t1", false, 600, 0))
                .addToolCall(new ToolCallTrace("web_search", "t2", true, 400, 200))
                .addToolCall(new ToolCallTrace("web_search", "t2", false, 500, 0))
                .build();

            List<SpecificObservation> observations = collector.collect(trace);

            long toolObs = observations.stream()
                .filter(o -> o.type() == ObservationType.TOOL_SELECTION).count();
            assertTrue(toolObs >= 1,
                "Tool with 25% success rate (1/4 calls) should be flagged");
        }

        @Test
        @DisplayName("does not flag tool with high success rate")
        void doesNotFlagHighSuccessRate() {
            ExecutionTrace trace = traceBuilder()
                .addToolCall(new ToolCallTrace("file_read", "t1", true, 50, 500))
                .addToolCall(new ToolCallTrace("file_read", "t1", true, 60, 400))
                .addToolCall(new ToolCallTrace("file_read", "t2", true, 55, 450))
                .build();

            List<SpecificObservation> observations = collector.collect(trace);

            long toolObs = observations.stream()
                .filter(o -> o.type() == ObservationType.TOOL_SELECTION).count();
            assertEquals(0, toolObs,
                "Tool with 100% success rate should not be flagged");
        }

        @Test
        @DisplayName("requires minimum 3 samples to flag")
        void requiresMinimumSamples() {
            ExecutionTrace trace = traceBuilder()
                .addToolCall(new ToolCallTrace("rare_tool", "t1", false, 500, 0))
                .addToolCall(new ToolCallTrace("rare_tool", "t1", false, 600, 0))
                .build();

            List<SpecificObservation> observations = collector.collect(trace);

            long toolObs = observations.stream()
                .filter(o -> o.type() == ObservationType.TOOL_SELECTION).count();
            assertEquals(0, toolObs,
                "Only 2 calls — below minimum 3 samples, should not flag");
        }
    }

    // ================================================================
    // SKILL GRADUATION CANDIDATES — validated + reused >=2 + quality >=0.8
    // ================================================================

    @Nested
    @DisplayName("Skill Graduation Detection")
    class SkillGraduation {

        @Test
        @DisplayName("detects graduation candidate (validated, reused 3x, quality 0.85)")
        void detectsGraduationCandidate() {
            ExecutionTrace trace = traceBuilder()
                .addSkillTrace(new SkillTrace("s1", "csv_parser", "Parse CSV data", true, 3, 0.85))
                .build();

            List<SpecificObservation> observations = collector.collect(trace);

            long skills = observations.stream()
                .filter(o -> o.type() == ObservationType.SUCCESSFUL_SKILL).count();
            assertEquals(1, skills,
                "Skill meeting all graduation criteria should be flagged");
        }

        @Test
        @DisplayName("does not flag unvalidated skill")
        void doesNotFlagUnvalidated() {
            ExecutionTrace trace = traceBuilder()
                .addSkillTrace(new SkillTrace("s1", "broken_parser", "Bad skill", false, 5, 0.9))
                .build();

            List<SpecificObservation> observations = collector.collect(trace);

            long skills = observations.stream()
                .filter(o -> o.type() == ObservationType.SUCCESSFUL_SKILL).count();
            assertEquals(0, skills,
                "Unvalidated skill should not be flagged for graduation");
        }

        @Test
        @DisplayName("does not flag skill with low reuse count")
        void doesNotFlagLowReuse() {
            ExecutionTrace trace = traceBuilder()
                .addSkillTrace(new SkillTrace("s1", "once_used", "Single use skill", true, 1, 0.9))
                .build();

            List<SpecificObservation> observations = collector.collect(trace);

            long skills = observations.stream()
                .filter(o -> o.type() == ObservationType.SUCCESSFUL_SKILL).count();
            assertEquals(0, skills,
                "Skill with reuseCount=1 (below threshold of 2) should not be flagged");
        }

        @Test
        @DisplayName("does not flag skill with low quality")
        void doesNotFlagLowQuality() {
            ExecutionTrace trace = traceBuilder()
                .addSkillTrace(new SkillTrace("s1", "low_quality", "Low quality skill", true, 5, 0.5))
                .build();

            List<SpecificObservation> observations = collector.collect(trace);

            long skills = observations.stream()
                .filter(o -> o.type() == ObservationType.SUCCESSFUL_SKILL).count();
            assertEquals(0, skills,
                "Skill with quality 0.5 (below 0.8 threshold) should not be flagged");
        }
    }

    // ================================================================
    // ANTI-PATTERN DETECTION — Agent spinning (>5 turns, no tool calls)
    // ================================================================

    @Nested
    @DisplayName("Anti-Pattern Detection")
    class AntiPatternDetection {

        @Test
        @DisplayName("detects agent spinning (6 turns, no tools)")
        void detectsSpinning() {
            ExecutionTrace trace = traceBuilder()
                .addTaskTrace(taskTrace("task-1", true, 3000, 1500, 6, List.of()))
                .totalPromptTokens(3000).totalCompletionTokens(1500)
                .build();

            List<SpecificObservation> observations = collector.collect(trace);

            long spinning = observations.stream()
                .filter(o -> o.type() == ObservationType.ANTI_PATTERN)
                .filter(o -> o.description().contains("no tool calls"))
                .count();
            assertEquals(1, spinning,
                "Agent with 6 turns and no tools is spinning");
        }

        @Test
        @DisplayName("does not flag agent with tools even if many turns")
        void doesNotFlagWithTools() {
            ExecutionTrace trace = traceBuilder()
                .addTaskTrace(taskTrace("task-1", true, 3000, 1500, 8, List.of("web_search", "file_read")))
                .totalPromptTokens(3000).totalCompletionTokens(1500)
                .build();

            List<SpecificObservation> observations = collector.collect(trace);

            long spinning = observations.stream()
                .filter(o -> o.type() == ObservationType.ANTI_PATTERN)
                .filter(o -> o.description().contains("no tool calls"))
                .count();
            assertEquals(0, spinning,
                "Agent using tools is not spinning regardless of turn count");
        }

        @Test
        @DisplayName("does not flag 5 turns (boundary: threshold is >5)")
        void doesNotFlagExactlyFiveTurns() {
            ExecutionTrace trace = traceBuilder()
                .addTaskTrace(taskTrace("task-1", true, 2500, 1200, 5, List.of()))
                .totalPromptTokens(2500).totalCompletionTokens(1200)
                .build();

            List<SpecificObservation> observations = collector.collect(trace);

            long spinning = observations.stream()
                .filter(o -> o.type() == ObservationType.ANTI_PATTERN)
                .filter(o -> o.description().contains("no tool calls"))
                .count();
            assertEquals(0, spinning,
                "Exactly 5 turns is the boundary — should NOT be flagged (threshold is >5)");
        }
    }

    // ================================================================
    // AGGREGATE BEHAVIOR — Verify the collector produces the right
    // mix of observation types from a realistic trace.
    // ================================================================

    @Nested
    @DisplayName("Aggregate Collection Behavior")
    class AggregateBehavior {

        @Test
        @DisplayName("empty trace produces no observations")
        void emptyTrace() {
            ExecutionTrace trace = traceBuilder().build();

            List<SpecificObservation> observations = collector.collect(trace);

            assertTrue(observations.isEmpty(),
                "Empty trace should produce no observations");
        }

        @Test
        @DisplayName("realistic trace produces mixed observation types")
        void realisticTrace() {
            ExecutionTrace trace = traceBuilder()
                // Task 1: expensive success
                .addTaskTrace(taskTrace("task-1", true, 8000, 4000, 3, List.of("web_search")))
                // Task 2: structural failure
                .addTaskTrace(new TaskTrace("task-2", "Analyst", false, 1000, 500, 2,
                    List.of("csv_analysis"), "Schema validation error", Duration.ofSeconds(5)))
                // Task 3: spinning agent
                .addTaskTrace(taskTrace("task-3", true, 2000, 1000, 7, List.of()))
                .totalPromptTokens(11000).totalCompletionTokens(5500)
                // Convergence
                .iterationCount(3).convergedAtIteration(2)
                // Tool with low success
                .addToolCall(new ToolCallTrace("web_search", "t1", false, 500, 0))
                .addToolCall(new ToolCallTrace("web_search", "t1", false, 600, 0))
                .addToolCall(new ToolCallTrace("web_search", "t2", true, 400, 200))
                // Graduated skill
                .addSkillTrace(new SkillTrace("s1", "data_parser", "Parse data", true, 3, 0.9))
                .build();

            List<SpecificObservation> observations = collector.collect(trace);

            assertTrue(observations.size() >= 4,
                "Realistic trace should produce multiple observation types. Got: " + observations.size());

            // Check that multiple types are represented
            Set<ObservationType> types = observations.stream()
                .map(SpecificObservation::type)
                .collect(java.util.stream.Collectors.toSet());

            assertTrue(types.contains(ObservationType.FAILURE),
                "Should detect the structural failure");
            assertTrue(types.contains(ObservationType.CONVERGENCE_PATTERN),
                "Should detect early convergence");
            assertTrue(types.contains(ObservationType.ANTI_PATTERN),
                "Should detect the spinning agent");
            assertTrue(types.contains(ObservationType.SUCCESSFUL_SKILL),
                "Should detect the graduation candidate");
        }

        @Test
        @DisplayName("all observations have the workflow shape from the trace")
        void allObservationsHaveWorkflowShape() {
            ExecutionTrace trace = traceBuilder()
                .addTaskTrace(new TaskTrace("t1", "Agent", false, 1000, 500, 2,
                    List.of(), "Validation error", Duration.ofSeconds(3)))
                .totalPromptTokens(1000).totalCompletionTokens(500)
                .iterationCount(3).convergedAtIteration(2)
                .build();

            List<SpecificObservation> observations = collector.collect(trace);

            for (SpecificObservation obs : observations) {
                assertNotNull(obs.workflowShape(),
                    "Every observation must carry the workflow shape for cross-validation");
                assertEquals("SELF_IMPROVING", obs.workflowShape().processType());
            }
        }
    }

    // ================================================================
    // Helpers — Build realistic ExecutionTrace objects
    // ================================================================

    private static final WorkflowShape DEFAULT_SHAPE = new WorkflowShape(
        3, 2, true, false, true,
        Set.of("WEB", "DATA"), "SELF_IMPROVING", 2, 3.0, true, false
    );

    private ExecutionTrace.Builder traceBuilder() {
        return ExecutionTrace.builder()
            .swarmId("test-swarm")
            .workflowShape(DEFAULT_SHAPE)
            .modelName("test-model")
            .totalDuration(Duration.ofSeconds(30));
    }

    private ExecutionTrace traceWithFailedTask(String taskId, String role, String failureReason) {
        return traceBuilder()
            .addTaskTrace(new TaskTrace(taskId, role, false, 1000, 500, 2,
                List.of(), failureReason, Duration.ofSeconds(5)))
            .totalPromptTokens(1000).totalCompletionTokens(500)
            .build();
    }

    private TaskTrace taskTrace(String id, boolean succeeded, long prompt, long completion,
                                 int turns, List<String> tools) {
        return new TaskTrace(id, "Test Agent", succeeded, prompt, completion,
            turns, tools, succeeded ? null : "Failed", Duration.ofSeconds(5));
    }
}
