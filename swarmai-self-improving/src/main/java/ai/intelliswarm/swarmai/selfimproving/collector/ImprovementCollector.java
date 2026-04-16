package ai.intelliswarm.swarmai.selfimproving.collector;

import ai.intelliswarm.swarmai.selfimproving.model.*;
import ai.intelliswarm.swarmai.selfimproving.model.ExecutionTrace.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Gathers raw observations from an ExecutionTrace.
 * Classifies each observation by type for downstream processing by the priority pipeline.
 * This is the first step in the 10% improvement phase.
 */
public class ImprovementCollector {

    private static final Logger log = LoggerFactory.getLogger(ImprovementCollector.class);

    /**
     * Analyze an execution trace and produce a list of specific observations.
     */
    public List<SpecificObservation> collect(ExecutionTrace trace) {
        List<SpecificObservation> observations = new ArrayList<>();

        observations.addAll(collectFailures(trace));
        observations.addAll(collectExpensiveTasks(trace));
        observations.addAll(collectConvergencePatterns(trace));
        observations.addAll(collectToolSelectionPatterns(trace));
        observations.addAll(collectSkillPatterns(trace));
        observations.addAll(collectAntiPatterns(trace));
        observations.addAll(collectDecisionQuality(trace));
        observations.addAll(collectProcessSuitability(trace));
        observations.addAll(collectCoordinationQuality(trace));

        log.info("Collected {} observations from swarm {}", observations.size(), trace.swarmId());
        return observations;
    }

    private List<SpecificObservation> collectFailures(ExecutionTrace trace) {
        return trace.failedTasks().stream()
                .filter(task -> !isTransientFailure(task.failureReason()))
                .map(task -> SpecificObservation.failure(
                        trace.workflowShape(),
                        "Structural failure in task '%s': %s".formatted(task.taskId(), task.failureReason()),
                        Map.of(
                                "task_id", task.taskId(),
                                "agent_role", task.agentRole() != null ? task.agentRole() : "unknown",
                                "tools_used", task.toolsUsed(),
                                "turn_count", task.turnCount(),
                                "tokens_spent", task.totalTokens()
                        )
                ))
                .toList();
    }

    private List<SpecificObservation> collectExpensiveTasks(ExecutionTrace trace) {
        if (trace.taskTraces().size() <= 1) return List.of(); // single task always uses 100%
        // Tasks consuming more than 40% of total tokens are worth investigating
        long threshold = (long) (trace.totalTokens() * 0.40);
        return trace.taskTraces().stream()
                .filter(t -> t.succeeded() && t.totalTokens() > threshold)
                .map(task -> {
                    double costRatio = (double) task.totalTokens() / trace.totalTokens();
                    return SpecificObservation.expensiveTask(
                            trace.workflowShape(),
                            "Task '%s' consumed %.0f%% of total tokens (%d tokens, %d turns)".formatted(
                                    task.taskId(), costRatio * 100, task.totalTokens(), task.turnCount()),
                            Map.of(
                                    "task_id", task.taskId(),
                                    "token_ratio", costRatio,
                                    "turn_count", task.turnCount(),
                                    "tools_used", task.toolsUsed()
                            ),
                            costRatio
                    );
                })
                .toList();
    }

    private List<SpecificObservation> collectConvergencePatterns(ExecutionTrace trace) {
        List<SpecificObservation> observations = new ArrayList<>();

        if (trace.iterationCount() > 0 && trace.convergedAtIteration() > 0) {
            // Did it converge early? That's a useful default to learn.
            if (trace.convergedAtIteration() < trace.iterationCount()) {
                observations.add(SpecificObservation.convergencePattern(
                        trace.workflowShape(),
                        "Workflow converged at iteration %d (max was %d)".formatted(
                                trace.convergedAtIteration(), trace.iterationCount()),
                        Map.of(
                                "converged_at", trace.convergedAtIteration(),
                                "max_iterations", trace.iterationCount(),
                                "process_type", trace.workflowShape().processType(),
                                "task_count", trace.workflowShape().taskCount(),
                                "max_depth", trace.workflowShape().maxDependencyDepth()
                        )
                ));
            }

            // Did it exhaust all iterations without converging? That's an anti-pattern signal.
            // Only meaningful for iterative/self-improving processes with more than 1 iteration.
            if (trace.convergedAtIteration() >= trace.iterationCount() && trace.iterationCount() > 1) {
                observations.add(SpecificObservation.antiPattern(
                        trace.workflowShape(),
                        "Workflow exhausted max iterations (%d) without converging".formatted(trace.iterationCount()),
                        Map.of(
                                "max_iterations", trace.iterationCount(),
                                "process_type", trace.workflowShape().processType()
                        )
                ));
            }
        }

        return observations;
    }

