package ai.intelliswarm.swarmai.distributed.fault;

import ai.intelliswarm.swarmai.distributed.cluster.ClusterNode;
import ai.intelliswarm.swarmai.distributed.cluster.ClusterTopology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Heartbeat-based failure detector for cluster nodes.
 *
 * <p>Runs periodic checks against the cluster topology. Nodes that miss heartbeats
 * transition through SUSPECT → DEAD states. On node death, notifies registered
 * listeners so the GoalReconciler can reassign orphaned partitions.</p>
 *
 * <p>Implements the phi accrual failure detection pattern with configurable thresholds.</p>
 */
public class FailureDetector {

    private static final Logger log = LoggerFactory.getLogger(FailureDetector.class);

    private final ClusterTopology topology;
    private final long suspectThresholdMs;
    private final long deadThresholdMs;
    private final long checkIntervalMs;
    private final ScheduledExecutorService scheduler;
    private volatile ScheduledFuture<?> checkTask;
    private volatile Consumer<List<ClusterNode>> onNodesDead;

    public FailureDetector(ClusterTopology topology,
                           long suspectThresholdMs,
                           long deadThresholdMs,
                           long checkIntervalMs) {
        this.topology = topology;
        this.suspectThresholdMs = suspectThresholdMs;
        this.deadThresholdMs = deadThresholdMs;
        this.checkIntervalMs = checkIntervalMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "failure-detector");
            t.setDaemon(true);
            return t;
        });
    }

    public FailureDetector(ClusterTopology topology) {
        this(topology, 5000, 15000, 2000);
    }

    public void onNodesDead(Consumer<List<ClusterNode>> listener) {
        this.onNodesDead = listener;
    }

    public void start() {
        checkTask = scheduler.scheduleAtFixedRate(this::check, checkIntervalMs, checkIntervalMs, TimeUnit.MILLISECONDS);
        log.info("Failure detector started (suspect={}ms, dead={}ms, interval={}ms)",
                suspectThresholdMs, deadThresholdMs, checkIntervalMs);
    }

    public void stop() {
        if (checkTask != null) checkTask.cancel(false);
        scheduler.shutdown();
    }

    private void check() {
        try {
            topology.detectSuspectNodes(suspectThresholdMs);
            List<ClusterNode> dead = topology.promoteToDeadNodes(deadThresholdMs);

            if (!dead.isEmpty() && onNodesDead != null) {
                onNodesDead.accept(dead);
            }
        } catch (Exception e) {
            log.error("Failure detection check failed: {}", e.getMessage());
        }
    }
}
