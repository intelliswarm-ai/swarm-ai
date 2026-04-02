package ai.intelliswarm.swarmai.dsl.model;

/**
 * Root wrapper for the YAML document.
 * The YAML file starts with a top-level {@code swarm:} key.
 *
 * <pre>{@code
 * swarm:
 *   name: "My Workflow"
 *   process: SEQUENTIAL
 *   ...
 * }</pre>
 */
public class SwarmYamlRoot {

    private SwarmDefinition swarm;

    public SwarmDefinition getSwarm() { return swarm; }
    public void setSwarm(SwarmDefinition swarm) { this.swarm = swarm; }
}