    private List<SpecificObservation> collectToolSelectionPatterns(ExecutionTrace trace) {
        // Group tool calls by task and analyze success rates
        Map<String, List<ToolCallTrace>> byTool = trace.toolCalls().stream()
                .collect(Collectors.groupingBy(ToolCallTrace::toolName));

        return byTool.entrySet().stream()
                .filter(e -> e.getValue().size() >= 3) // need enough samples
                .map(entry -> {
                    String tool = entry.getKey();
                    List<ToolCallTrace> calls = entry.getValue();
                    long successes = calls.stream().filter(ToolCallTrace::succeeded).count();
                    double successRate = (double) successes / calls.size();

                    if (successRate < 0.5) {
                        return SpecificObservation.toolSelection(
                                trace.workflowShape(),
                                "Tool '%s' has %.0f%% success rate across %d calls".formatted(
                                        tool, successRate * 100, calls.size()),
                                Map.of(
                                        "tool_name", tool,
                                        "success_rate", successRate,
                                        "total_calls", calls.size(),
                                        "avg_duration_ms", calls.stream().mapToLong(ToolCallTrace::durationMs).average().orElse(0)
                                )
                        );
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .toList();
    }

    private List<SpecificObservation> collectSkillPatterns(ExecutionTrace trace) {
        List<SpecificObservation> observations = new ArrayList<>();

        for (SkillTrace skill : trace.skillsGenerated()) {
            if (skill.validated() && skill.reuseCount() >= 2 && skill.qualityScore() >= 0.8) {
                // This skill is a graduation candidate
                observations.add(new SpecificObservation(
                        UUID.randomUUID().toString(),
                        SpecificObservation.ObservationType.SUCCESSFUL_SKILL,
                        trace.workflowShape(),
                        "Skill '%s' validated, reused %d times, quality %.2f — graduation candidate".formatted(
                                skill.skillName(), skill.reuseCount(), skill.qualityScore()),
                        Map.of(
                                "skill_id", skill.skillId(),
                                "skill_name", skill.skillName(),
                                "reuse_count", skill.reuseCount(),
                                "quality_score", skill.qualityScore(),
                                "gap_description", skill.gapDescription()
                        ),
                        skill.qualityScore(),
                        java.time.Instant.now()
                ));
            }
        }

        return observations;
    }

    private List<SpecificObservation> collectAntiPatterns(ExecutionTrace trace) {
        List<SpecificObservation> observations = new ArrayList<>();

        // Agent spinning: too many turns with no tool calls
        for (TaskTrace task : trace.taskTraces()) {
            if (task.turnCount() > 5 && task.toolsUsed().isEmpty()) {
                observations.add(SpecificObservation.antiPattern(
                        trace.workflowShape(),
                        "Agent '%s' used %d turns with no tool calls on task '%s'".formatted(
                                task.agentRole(), task.turnCount(), task.taskId()),
                        Map.of(
                                "task_id", task.taskId(),
                                "turn_count", task.turnCount(),
                                "agent_role", task.agentRole() != null ? task.agentRole() : "unknown"
                        )
                ));
            }
        }

        return observations;
    }

    /**
     * Detect when agents retry excessively (more than 2 retries signals a decision-quality issue).
     */
    private List<SpecificObservation> collectDecisionQuality(ExecutionTrace trace) {
        List<SpecificObservation> observations = new ArrayList<>();
        for (TaskTrace task : trace.taskTraces()) {
            // turnCount > 3 on a successful task means the agent needed many attempts
            // to arrive at a good answer — the decision pipeline could be improved.
            if (task.succeeded() && task.turnCount() > 3) {
                observations.add(SpecificObservation.decisionQuality(
                        trace.workflowShape(),
                        "Agent '%s' required %d turns to complete task '%s' successfully — possible decision-quality issue".formatted(
                                task.agentRole(), task.turnCount(), task.taskId()),
                        Map.of(
                                "task_id", task.taskId(),
                                "agent_role", task.agentRole() != null ? task.agentRole() : "unknown",
                                "turn_count", task.turnCount(),
                                "tokens_spent", task.totalTokens()
                        )
                ));
            }
        }
        return observations;
    }

    /**
     * Detect sequential workflows where tasks have no dependencies — they should
     * be running in parallel.
     */
    private List<SpecificObservation> collectProcessSuitability(ExecutionTrace trace) {
        if (trace.workflowShape() == null) return List.of();
        // A sequential workflow with multiple independent tasks (maxDependencyDepth == 0
        // and taskCount > 1) would likely benefit from parallel execution.
        if ("SEQUENTIAL".equals(trace.workflowShape().processType())
                && trace.workflowShape().taskCount() > 1
                && trace.workflowShape().maxDependencyDepth() == 0) {
            return List.of(SpecificObservation.processSuitability(
                    trace.workflowShape(),
                    "Sequential workflow with %d independent tasks (depth 0) — parallel execution may reduce latency".formatted(
                            trace.workflowShape().taskCount()),
                    Map.of(
                            "process_type", trace.workflowShape().processType(),
                            "task_count", trace.workflowShape().taskCount(),
                            "max_depth", trace.workflowShape().maxDependencyDepth(),
                            "agent_count", trace.workflowShape().agentCount()
                    )
            ));
        }
        return List.of();
    }

    /**
     * Detect when downstream tasks ignore upstream output — low context utilization
     * suggests poor agent coordination.
     */
    private List<SpecificObservation> collectCoordinationQuality(ExecutionTrace trace) {
        List<SpecificObservation> observations = new ArrayList<>();
        // In a sequential workflow with multiple tasks, if downstream tasks use
        // significantly more tokens than upstream ones, it may indicate they are
        // not leveraging the upstream context effectively.
        List<TaskTrace> tasks = trace.taskTraces();
        if (tasks.size() < 2) return observations;

        for (int i = 1; i < tasks.size(); i++) {
            TaskTrace upstream = tasks.get(i - 1);
            TaskTrace downstream = tasks.get(i);
            // If downstream uses 3x+ more tokens than upstream and upstream succeeded,
            // the downstream agent may be re-deriving context instead of using the handoff.
            if (upstream.succeeded() && downstream.succeeded()
                    && upstream.totalTokens() > 0
                    && downstream.totalTokens() > upstream.totalTokens() * 3) {
                observations.add(SpecificObservation.coordinationQuality(
                        trace.workflowShape(),
                        "Task '%s' used %dx more tokens than predecessor '%s' — possible context handoff inefficiency".formatted(
                                downstream.taskId(),
                                downstream.totalTokens() / Math.max(1, upstream.totalTokens()),
                                upstream.taskId()),
                        Map.of(
                                "upstream_task", upstream.taskId(),
                                "downstream_task", downstream.taskId(),
                                "upstream_tokens", upstream.totalTokens(),
                                "downstream_tokens", downstream.totalTokens(),
                                "token_ratio", (double) downstream.totalTokens() / Math.max(1, upstream.totalTokens())
                        )
                ));
            }
        }
        return observations;
    }

    private boolean isTransientFailure(String reason) {
        if (reason == null) return false;
        String lower = reason.toLowerCase();
        return lower.contains("timeout") || lower.contains("rate limit")
                || lower.contains("429") || lower.contains("503")
                || lower.contains("connection refused") || lower.contains("network");
    }
}
