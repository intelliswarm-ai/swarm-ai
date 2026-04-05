package ai.intelliswarm.swarmai.distributed.goal;

import java.time.Instant;
import java.util.*;

/**
 * Declarative goal definition — desired state for a distributed agent swarm.
 *
 * <p>Instead of specifying tasks to execute, you declare the outcome you want.
 * The distributed swarm partitions work, elects a leader via RAFT consensus,
 * and reconciles actual state toward the desired state until success criteria are met.</p>
 *
 * <pre>{@code
 * SwarmGoal goal = SwarmGoal.builder()
 *     .name("security-audit")
 *     .objective("Audit 10,000 repositories for critical vulnerabilities")
 *     .successCriterion("All repositories scanned")
 *     .successCriterion("Critical vulnerabilities patched")
 *     .successCriterion("Coverage >= 95%")
 *     .deadline(Instant.parse("2026-04-30T00:00:00Z"))
 *     .partitioning(PartitionStrategy.ADAPTIVE)
 *     .replicas(5)
 *     .build();
 * }</pre>
 */
public record SwarmGoal(
        String name,
        String objective,
        List<String> successCriteria,
        Instant deadline,
        PartitionStrategy partitioning,
        int replicas,
        int replicationFactor,
        Map<String, Object> parameters
) {

    public SwarmGoal {
        Objects.requireNonNull(name, "Goal name is required");
        Objects.requireNonNull(objective, "Objective is required");
        successCriteria = List.copyOf(successCriteria);
        parameters = Map.copyOf(parameters);
        if (replicas < 1) throw new IllegalArgumentException("Replicas must be >= 1");
        if (replicationFactor < 1) throw new IllegalArgumentException("Replication factor must be >= 1");
    }

    public boolean hasDeadline() {
        return deadline != null;
    }

    public boolean isExpired() {
        return deadline != null && Instant.now().isAfter(deadline);
    }

    public int quorumSize() {
        return (replicas / 2) + 1;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String name;
        private String objective;
        private final List<String> successCriteria = new ArrayList<>();
        private Instant deadline;
        private PartitionStrategy partitioning = PartitionStrategy.ADAPTIVE;
        private int replicas = 3;
        private int replicationFactor = 3;
        private final Map<String, Object> parameters = new LinkedHashMap<>();

        public Builder name(String name) { this.name = name; return this; }
        public Builder objective(String objective) { this.objective = objective; return this; }
        public Builder successCriterion(String criterion) { this.successCriteria.add(criterion); return this; }
        public Builder successCriteria(List<String> criteria) { this.successCriteria.addAll(criteria); return this; }
        public Builder deadline(Instant deadline) { this.deadline = deadline; return this; }
        public Builder partitioning(PartitionStrategy strategy) { this.partitioning = strategy; return this; }
        public Builder replicas(int replicas) { this.replicas = replicas; return this; }
        public Builder replicationFactor(int factor) { this.replicationFactor = factor; return this; }
        public Builder parameter(String key, Object value) { this.parameters.put(key, value); return this; }
        public Builder parameters(Map<String, Object> params) { this.parameters.putAll(params); return this; }

        public SwarmGoal build() {
            return new SwarmGoal(name, objective, successCriteria, deadline,
                    partitioning, replicas, replicationFactor, parameters);
        }
    }
}
