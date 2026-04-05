package ai.intelliswarm.swarmai.distributed.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the SwarmAI distributed execution module.
 *
 * <pre>{@code
 * swarmai:
 *   distributed:
 *     enabled: true
 *     heartbeat-interval-ms: 150
 *     election-timeout-min-ms: 300
 *     election-timeout-max-ms: 500
 *     suspect-threshold-ms: 5000
 *     dead-threshold-ms: 15000
 *     max-parallel-partitions: 10
 *     max-partition-retries: 3
 *     reconciliation-interval-ms: 100
 * }</pre>
 */
@ConfigurationProperties(prefix = "swarmai.distributed")
public class DistributedConfig {

    private boolean enabled = false;

    // RAFT consensus timing
    private int heartbeatIntervalMs = 150;
    private int electionTimeoutMinMs = 300;
    private int electionTimeoutMaxMs = 500;

    // Failure detection
    private long suspectThresholdMs = 5000;
    private long deadThresholdMs = 15000;

    // Execution
    private int maxParallelPartitions = Runtime.getRuntime().availableProcessors();
    private int maxPartitionRetries = 3;
    private long reconciliationIntervalMs = 100;

    // Cluster discovery
    private String discoveryMode = "static"; // static, multicast, dns

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getHeartbeatIntervalMs() { return heartbeatIntervalMs; }
    public void setHeartbeatIntervalMs(int heartbeatIntervalMs) { this.heartbeatIntervalMs = heartbeatIntervalMs; }

    public int getElectionTimeoutMinMs() { return electionTimeoutMinMs; }
    public void setElectionTimeoutMinMs(int electionTimeoutMinMs) { this.electionTimeoutMinMs = electionTimeoutMinMs; }

    public int getElectionTimeoutMaxMs() { return electionTimeoutMaxMs; }
    public void setElectionTimeoutMaxMs(int electionTimeoutMaxMs) { this.electionTimeoutMaxMs = electionTimeoutMaxMs; }

    public long getSuspectThresholdMs() { return suspectThresholdMs; }
    public void setSuspectThresholdMs(long suspectThresholdMs) { this.suspectThresholdMs = suspectThresholdMs; }

    public long getDeadThresholdMs() { return deadThresholdMs; }
    public void setDeadThresholdMs(long deadThresholdMs) { this.deadThresholdMs = deadThresholdMs; }

    public int getMaxParallelPartitions() { return maxParallelPartitions; }
    public void setMaxParallelPartitions(int maxParallelPartitions) { this.maxParallelPartitions = maxParallelPartitions; }

    public int getMaxPartitionRetries() { return maxPartitionRetries; }
    public void setMaxPartitionRetries(int maxPartitionRetries) { this.maxPartitionRetries = maxPartitionRetries; }

    public long getReconciliationIntervalMs() { return reconciliationIntervalMs; }
    public void setReconciliationIntervalMs(long reconciliationIntervalMs) { this.reconciliationIntervalMs = reconciliationIntervalMs; }

    public String getDiscoveryMode() { return discoveryMode; }
    public void setDiscoveryMode(String discoveryMode) { this.discoveryMode = discoveryMode; }
}
