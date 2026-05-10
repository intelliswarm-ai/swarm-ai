package ai.intelliswarm.swarmai.skill.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * In-process collection of {@link Finding}s shared across audit phases.
 *
 * <p>Each phase writes its observations here; downstream phases query the
 * blackboard rather than re-running discovery. This is the architectural
 * pattern that eliminates the "loop a per-host tool over 256 unreachable
 * hosts" wasted work — Phase E queries the blackboard for hosts with
 * a specific service open, runs its tool only against those.
 *
 * <p>Thread-safe via a read-write lock so phases that publish on
 * background workers don't corrupt iteration.
 *
 * <p>Inspired by Pentest-Swarm-AI's PG+pgvector blackboard but scoped to
 * a single audit run; for cross-run persistence the framework already
 * has {@link ContainerAuditPersistence} (audit log) and the
 * {@code SkillRegistry} (skill catalog).
 */
public final class FindingsBlackboard {

    private final List<Finding> findings = new ArrayList<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /** Append a finding. Thread-safe. */
    public void record(Finding finding) {
        if (finding == null) return;
        lock.writeLock().lock();
        try {
            findings.add(finding);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Append several findings at once — slightly cheaper than N record() calls. */
    public void recordAll(List<Finding> batch) {
        if (batch == null || batch.isEmpty()) return;
        lock.writeLock().lock();
        try {
            findings.addAll(batch);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Snapshot of every finding, oldest first. */
    public List<Finding> all() {
        lock.readLock().lock();
        try {
            return List.copyOf(findings);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** All findings of a given type. */
    public List<Finding> byType(String type) {
        if (type == null) return List.of();
        lock.readLock().lock();
        try {
            return findings.stream()
                    .filter(f -> type.equals(f.type()))
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** All findings for a given host (any type, any port). */
    public List<Finding> byHost(String host) {
        if (host == null) return List.of();
        lock.readLock().lock();
        try {
            return findings.stream()
                    .filter(f -> host.equals(f.host()))
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Hosts that have at least one PORT_OPEN finding for the given service
     * (e.g. {@code "http"} → hosts with web servers; {@code "ssh"} → hosts
     * with SSH). The signature Phase E uses to scope its work.
     */
    public Set<String> hostsWithService(String service) {
        if (service == null) return Set.of();
        lock.readLock().lock();
        try {
            return findings.stream()
                    .filter(f -> "PORT_OPEN".equals(f.type()))
                    .filter(f -> service.equals(f.service()))
                    .map(Finding::host)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Sorted set of unique hosts that appear in any finding. */
    public Set<String> hosts() {
        lock.readLock().lock();
        try {
            return findings.stream()
                    .map(Finding::host)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Number of findings recorded so far. */
    public int size() {
        lock.readLock().lock();
        try {
            return findings.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** True when nothing has been recorded yet. */
    public boolean isEmpty() {
        return size() == 0;
    }

    /**
     * Group findings by type → list, preserving insertion order. Useful
     * for the report phase which renders one section per type.
     */
    public Map<String, List<Finding>> groupedByType() {
        lock.readLock().lock();
        try {
            Map<String, List<Finding>> out = new LinkedHashMap<>();
            for (Finding f : findings) {
                out.computeIfAbsent(f.type(), k -> new ArrayList<>()).add(f);
            }
            return Collections.unmodifiableMap(out);
        } finally {
            lock.readLock().unlock();
        }
    }
}
