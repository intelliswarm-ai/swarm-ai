package ai.intelliswarm.swarmai.distributed.consensus;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Replicated log — the shared source of truth across the RAFT cluster.
 *
 * <p>Every state change (partition assignment, result submission, node failure, goal progress)
 * is appended as a log entry. The leader replicates entries to followers.
 * An entry is committed when a quorum of nodes has acknowledged it.</p>
 */
public class RaftLog {

    private final List<LogEntry> entries = new CopyOnWriteArrayList<>();
    private volatile long commitIndex = -1;
    private volatile long lastApplied = -1;

    public long append(LogEntry entry) {
        entries.add(entry);
        return entries.size() - 1;
    }

    public Optional<LogEntry> get(long index) {
        if (index < 0 || index >= entries.size()) return Optional.empty();
        return Optional.of(entries.get((int) index));
    }

    public long lastIndex() {
        return entries.size() - 1;
    }

    public long lastTerm() {
        if (entries.isEmpty()) return 0;
        return entries.getLast().term();
    }

    public long commitIndex() { return commitIndex; }

    public void setCommitIndex(long index) {
        if (index > commitIndex) {
            commitIndex = index;
        }
    }

    public long lastApplied() { return lastApplied; }
    public void setLastApplied(long index) { lastApplied = index; }

    public List<LogEntry> entriesFrom(long startIndex) {
        if (startIndex < 0 || startIndex >= entries.size()) return List.of();
        return List.copyOf(entries.subList((int) startIndex, entries.size()));
    }

    public void truncateFrom(long index) {
        if (index >= 0 && index < entries.size()) {
            entries.subList((int) index, entries.size()).clear();
        }
    }

    public List<LogEntry> uncommittedEntries() {
        long start = commitIndex + 1;
        if (start >= entries.size()) return List.of();
        return List.copyOf(entries.subList((int) start, entries.size()));
    }

    public int size() { return entries.size(); }

    /**
     * A single entry in the replicated log.
     */
    public record LogEntry(
            long term,
            EntryType type,
            String key,
            Map<String, Object> data,
            Instant timestamp
    ) {
        public LogEntry {
            Objects.requireNonNull(type);
            data = data != null ? Map.copyOf(data) : Map.of();
            timestamp = timestamp != null ? timestamp : Instant.now();
        }

        public static LogEntry of(long term, EntryType type, String key, Map<String, Object> data) {
            return new LogEntry(term, type, key, data, Instant.now());
        }
    }

    public enum EntryType {
        /** Partition assigned to a node. */
        PARTITION_ASSIGNED,
        /** Partition execution completed. */
        PARTITION_COMPLETED,
        /** Partition execution failed. */
        PARTITION_FAILED,
        /** Node joined the cluster. */
        NODE_JOINED,
        /** Node declared dead by failure detector. */
        NODE_FAILED,
        /** Work rebalanced after node failure. */
        WORK_REBALANCED,
        /** Goal status updated. */
        GOAL_UPDATED,
        /** Success criterion evaluation result. */
        CRITERION_EVALUATED,
        /** Improvement observation from self-improving pipeline. */
        IMPROVEMENT_OBSERVED,
        /** Effective skill discovered — shared across the cluster for all nodes to use. */
        SKILL_SHARED,
        /** Skill effectiveness report from a node — contributes to cross-validation. */
        SKILL_FEEDBACK,
        /** Generic improvement rule — cross-validated pattern ready for cluster-wide application. */
        IMPROVEMENT_RULE_PROPOSED,
        /** Improvement rule accepted by quorum — applied cluster-wide. */
        IMPROVEMENT_RULE_ACCEPTED,
        /** Convergence insight — optimal parameters discovered by a node. */
        CONVERGENCE_INSIGHT,
        /** Cluster configuration change. */
        CONFIG_CHANGE,
        /** Checkpoint created. */
        CHECKPOINT
    }
}
