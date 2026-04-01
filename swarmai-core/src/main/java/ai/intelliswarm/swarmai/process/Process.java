package ai.intelliswarm.swarmai.process;

import ai.intelliswarm.swarmai.budget.BudgetTracker;
import ai.intelliswarm.swarmai.state.AgentState;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;

import java.util.List;
import java.util.Map;

public interface Process {

    SwarmOutput execute(List<Task> tasks, Map<String, Object> inputs, String swarmId);

    /**
     * Executes tasks with type-safe AgentState.
     * Default implementation delegates to the Map-based execute for backward compatibility.
     */
    default SwarmOutput execute(List<Task> tasks, AgentState state, String swarmId) {
        return execute(tasks, state != null ? state.data() : Map.of(), swarmId);
    }

    ProcessType getType();

    boolean isAsync();

    void validateTasks(List<Task> tasks);

    /**
     * Records token usage from a completed task to the budget tracker.
     * No-op if tracker is null (backward compatible).
     */
    default void recordBudgetUsage(BudgetTracker tracker, String swarmId, TaskOutput output, String modelName) {
        if (tracker == null || output == null || swarmId == null) {
            return;
        }
        long prompt = output.getPromptTokens() != null ? output.getPromptTokens() : 0;
        long completion = output.getCompletionTokens() != null ? output.getCompletionTokens() : 0;
        if (prompt > 0 || completion > 0) {
            tracker.recordUsage(swarmId, prompt, completion, modelName != null ? modelName : "unknown");
        }
    }

    /**
     * Aggregates reactive-loop metadata (turns, compacted turns) from individual TaskOutputs.
     * Call this when building SwarmOutput to populate totalTurns and totalCompactedTurns.
     */
    default void aggregateReactiveMetrics(SwarmOutput.Builder builder, List<TaskOutput> outputs) {
        if (outputs == null || outputs.isEmpty()) return;
        long totalTurns = 0;
        long totalCompactedTurns = 0;
        for (TaskOutput output : outputs) {
            if (output.getMetadata() != null) {
                Object turns = output.getMetadata().get("turns");
                if (turns instanceof Number n) totalTurns += n.longValue();
                Object compacted = output.getMetadata().get("compactedTurns");
                if (compacted instanceof Number n) totalCompactedTurns += n.longValue();
            }
        }
        if (totalTurns > 0) builder.metadata("totalTurns", totalTurns);
        if (totalCompactedTurns > 0) builder.metadata("totalCompactedTurns", totalCompactedTurns);
    }

    /**
     * Interpolates input variables into a template string.
     * Replaces {variable_name} placeholders with values from the inputs map.
     */
    default String interpolateInputs(String template, Map<String, Object> inputs) {
        if (template == null || inputs == null || inputs.isEmpty()) {
            return template;
        }
        String result = template;
        for (Map.Entry<String, Object> entry : inputs.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return result;
    }
}