package ai.intelliswarm.swarmai.process;

import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;

import java.util.List;
import java.util.Map;

public interface Process {
    
    SwarmOutput execute(List<Task> tasks, Map<String, Object> inputs);
    
    ProcessType getType();
    
    boolean isAsync();
    
    void validateTasks(List<Task> tasks);
}