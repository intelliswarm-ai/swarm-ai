package ai.intelliswarm.swarmai.state.reminder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Append-only channel for harness-authored {@link SystemReminder}s, drained on
 * read. Producers post reminders; the prompt-assembly path drains them right
 * before the next LLM call and renders them via {@link SystemReminderRenderer}.
 *
 * <h2>Lifecycle</h2>
 * Default: each reminder fires exactly once. {@code post} appends; {@code drain}
 * returns and clears. {@link #postOnce} additionally guarantees that the same
 * (source, message) pair is never posted again for the lifetime of the channel,
 * even across many drains — useful for "warn the agent the first time the drift
 * budget exceeds N" kinds of nudges.
 *
 * <h2>Threading</h2>
 * Posts and drains are mutually exclusive (synchronized on the same intrinsic
 * lock). Listeners are stored in a {@link CopyOnWriteArrayList} and fire
 * <em>outside</em> the lock so listener callbacks cannot deadlock against
 * post/drain.
 *
 * <h2>Composition with other channels</h2>
 * SystemReminderChannel is intentionally separate from {@code PlanChannelAccessor}.
 * Plan entries are <em>state</em> the harness reasons about; reminders are
 * <em>messages</em> the harness sends to the agent. A single workflow may have
 * one of each (or several of each — though one of each is the common case).
 */
public final class SystemReminderChannel {

    private final Deque<SystemReminder> pending = new ArrayDeque<>();
    private final Set<String> oncePosted = new HashSet<>();
    private final List<Consumer<SystemReminder>> listeners = new CopyOnWriteArrayList<>();

    /** Post a fully-formed reminder. */
    public void post(SystemReminder reminder) {
        Objects.requireNonNull(reminder, "reminder");
        synchronized (this) {
            pending.addLast(reminder);
        }
        notifyListeners(reminder);
    }

    /** Convenience: post an INFO reminder. */
    public SystemReminder post(String source, String message) {
        SystemReminder r = SystemReminder.info(source, message);
        post(r);
        return r;
    }

    /** Convenience: post a WARN reminder. */
    public SystemReminder postWarn(String source, String message) {
        SystemReminder r = SystemReminder.warn(source, message);
        post(r);
        return r;
    }

    /**
     * Post a reminder only if no reminder with the same {@code (source, message)}
     * pair has been posted on this channel before. Returns the posted reminder,
     * or {@code null} if it was deduplicated.
     */
    public SystemReminder postOnce(String source, String message, SystemReminder.Severity severity) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(message, "message");
        SystemReminder.Severity sev = severity != null ? severity : SystemReminder.Severity.INFO;
        String key = source + "\0" + message;
        SystemReminder reminder;
        synchronized (this) {
            if (!oncePosted.add(key)) return null;
            reminder = (sev == SystemReminder.Severity.WARN)
                    ? SystemReminder.warn(source, message)
                    : SystemReminder.info(source, message);
            pending.addLast(reminder);
        }
        notifyListeners(reminder);
        return reminder;
    }

    /**
     * Atomically take and clear all pending reminders, in posted order.
     * Returns an empty list if nothing is pending.
     */
    public List<SystemReminder> drain() {
        synchronized (this) {
            if (pending.isEmpty()) return List.of();
            List<SystemReminder> out = new ArrayList<>(pending);
            pending.clear();
            return Collections.unmodifiableList(out);
        }
    }

    /** Snapshot the pending reminders without consuming them. */
    public List<SystemReminder> peek() {
        synchronized (this) {
            if (pending.isEmpty()) return List.of();
            return Collections.unmodifiableList(new ArrayList<>(pending));
        }
    }

    /** Number of reminders currently waiting to be drained. */
    public int pendingCount() {
        synchronized (this) {
            return pending.size();
        }
    }

    /**
     * Subscribe to reminder posts. Listener fires once per successful post,
     * <em>not</em> on drain. Use this to mirror reminders to logs/UIs while
     * the agent's prompt-assembly path consumes them via {@link #drain}.
     */
    public void addListener(Consumer<SystemReminder> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeListener(Consumer<SystemReminder> listener) {
        listeners.remove(listener);
    }

    private void notifyListeners(SystemReminder reminder) {
        for (Consumer<SystemReminder> l : listeners) {
            try {
                l.accept(reminder);
            } catch (RuntimeException ignored) {
                // listeners must not break post()
            }
        }
    }
}
