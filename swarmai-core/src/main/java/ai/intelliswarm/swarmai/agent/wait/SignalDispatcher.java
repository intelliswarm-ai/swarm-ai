package ai.intelliswarm.swarmai.agent.wait;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Pairs paused agents (registered via {@link #registerWait}) with incoming
 * {@link Signal}s (delivered via {@link #deliver}). The first half of the
 * pause-resume / WaitForSignal primitive — built on top of the existing
 * {@code CheckpointSaver} infrastructure but operating in-memory for the
 * MVP. Cross-JVM resume layers on top later.
 *
 * <h2>Matching</h2>
 * Waits and signals are matched by {@code signalKey} in <b>FIFO</b> order: when a
 * signal with key {@code K} arrives, the oldest pending wait for {@code K}
 * receives it. Signals with no matching pending wait are dropped silently
 * (fire-and-forget — the dispatcher does not buffer signals for waits that
 * haven't registered yet). Listeners can be added via {@link #addUnmatchedSignalListener}
 * to observe these.
 *
 * <h2>Threading</h2>
 * All public methods are safe to call from any thread. {@link #awaitSignal}
 * blocks on a {@link CompletableFuture}, releasing the calling thread while
 * the wait is pending. {@link #deliver}, {@link #cancel}, and
 * {@link #expire} complete the future from the signal-source thread.
 *
 * <h2>Mode A vs Mode B</h2>
 * This MVP implements <b>Mode A</b> (synchronous block-on-wait): the agent's
 * tool-callback thread is held while the wait is pending. Suitable for waits
 * up to minutes/hours within a single JVM session.
 *
 * <p><b>Mode B</b> (cross-session checkpoint-and-release) layers on top:
 * a future {@code CheckpointingSignalDispatcher} would, instead of returning
 * a blocking {@code CompletableFuture}, persist the wait state via
 * {@code CheckpointSaver} and signal the agent loop to release. On signal
 * arrival, the harness loads the checkpoint and re-enters the loop with the
 * signal payload as input. The Wait/Signal/Status records here are designed
 * to round-trip through that flow.
 */
public final class SignalDispatcher {

    /** Internal mutable wait state — never exposed directly. */
    private static final class WaitEntry {
        final String id;
        final String signalKey;
        final String reason;
        final Instant pausedAt;
        final Instant expiresAt;          // nullable
        final CompletableFuture<Signal> future = new CompletableFuture<>();
        volatile WaitStatus status = WaitStatus.PENDING;
        volatile Signal received;          // non-null on COMPLETED
        volatile Instant completedAt;      // non-null on terminal status

        WaitEntry(String id, String signalKey, String reason,
                  Instant pausedAt, Instant expiresAt) {
            this.id = id;
            this.signalKey = signalKey;
            this.reason = reason;
            this.pausedAt = pausedAt;
            this.expiresAt = expiresAt;
        }

        WaitForSignalRequest snapshot() {
            return new WaitForSignalRequest(id, signalKey, reason, pausedAt,
                    expiresAt, status, completedAt, received);
        }
    }

    private final Map<String, WaitEntry> byId = new ConcurrentHashMap<>();
    /** Per-key FIFO queue of pending entries; mutations synchronized on the queue itself. */
    private final Map<String, Deque<WaitEntry>> bySignalKey = new ConcurrentHashMap<>();
    private final List<Consumer<Signal>> unmatchedListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<Consumer<WaitForSignalRequest>> completionListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final AtomicInteger droppedSignals = new AtomicInteger();
    /** Lazily initialised — only spun up when a wait registers with a non-null TTL. */
    private volatile ScheduledExecutorService expiryScheduler;
    /** Per-wait expiry handle, used so cancel/deliver can preempt the timer. */
    private final Map<String, ScheduledFuture<?>> expiryHandles = new ConcurrentHashMap<>();

    private synchronized ScheduledExecutorService expiryScheduler() {
        ScheduledExecutorService s = expiryScheduler;
        if (s == null) {
            s = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "signal-dispatcher-expiry");
                t.setDaemon(true);
                return t;
            });
            expiryScheduler = s;
        }
        return s;
    }

    /**
     * Register a new wait. The agent's tool callback typically follows this
     * with {@link #awaitSignal(String, Duration)} to block until the wait
     * resolves.
     *
     * @param signalKey identifier this wait will match against
     * @param reason    human-readable description for diagnostics
     * @param ttl       optional TTL; null means the wait has no timeout
     * @return the immutable snapshot record (use {@link WaitForSignalRequest#id} for follow-up calls)
     */
    public WaitForSignalRequest registerWait(String signalKey, String reason, Duration ttl) {
        Objects.requireNonNull(signalKey, "signalKey");
        Objects.requireNonNull(reason, "reason");
        if (signalKey.isBlank()) throw new IllegalArgumentException("signalKey must not be blank");
        if (reason.isBlank()) throw new IllegalArgumentException("reason must not be blank");

        String id = "wait_" + UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now();
        Instant expiresAt = (ttl == null) ? null : now.plus(ttl);
        WaitEntry entry = new WaitEntry(id, signalKey, reason, now, expiresAt);

        byId.put(id, entry);
        Deque<WaitEntry> queue = bySignalKey.computeIfAbsent(signalKey, k -> new ArrayDeque<>());
        synchronized (queue) {
            queue.addLast(entry);
        }

        // Auto-schedule expiry if a TTL was supplied so the wait fires reliably
        // without callers needing to invoke sweepExpired().
        if (ttl != null) {
            ScheduledFuture<?> handle = expiryScheduler().schedule(
                    () -> expireOne(id),
                    ttl.toMillis(), TimeUnit.MILLISECONDS);
            expiryHandles.put(id, handle);
        }
        return entry.snapshot();
    }

    /** Expire a single wait if it's still PENDING. Called by the scheduled task. */
    private void expireOne(String waitId) {
        WaitEntry entry = byId.get(waitId);
        if (entry == null) return;
        synchronized (entry) {
            if (entry.status != WaitStatus.PENDING) return;
            entry.status = WaitStatus.EXPIRED;
            entry.completedAt = Instant.now();
            entry.future.completeExceptionally(
                    new WaitForSignalException.Timeout(waitId,
                            "wait expired after TTL " + entry.expiresAt));
        }
    }

    /**
     * Block until the wait completes, fails, or expires. Releases the calling
     * thread while pending; resumed when the future is completed by
     * {@link #deliver} / {@link #cancel} / {@link #expire}.
     *
     * @param waitId     the id returned by {@link #registerWait}
     * @param maxBlockFor cap on how long this CALL blocks; <em>independent</em>
     *                    of the wait's TTL. Use a generous value (hours) when
     *                    the wait itself has a shorter TTL — TTL expiry will
     *                    complete the future early. Use a short value to bail
     *                    quickly even if the wait is still pending.
     * @return the signal that completed the wait
     * @throws WaitForSignalException.UnknownWait  if no wait with that id exists
     * @throws WaitForSignalException.Cancelled    if the wait was cancelled
     * @throws WaitForSignalException.Timeout      if the wait's TTL elapsed
     *                                              OR if {@code maxBlockFor}
     *                                              elapsed first
     */
    public Signal awaitSignal(String waitId, Duration maxBlockFor) {
        Objects.requireNonNull(waitId, "waitId");
        Objects.requireNonNull(maxBlockFor, "maxBlockFor");
        WaitEntry entry = byId.get(waitId);
        if (entry == null) {
            throw new WaitForSignalException.UnknownWait(waitId);
        }
        try {
            return entry.future.get(maxBlockFor.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Caller's block deadline elapsed; the wait may still be PENDING.
            throw new WaitForSignalException.Timeout(waitId,
                    "awaitSignal timed out after " + maxBlockFor + " (wait still PENDING)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WaitForSignalException.Cancelled(waitId, "interrupted");
        } catch (ExecutionException e) {
            // The future completed exceptionally — re-throw with the dispatcher's status
            Throwable cause = e.getCause();
            if (cause instanceof WaitForSignalException w) throw w;
            throw new WaitForSignalException(waitId,
                    "wait failed: " + (cause == null ? "(unknown)" : cause.getMessage()));
        }
    }

    /**
     * Deliver a signal to the oldest pending wait matching {@code key}.
     * Returns true if a wait was matched and woken up; false (and the signal
     * is dropped) if no pending wait existed.
     */
    public boolean deliver(String signalKey, String payload) {
        Objects.requireNonNull(signalKey, "signalKey");
        Objects.requireNonNull(payload, "payload");
        Signal signal = Signal.of(signalKey, payload);

        Deque<WaitEntry> queue = bySignalKey.get(signalKey);
        WaitEntry matched = null;
        if (queue != null) {
            synchronized (queue) {
                while (!queue.isEmpty()) {
                    WaitEntry candidate = queue.pollFirst();
                    if (candidate.status == WaitStatus.PENDING) {
                        matched = candidate;
                        break;
                    }
                    // candidate became terminal between iterations — drop it
                }
            }
        }

        if (matched == null) {
            droppedSignals.incrementAndGet();
            for (Consumer<Signal> l : unmatchedListeners) {
                try { l.accept(signal); } catch (RuntimeException ignored) {}
            }
            return false;
        }

        WaitForSignalRequest snapshot;
        synchronized (matched) {
            if (matched.status.isTerminal()) {
                droppedSignals.incrementAndGet();
                for (Consumer<Signal> l : unmatchedListeners) {
                    try { l.accept(signal); } catch (RuntimeException ignored) {}
                }
                return false;
            }
            matched.status = WaitStatus.COMPLETED;
            matched.received = signal;
            matched.completedAt = Instant.now();
            matched.future.complete(signal);
            snapshot = matched.snapshot();
        }

        // Cancel the auto-expiry timer (no-op if it already fired or never scheduled)
        ScheduledFuture<?> handle = expiryHandles.remove(matched.id);
        if (handle != null) handle.cancel(false);
        // Fire completion listeners outside the queue/entry lock; throws are swallowed.
        for (Consumer<WaitForSignalRequest> l : completionListeners) {
            try { l.accept(snapshot); } catch (RuntimeException ignored) {}
        }
        return true;
    }

    /** Cancel a wait. Returns true if the cancellation was effective (was PENDING). */
    public boolean cancel(String waitId) {
        return cancelInternal(waitId, "cancelled by caller");
    }

    private boolean cancelInternal(String waitId, String reason) {
        WaitEntry entry = byId.get(waitId);
        if (entry == null) return false;
        synchronized (entry) {
            if (entry.status.isTerminal()) return false;
            entry.status = WaitStatus.CANCELLED;
            entry.completedAt = Instant.now();
            entry.future.completeExceptionally(
                    new WaitForSignalException.Cancelled(waitId, reason));
        }
        // Preempt the auto-expiry timer if one was scheduled
        ScheduledFuture<?> handle = expiryHandles.remove(waitId);
        if (handle != null) handle.cancel(false);
        return true;
    }

    /**
     * Stop the auto-expiry scheduler thread. Call this when shutting down a
     * harness session so the daemon thread exits cleanly. Idempotent.
     */
    public synchronized void close() {
        if (expiryScheduler != null) {
            expiryScheduler.shutdownNow();
            expiryScheduler = null;
        }
        expiryHandles.clear();
    }

    /**
     * Sweep waits whose TTL has elapsed, marking them EXPIRED and completing
     * their futures with a Timeout exception. Returns the number swept.
     * Typically called by a scheduled task on some cadence (1-second, etc.).
     */
    public int sweepExpired() {
        Instant now = Instant.now();
        int swept = 0;
        for (WaitEntry entry : byId.values()) {
            if (entry.status != WaitStatus.PENDING) continue;
            if (entry.expiresAt != null && now.isAfter(entry.expiresAt)) {
                synchronized (entry) {
                    if (entry.status != WaitStatus.PENDING) continue;
                    entry.status = WaitStatus.EXPIRED;
                    entry.completedAt = now;
                    entry.future.completeExceptionally(
                            new WaitForSignalException.Timeout(entry.id,
                                    "wait expired after TTL " + entry.expiresAt));
                    swept++;
                }
            }
        }
        return swept;
    }

    /** Look up a wait by id. */
    public Optional<WaitForSignalRequest> getWait(String waitId) {
        if (waitId == null) return Optional.empty();
        WaitEntry entry = byId.get(waitId);
        return Optional.ofNullable(entry).map(WaitEntry::snapshot);
    }

    /** Snapshot of every PENDING wait, in registration order. */
    public List<WaitForSignalRequest> pendingWaits() {
        List<WaitForSignalRequest> out = new ArrayList<>();
        for (WaitEntry e : byId.values()) {
            if (e.status == WaitStatus.PENDING) out.add(e.snapshot());
        }
        // Sort by pausedAt for stable iteration
        out.sort((a, b) -> a.pausedAt().compareTo(b.pausedAt()));
        return List.copyOf(out);
    }

    /** Snapshot of every wait ever registered, regardless of status. */
    public List<WaitForSignalRequest> allWaits() {
        List<WaitForSignalRequest> out = new ArrayList<>();
        for (WaitEntry e : byId.values()) out.add(e.snapshot());
        out.sort((a, b) -> a.pausedAt().compareTo(b.pausedAt()));
        return List.copyOf(out);
    }

    /** Number of pending waits. */
    public int pendingCount() {
        int count = 0;
        for (WaitEntry e : byId.values()) {
            if (e.status == WaitStatus.PENDING) count++;
        }
        return count;
    }

    /** Number of signals dropped because no matching wait existed. */
    public int droppedSignals() {
        return droppedSignals.get();
    }

    /**
     * Subscribe to signals that arrived without a matching pending wait.
     * Useful for debugging signal-key drift between producers and consumers.
     */
    public void addUnmatchedSignalListener(Consumer<Signal> listener) {
        unmatchedListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeUnmatchedSignalListener(Consumer<Signal> listener) {
        unmatchedListeners.remove(listener);
    }

    /**
     * Subscribe to wait-completion events. Listener fires once per wait when
     * it transitions to COMPLETED via {@link #deliver}. The
     * {@code ResumeCoordinator} uses this to trigger rehydration on signal
     * arrival. Throwing listeners are swallowed so they cannot block
     * subsequent listeners.
     */
    public void addCompletionListener(Consumer<WaitForSignalRequest> listener) {
        completionListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeCompletionListener(Consumer<WaitForSignalRequest> listener) {
        completionListeners.remove(listener);
    }
}
