package ai.intelliswarm.swarmai.selfimproving.model;

import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.output.TaskOutput;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Immutable capture of everything that happened during Phase 1 (workflow execution).
 * The ImprovementPhase uses this to identify what to improve.
 */
public record ExecutionTrace(
        String swarmId,
        WorkflowShape workflowShape,
        SwarmOutput swarmOutput,
        List<TaskTrace> taskTraces,
        List<PolicyDecisionTrace> policyDecisions,
        List<SkillTrace> skillsGenerated,
        List<ToolCallTrace> toolCalls,
        long totalPromptTokens,
        long totalCompletionTokens,
        String modelName,
        int iterationCount,
        int convergedAtIteration,
        Duration totalDuration,
        Instant timestamp
) {

    public static Builder builder() {
        return new Builder();
    }

    public double tokenEfficiency() {
        if (totalPromptTokens + totalCompletionTokens == 0) return 0;
        long successful = taskTraces.stream().filter(TaskTrace::succeeded).count();
        return (double) successful / (totalPromptTokens + totalCompletionTokens) * 100_000;
    }

    public List<TaskTrace> failedTasks() {
        return taskTraces.stream().filter(t -> !t.succeeded()).toList();
    }

    public List<TaskTrace> expensiveTasks(double topPercent) {
        long threshold = (long) (totalTokens() * topPercent);
        return taskTraces.stream()
                .sorted(Comparator.comparingLong(TaskTrace::totalTokens).reversed())
                .takeWhile(new java.util.function.Predicate<>() {
                    long cumulative = 0;

                    @Override
                    public boolean test(TaskTrace t) {
                        cumulative += t.totalTokens();
                        return cumulative <= threshold;
                    }
                })
                .toList();
    }

    public long totalTokens() {
        return totalPromptTokens + totalCompletionTokens;
    }

    public record TaskTrace(
            String taskId,
            String agentRole,
            boolean succeeded,
            long promptTokens,
            long completionTokens,
            int turnCount,
            List<String> toolsUsed,
            String failureReason,
            Duration duration
    ) {
        public long totalTokens() {
            return promptTokens + completionTokens;
        }
    }

    public record PolicyDecisionTrace(
            String decisionType,
            String action,
            Map<String, Object> context,
            double confidence
    ) {}

    public record SkillTrace(
            String skillId,
            String skillName,
            String gapDescription,
            boolean validated,
            int reuseCount,
            double qualityScore
    ) {}

    public record ToolCallTrace(
            String toolName,
            String taskId,
            boolean succeeded,
            long durationMs,
            int responseSize
    ) {}

    public static class Builder {
        private String swarmId;
        private WorkflowShape workflowShape;
        private SwarmOutput swarmOutput;
        private final List<TaskTrace> taskTraces = new ArrayList<>();
        private final List<PolicyDecisionTrace> policyDecisions = new ArrayList<>();
        private final List<SkillTrace> skillsGenerated = new ArrayList<>();
        private final List<ToolCallTrace> toolCalls = new ArrayList<>();
        private long totalPromptTokens;
        private long totalCompletionTokens;
        private String modelName;
        private int iterationCount;
        private int convergedAtIteration;
        private Duration totalDuration;

        public Builder swarmId(String swarmId) { this.swarmId = swarmId; return this; }
        public Builder workflowShape(WorkflowShape shape) { this.workflowShape = shape; return this; }
        public Builder swarmOutput(SwarmOutput output) { this.swarmOutput = output; return this; }
        public Builder addTaskTrace(TaskTrace trace) { this.taskTraces.add(trace); return this; }
        public Builder addPolicyDecision(PolicyDecisionTrace trace) { this.policyDecisions.add(trace); return this; }
        public Builder addSkillTrace(SkillTrace trace) { this.skillsGenerated.add(trace); return this; }
        public Builder addToolCall(ToolCallTrace trace) { this.toolCalls.add(trace); return this; }
        public Builder totalPromptTokens(long tokens) { this.totalPromptTokens = tokens; return this; }
        public Builder totalCompletionTokens(long tokens) { this.totalCompletionTokens = tokens; return this; }
        public Builder modelName(String name) { this.modelName = name; return this; }
        public Builder iterationCount(int count) { this.iterationCount = count; return this; }
        public Builder convergedAtIteration(int iter) { this.convergedAtIteration = iter; return this; }
        public Builder totalDuration(Duration duration) { this.totalDuration = duration; return this; }

        public Builder fromSwarmOutput(SwarmOutput output) {
            this.swarmOutput = output;
            this.swarmId = output.getSwarmId();
            this.totalPromptTokens = output.getTotalPromptTokens();
            this.totalCompletionTokens = output.getTotalCompletionTokens();

            for (TaskOutput taskOutput : output.getTaskOutputs()) {
                Long prompt = taskOutput.getPromptTokens() != null ? taskOutput.getPromptTokens() : 0L;
                Long completion = taskOutput.getCompletionTokens() != null ? taskOutput.getCompletionTokens() : 0L;
                addTaskTrace(new TaskTrace(
                        taskOutput.getTaskId(),
                        (String) taskOutput.getMetadata().getOrDefault("agentRole", "unknown"),
                        taskOutput.isSuccessful(),
                        prompt,
                        completion,
                        taskOutput.getMetadata().containsKey("turns")
                                ? ((Number) taskOutput.getMetadata().get("turns")).intValue() : 1,
                        List.of(),
                        taskOutput.isSuccessful() ? null : "Task failed",
                        Duration.ofMillis(taskOutput.getExecutionTimeMs())
                ));
            }
            return this;
        }

        public ExecutionTrace build() {
            return new ExecutionTrace(
                    swarmId, workflowShape, swarmOutput, List.copyOf(taskTraces),
                    List.copyOf(policyDecisions), List.copyOf(skillsGenerated),
                    List.copyOf(toolCalls), totalPromptTokens, totalCompletionTokens,
                    modelName, iterationCount, convergedAtIteration, totalDuration,
                    Instant.now()
            );
        }
    }
}
