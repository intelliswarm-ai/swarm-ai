package ai.intelliswarm.swarmai.state;

/**
 * Generates visual workflow diagrams from a {@link CompiledSwarm}.
 * Implementations produce diagrams in different formats for documentation,
 * debugging, and the Studio web dashboard.
 */
public interface DiagramGenerator {

    /**
     * Generates a diagram representation of the compiled swarm workflow.
     *
     * @param swarm the compiled swarm to visualize
     * @return the diagram as a string in the implementation's format
     */
    String generate(CompiledSwarm swarm);
}
