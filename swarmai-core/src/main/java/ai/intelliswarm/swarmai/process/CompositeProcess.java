package ai.intelliswarm.swarmai.process;

import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.TaskOutput;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * A process that chains multiple processes sequentially, passing the output
 * of each stage as context to the next. Enables complex workflows like
 * Sequential → Hierarchical → Iterative.
 *
 * <p>Usage:
 * <pre>{@code
 * Process pipeline = CompositeProcess.of(
 *     new SequentialProcess(agents, publisher),
 *     new HierarchicalProcess(agents, manager, publisher)
 * );
 *
 * SwarmOutput output = pipeline.execute(tasks, inputs, swarmId);
 * }</pre>
 */
public class CompositeProcess implements Process {

    private final List<Process> stages;

    private CompositeProcess(List<Process> stages) {
        if (stages == null || stages.isEmpty()) {
            throw new IllegalArgumentException("CompositeProcess requires at least one stage");
        }
        this.stages = List.copyOf(stages);
    }

    /**
     * Creates a composite process from the given stages, executed in order.
     */
    public static CompositeProcess of(Process... stages) {
        return new CompositeProcess(Arrays.asList(stages));
    }

    /**
     * Creates a composite process from a list of stages.
     */
    public static CompositeProcess of(List<Process> stages) {
        return new CompositeProcess(stages);
    }

    @Override
    public SwarmOutput execute(List<Task> tasks, Map<String, Object> inputs, String swarmId) {
        LocalDateTime startTime = LocalDateTime.now();
        List<TaskOutput> allOutputs = new ArrayList<>();
        Map<String, Object> currentInputs = inputs != null ? new HashMap<>(inputs) : new HashMap<>();
        SwarmOutput lastOutput = null;

        for (int i = 0; i < stages.size(); i++) {
            Process stage = stages.get(i);

            // Reset tasks between stages so they can be re-executed
            if (i > 0) {
                tasks.forEach(Task::reset);
            }

            // Inject prior stage output as context for next stage
            if (lastOutput != null && lastOutput.getFinalOutput() != null) {
                currentInputs.put("__priorStageOutput", lastOutput.getFinalOutput());
            }

            lastOutput = stage.execute(tasks, currentInputs, swarmId);
            allOutputs.addAll(lastOutput.getTaskOutputs());
        }

        LocalDateTime endTime = LocalDateTime.now();

        return SwarmOutput.builder()
                .swarmId(swarmId)
                .rawOutput(lastOutput != null ? lastOutput.getRawOutput() : null)
                .finalOutput(lastOutput != null ? lastOutput.getFinalOutput() : null)
                .taskOutputs(allOutputs)
                .startTime(startTime)
                .endTime(endTime)
                .executionTime(Duration.between(startTime, endTime))
                .successful(lastOutput != null && lastOutput.isSuccessful())
                .metadata("stages", stages.size())
                .metadata("processTypes", stages.stream()
                        .map(p -> p.getType().name())
                        .toList())
                .build();
    }

    @Override
    public ProcessType getType() {
        return ProcessType.COMPOSITE;
    }

    @Override
    public boolean isAsync() {
        return false;
    }

    @Override
    public void validateTasks(List<Task> tasks) {
        // Delegate validation to each stage
        for (Process stage : stages) {
            stage.validateTasks(tasks);
        }
    }

    /**
     * Returns the number of stages in this pipeline.
     */
    public int stageCount() {
        return stages.size();
    }

    /**
     * Returns the process at the given stage index.
     */
    public Process getStage(int index) {
        return stages.get(index);
    }

    @Override
    public String toString() {
        return "CompositeProcess{stages=" + stages.size() +
                ", types=" + stages.stream().map(p -> p.getType().name()).toList() + "}";
    }
}
