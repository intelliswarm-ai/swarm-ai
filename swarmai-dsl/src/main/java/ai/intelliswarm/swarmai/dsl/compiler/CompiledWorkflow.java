package ai.intelliswarm.swarmai.dsl.compiler;

import ai.intelliswarm.swarmai.process.CompositeProcess;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;

import java.util.List;
import java.util.Map;

/**
 * A compiled workflow that can be executed. Wraps either a standard {@link Swarm},
 * a {@link CompositeProcess} pipeline, or a {@link GraphExecutor} for graph-based workflows.
 *
 * <pre>{@code
 * CompiledWorkflow workflow = compiler.compileWorkflow(definition);
 * SwarmOutput output = workflow.kickoff(Map.of("topic", "AI Safety"));
 * }</pre>
 */
public class CompiledWorkflow {

    private final Swarm swarm;
    private final CompositeProcess compositeProcess;
    private final GraphExecutor graphExecutor;
    private final List<Task> tasks;
    private final String swarmId;

    private CompiledWorkflow(Swarm swarm) {
        this.swarm = swarm;
        this.compositeProcess = null;
        this.graphExecutor = null;
        this.tasks = null;
        this.swarmId = null;
    }

    private CompiledWorkflow(CompositeProcess compositeProcess, List<Task> tasks, String swarmId) {
        this.swarm = null;
        this.compositeProcess = compositeProcess;
        this.graphExecutor = null;
        this.tasks = tasks;
        this.swarmId = swarmId;
    }

    private CompiledWorkflow(GraphExecutor graphExecutor) {
        this.swarm = null;
        this.compositeProcess = null;
        this.graphExecutor = graphExecutor;
        this.tasks = null;
        this.swarmId = null;
    }

    static CompiledWorkflow fromSwarm(Swarm swarm) {
        return new CompiledWorkflow(swarm);
    }

    static CompiledWorkflow fromComposite(CompositeProcess process, List<Task> tasks, String swarmId) {
        return new CompiledWorkflow(process, tasks, swarmId);
    }

    static CompiledWorkflow fromGraph(GraphExecutor executor) {
        return new CompiledWorkflow(executor);
    }

    /**
     * Executes the compiled workflow.
     */
    public SwarmOutput kickoff(Map<String, Object> inputs) {
        if (swarm != null) {
            return swarm.kickoff(inputs);
        }
        if (graphExecutor != null) {
            return graphExecutor.execute(inputs);
        }
        tasks.forEach(Task::reset);
        return compositeProcess.execute(tasks, inputs, swarmId);
    }

    /**
     * Returns true if this is a COMPOSITE workflow.
     */
    public boolean isComposite() {
        return compositeProcess != null;
    }

    /**
     * Returns true if this is a graph-based workflow.
     */
    public boolean isGraph() {
        return graphExecutor != null;
    }

    /**
     * Returns the underlying Swarm, or null if this is a COMPOSITE or GRAPH workflow.
     */
    public Swarm getSwarm() { return swarm; }

    /**
     * Returns the number of stages if COMPOSITE, or 1 otherwise.
     */
    public int getStageCount() {
        return compositeProcess != null ? compositeProcess.stageCount() : 1;
    }
}
