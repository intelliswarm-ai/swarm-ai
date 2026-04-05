package ai.intelliswarm.swarmai.distributed.goal;

/**
 * Strategy for partitioning work across cluster nodes.
 */
public enum PartitionStrategy {

    /** Distribute work items by hash of partition key — even distribution, deterministic. */
    HASH,

    /** Distribute by value range — good for ordered data (dates, IDs). */
    RANGE,

    /** Simple round-robin assignment — fair but ignores node capacity. */
    ROUND_ROBIN,

    /** Adaptive assignment based on node load and task complexity — rebalances dynamically. */
    ADAPTIVE;
}
