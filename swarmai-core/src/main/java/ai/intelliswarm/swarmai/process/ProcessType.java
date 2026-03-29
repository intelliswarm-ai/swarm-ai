package ai.intelliswarm.swarmai.process;

public enum ProcessType {
    SEQUENTIAL,
    HIERARCHICAL,
    PARALLEL,
    ITERATIVE,
    SELF_IMPROVING,
    SWARM;           // Distributed fan-out: discovery -> parallel self-improving agents per target

    /**
     * Returns true if this process type executes tasks asynchronously.
     */
    public boolean isAsync() {
        return this == PARALLEL || this == SWARM;
    }
}