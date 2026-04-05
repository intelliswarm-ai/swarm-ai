package ai.intelliswarm.swarmai.dsl.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * YAML definition for work partitioning strategy.
 *
 * <pre>{@code
 * partitioning:
 *   strategy: ADAPTIVE
 *   maxPartitionsPerNode: 10
 *   rebalanceOnFailure: true
 * }</pre>
 */
public class PartitioningDefinition {

    private String strategy = "ADAPTIVE";

    @JsonProperty("maxPartitionsPerNode")
    private int maxPartitionsPerNode = 10;

    @JsonProperty("rebalanceOnFailure")
    private boolean rebalanceOnFailure = true;

    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }

    public int getMaxPartitionsPerNode() { return maxPartitionsPerNode; }
    public void setMaxPartitionsPerNode(int maxPartitionsPerNode) { this.maxPartitionsPerNode = maxPartitionsPerNode; }

    public boolean isRebalanceOnFailure() { return rebalanceOnFailure; }
    public void setRebalanceOnFailure(boolean rebalanceOnFailure) { this.rebalanceOnFailure = rebalanceOnFailure; }
}
