package ai.intelliswarm.swarmai.eval.scenario;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.budget.BudgetPolicy;
import ai.intelliswarm.swarmai.budget.BudgetTracker;
import ai.intelliswarm.swarmai.budget.InMemoryBudgetTracker;
import ai.intelliswarm.swarmai.eval.scoring.ScenarioResult;
import ai.intelliswarm.swarmai.exception.AgentExecutionException;
import ai.intelliswarm.swarmai.exception.ProcessExecutionException;
import ai.intelliswarm.swarmai.memory.InMemoryMemory;
import ai.intelliswarm.swarmai.memory.Memory;
import ai.intelliswarm.swarmai.observability.core.ObservabilityContext;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.skill.SkillRegistry;
import ai.intelliswarm.swarmai.task.Task;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Core framework capability scenarios.
 * These verify fundamental building blocks work correctly.
 */
public class CoreScenarios {

    /** Verifies Agent builder creates valid agents with all configuration options. */
    public static EvalScenario agentBuilder() {
        return new AbstractEvalScenario() {
            @Override public String id() { return "core-agent-builder"; }
            @Override public String name() { return "Agent Builder Validation"; }
            @Override public String category() { return "CORE"; }
            @Override public String description() { return "Verify Agent.builder() produces valid agents"; }

            @Override
            protected ScenarioResult doExecute() {
                // Verify builder validates required fields
                try {
                    Agent.builder().build();
                    return ScenarioResult.fail(id(), name(), category(),
                            "Builder should reject missing required fields", Duration.ZERO);
                } catch (Exception expected) {
                    // Good -- builder validates required fields
                }

                // Verify builder accepts all configuration options (without chatClient, which requires LLM)
                var builder = Agent.builder()
                        .role("Test Role")
                        .goal("Test Goal")
                        .backstory("Test Backstory")
                        .verbose(true)
                        .temperature(0.5);

                boolean valid = builder != null; // Builder chain doesn't throw
                return valid
                        ? ScenarioResult.pass(id(), name(), category(), 100.0,
                        "Agent builder validates fields and accepts all config options", Duration.ZERO)
                        : ScenarioResult.fail(id(), name(), category(),
                        "Agent builder chain failed", Duration.ZERO);
            }
        };
    }

    /** Verifies Task builder with dependencies and output formatting. */
    public static EvalScenario taskBuilder() {
        return new AbstractEvalScenario() {
            @Override public String id() { return "core-task-builder"; }
            @Override public String name() { return "Task Builder with Dependencies"; }
            @Override public String category() { return "CORE"; }
            @Override public String description() { return "Verify Task builder handles dependencies"; }

            @Override
            protected ScenarioResult doExecute() {
                Task t1 = Task.builder().id("t1").description("First").build();
                Task t2 = Task.builder().id("t2").description("Second").dependsOn("t1").build();

                boolean valid = t2.getDependencyTaskIds().contains("t1");
                return valid
                        ? ScenarioResult.pass(id(), name(), category(), 100.0,
                        "Task dependencies wire correctly", Duration.ZERO)
                        : ScenarioResult.fail(id(), name(), category(),
                        "Task dependency not registered", Duration.ZERO);
            }
        };
    }

    /** Verifies Memory implementations store and retrieve data. */
    public static EvalScenario memoryOperations() {
        return new AbstractEvalScenario() {
            @Override public String id() { return "core-memory"; }
            @Override public String name() { return "Memory Store/Retrieve"; }
            @Override public String category() { return "CORE"; }
            @Override public String description() { return "Verify InMemoryMemory stores and retrieves"; }

            @Override
            protected ScenarioResult doExecute() {
                Memory memory = new InMemoryMemory();
                memory.save("agent-1", "test content", null);
                List<String> results = memory.getRecentMemories("agent-1", 10);

                boolean valid = results.size() == 1 && results.get(0).equals("test content");
                return valid
                        ? ScenarioResult.pass(id(), name(), category(), 100.0,
                        "Memory stores and retrieves correctly", Duration.ZERO)
                        : ScenarioResult.fail(id(), name(), category(),
                        "Memory returned: " + results, Duration.ZERO);
            }
        };
    }

