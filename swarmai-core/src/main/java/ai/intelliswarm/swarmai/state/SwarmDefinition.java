package ai.intelliswarm.swarmai.state;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.process.ProcessType;

import java.util.List;

/**
 * Sealed interface enforcing the two-phase swarm lifecycle:
 * <ol>
 *   <li>{@link SwarmGraph} — mutable construction phase (add agents, tasks, configure process)</li>
 *   <li>{@link CompiledSwarm} — immutable execution phase (validated, frozen, ready to execute)</li>
 * </ol>
 *
 * <p>The sealed hierarchy prevents misuse: you cannot execute a graph that hasn't been compiled,
 * and you cannot modify a graph that has been compiled.
 *
 * <p>Usage:
 * <pre>{@code
 * CompiledSwarm swarm = SwarmGraph.create()
 *     .addAgent(researcher)
 *     .addAgent(writer)
 *     .addTask(researchTask)
 *     .addTask(writeTask)
 *     .process(ProcessType.SEQUENTIAL)
 *     .compile()
 *     .compiled();  // throws if validation fails
 *
 * SwarmOutput output = swarm.kickoff(AgentState.of(Map.of("topic", "AI")));
 * }</pre>
 */
public sealed interface SwarmDefinition permits SwarmGraph, CompiledSwarm {

    /**
     * Returns the agents registered in this definition.
     */
    List<Agent> agents();

    /**
     * Returns the tasks registered in this definition.
     */
    List<Task> tasks();

    /**
     * Returns the process type for orchestration.
     */
    ProcessType processType();
}
