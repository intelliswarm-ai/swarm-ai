package ai.intelliswarm.swarmai.process;

public enum ProcessType {
    SEQUENTIAL,
    HIERARCHICAL,
    PARALLEL,
    ITERATIVE,
    SELF_IMPROVING,
    SWARM,           // Distributed fan-out: discovery -> parallel self-improving agents per target
    DISTRIBUTED,     // RAFT consensus: declarative goals, work partitioning, fault-tolerant coordination
    COMPOSITE;       // Chain multiple processes: Sequential → Hierarchical → Iterative

    /**
     * Returns true if this process type executes tasks asynchronously.
     */
    public boolean isAsync() {
        return this == PARALLEL || this == SWARM || this == DISTRIBUTED;
    }
}