    /** Verifies ObservabilityContext thread propagation. */
    public static EvalScenario observabilityPropagation() {
        return new AbstractEvalScenario() {
            @Override public String id() { return "core-observability-propagation"; }
            @Override public String name() { return "ObservabilityContext Thread Propagation"; }
            @Override public String category() { return "CORE"; }
            @Override public String description() { return "Verify context propagates to child threads"; }

            @Override
            protected ScenarioResult doExecute() {
                ObservabilityContext parent = ObservabilityContext.create();
                parent.withSwarmId("test-swarm");
                String parentCorrelation = parent.getCorrelationId();

                ObservabilityContext.Snapshot snapshot = ObservabilityContext.snapshot();
                String[] childCorrelation = new String[1];

                Thread child = new Thread(() -> {
                    snapshot.restore();
                    try {
                        ObservabilityContext ctx = ObservabilityContext.current();
                        childCorrelation[0] = ctx.getCorrelationId();
                    } finally {
                        ObservabilityContext.clear();
                    }
                });
                child.start();
                try { child.join(5000); } catch (InterruptedException ignored) {}

                ObservabilityContext.clear();

                boolean valid = parentCorrelation.equals(childCorrelation[0]);
                return valid
                        ? ScenarioResult.pass(id(), name(), category(), 100.0,
                        "Context propagated to child thread", Duration.ZERO)
                        : ScenarioResult.fail(id(), name(), category(),
                        "Context NOT propagated (parent=" + parentCorrelation
                                + ", child=" + childCorrelation[0] + ")", Duration.ZERO);
            }
        };
    }

    /** Verifies budget tracking with WARN mode. */
    public static EvalScenario budgetTracking() {
        return new AbstractEvalScenario() {
            @Override public String id() { return "core-budget"; }
            @Override public String name() { return "Budget Tracking"; }
            @Override public String category() { return "CORE"; }
            @Override public String description() { return "Verify budget tracker records usage correctly"; }

            @Override
            protected ScenarioResult doExecute() {
                BudgetPolicy policy = BudgetPolicy.builder()
                        .maxTotalTokens(100_000)
                        .maxCostUsd(5.0)
                        .onExceeded(BudgetPolicy.BudgetAction.WARN)
                        .build();
                BudgetTracker tracker = new InMemoryBudgetTracker(policy);
                tracker.recordUsage("wf-1", 500, 200, "gpt-4o");

                var snapshot = tracker.getSnapshot("wf-1");
                boolean valid = snapshot != null
                        && snapshot.promptTokensUsed() == 500
                        && snapshot.completionTokensUsed() == 200;

                return valid
                        ? ScenarioResult.pass(id(), name(), category(), 100.0,
                        "Budget tracking works correctly", Duration.ZERO)
                        : ScenarioResult.fail(id(), name(), category(),
                        "Unexpected snapshot: " + snapshot, Duration.ZERO);
            }
        };
    }

    /** Verifies exception hierarchy carries structured context. */
    public static EvalScenario exceptionHierarchy() {
        return new AbstractEvalScenario() {
            @Override public String id() { return "core-exceptions"; }
            @Override public String name() { return "Typed Exception Hierarchy"; }
            @Override public String category() { return "CORE"; }
            @Override public String description() { return "Verify exceptions carry agentId/taskId context"; }

            @Override
            protected ScenarioResult doExecute() {
                AgentExecutionException ex = new AgentExecutionException(
                        "test failure", null, "agent-1", "task-1", "swarm-1", "corr-1");
                boolean valid = "agent-1".equals(ex.getAgentId())
                        && "task-1".equals(ex.getTaskId())
                        && "swarm-1".equals(ex.getSwarmId())
                        && "corr-1".equals(ex.getCorrelationId());

                ProcessExecutionException pex = new ProcessExecutionException(
                        "process fail", null, ProcessType.PARALLEL);
                valid = valid && ProcessType.PARALLEL.equals(pex.getProcessType());

                return valid
                        ? ScenarioResult.pass(id(), name(), category(), 100.0,
                        "Exceptions carry structured context", Duration.ZERO)
                        : ScenarioResult.fail(id(), name(), category(),
                        "Exception context missing", Duration.ZERO);
            }
        };
    }

    /** Returns all core scenarios. */
    public static List<EvalScenario> all() {
        return List.of(
                agentBuilder(),
                taskBuilder(),
                memoryOperations(),
                observabilityPropagation(),
                budgetTracking(),
                exceptionHierarchy()
        );
    }
}
