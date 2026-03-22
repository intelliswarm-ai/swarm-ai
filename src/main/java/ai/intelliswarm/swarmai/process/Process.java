package ai.intelliswarm.swarmai.process;

import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;

import java.util.List;
import java.util.Map;

public interface Process {

    SwarmOutput execute(List<Task> tasks, Map<String, Object> inputs, String swarmId);

    ProcessType getType();

    boolean isAsync();

    void validateTasks(List<Task> tasks);

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