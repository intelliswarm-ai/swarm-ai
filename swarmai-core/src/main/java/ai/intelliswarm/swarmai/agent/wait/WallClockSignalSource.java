package ai.intelliswarm.swarmai.agent.wait;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Phase 2 of the pause-resume primitive: a wall-clock {@link SignalDispatcher}
 * driver. Lets harness code schedule a signal to fire at a future {@link Instant}
 * (or after a {@link Duration}). Internally backed by a single
 * {@link ScheduledExecutorService} daemon thread.
 *
 * <p>Use cases:
 * <ul>
 *   <li>Scheduled monitoring: wake the agent every 6 hours to check a metric.</li>
 *   <li>Timed alerts: "if no other signal arrives by 3pm, fire {@code budget.exceeded}."</li>
 *   <li>Test fixtures: deterministically wake an agent under test after N ms.</li>
 *   <li>Pipeline sentinels: fire a "deadline reached" signal at a known time.</li>
 * </ul>
 *
 * <p>Past instants fire immediately. Cancellation is best-effort — if the
 * scheduler thread is already executing the fire, cancellation returns false.
 *
 * <p>This source <em>only delivers signals</em>; it doesn't register waits.
 * The typical pattern: the agent registers a wait via the
 * {@code wait_for_signal} tool; some harness component schedules a wall-clock
 * fire via this source; when the time arrives, {@link SignalDispatcher#deliver}
 * is invoked from the scheduler thread and the agent's tool callback unblocks.
 */
public final class WallClockSignalSource {

    private final SignalDispatcher dispatcher;
    private final ScheduledExecutorService scheduler;
    private final Map<String, Scheduled> active = new ConcurrentHashMap<>();
    private volatile boolean closed = false;

    public WallClockSignalSource(SignalDispatcher dispatcher) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wall-clock-signal-source");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Schedule a signal to fire at the specified wall-clock instant.
     * Past instants fire immediately on the scheduler thread.
     *
     * @param at      when to fire
     * @param key     {@link Signal#key} the signal will carry
     * @param payload {@link Signal#payload} the signal will carry
     * @return immutable scheduled-fire record (use its {@link ScheduledFire#id} to cancel)
     */
    public ScheduledFire scheduleAt(Instant at, String key, String payload) {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(payload, "payload");
        if (closed) throw new IllegalStateException("WallClockSignalSource is closed");
        if (key.isBlank()) throw new IllegalArgumentException("key must not be blank");

        long delayMs = Math.max(0, java.time.Duration.between(Instant.now(), at).toMillis());
        String fireId = "fire_" + UUID.randomUUID().toString().substring(0, 8);
        ScheduledFire snapshot = new ScheduledFire(fireId, key, payload, at);

        ScheduledFuture<?> handle = scheduler.schedule(() -> fire(fireId), delayMs, TimeUnit.MILLISECONDS);
        active.put(fireId, new Scheduled(snapshot, handle));
        return snapshot;
    }

    /** Convenience: fire after the given duration from now. */
    public ScheduledFire scheduleAfter(Duration delay, String key, String payload) {
        Objects.requireNonNull(delay, "delay");
        return scheduleAt(Instant.now().plus(delay), key, payload);
    }

    /**
     * Cancel a previously-scheduled fire. Returns true if the fire was
     * cancelled before it ran; false if the scheduler had already started
     * executing it (or the id is unknown).
     */
    public boolean cancel(String fireId) {
        if (fireId == null) return false;
        Scheduled s = active.remove(fireId);
        if (s == null) return false;
        return s.handle.cancel(false);
    }

    /** Snapshot of every fire still in the active set (not yet executed or cancelled). */
    public List<ScheduledFire> activeFires() {
        List<ScheduledFire> out = new ArrayList<>(active.size());
        for (Scheduled s : active.values()) {
            out.add(s.snapshot);
        }
        out.sort((a, b) -> a.firesAt().compareTo(b.firesAt()));
        return List.copyOf(out);
    }

    /** Number of fires currently armed (excludes already-executed and cancelled). */
    public int activeFireCount() {
        return active.size();
    }

    /** Shutdown the scheduler thread. Idempotent. */
    public void close() {
        closed = true;
        scheduler.shutdownNow();
        active.clear();
    }

    private void fire(String fireId) {
        Scheduled s = active.remove(fireId);
        if (s == null) return; // cancelled between schedule and execution
        try {
            dispatcher.deliver(s.snapshot.signalKey(), s.snapshot.payload());
        } catch (RuntimeException ignored) {
            // swallow — the dispatcher already handles unmatched signals via its drop counter
        }
    }

    /** Public-facing record describing one scheduled fire. */
    public record ScheduledFire(String id, String signalKey, String payload, Instant firesAt) {
        public ScheduledFire {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(signalKey, "signalKey");
            Objects.requireNonNull(payload, "payload");
            Objects.requireNonNull(firesAt, "firesAt");
        }
    }

    /** Internal pairing of snapshot + executor handle. */
    private record Scheduled(ScheduledFire snapshot, ScheduledFuture<?> handle) {}
}
