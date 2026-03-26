package ai.intelliswarm.swarmai.process;

import ai.intelliswarm.swarmai.budget.BudgetTracker;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;

import java.util.List;
import java.util.Map;

public interface Process {

    SwarmOutput execute(List<Task> tasks, Map<String, Object> inputs, String swarmId);

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