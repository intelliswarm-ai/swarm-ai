package ai.intelliswarm.swarmai.dsl.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * YAML definition for distributed cluster configuration.
 *
 * <pre>{@code
 * cluster:
 *   replicas: 5
 *   consensus: RAFT
 *   heartbeatMs: 150
 *   electionTimeoutMs: 300
 *   replicationFactor: 3
 *   discovery: static
 * }</pre>
 */
public class ClusterDefinition {

    private int replicas = 3;
    private String consensus = "RAFT";

    @JsonProperty("heartbeatMs")
    private int heartbeatMs = 150;

    @JsonProperty("electionTimeoutMs")
    private int electionTimeoutMs = 300;

    @JsonProperty("replicationFactor")
    private int replicationFactor = 3;

    private String discovery = "static";

    public int getReplicas() { return replicas; }
    public void setReplicas(int replicas) { this.replicas = replicas; }

    public String getConsensus() { return consensus; }
    public void setConsensus(String consensus) { this.consensus = consensus; }

    public int getHeartbeatMs() { return heartbeatMs; }
    public void setHeartbeatMs(int heartbeatMs) { this.heartbeatMs = heartbeatMs; }

    public int getElectionTimeoutMs() { return electionTimeoutMs; }
    public void setElectionTimeoutMs(int electionTimeoutMs) { this.electionTimeoutMs = electionTimeoutMs; }

    public int getReplicationFactor() { return replicationFactor; }
    public void setReplicationFactor(int replicationFactor) { this.replicationFactor = replicationFactor; }

    public String getDiscovery() { return discovery; }
    public void setDiscovery(String discovery) { this.discovery = discovery; }
}
