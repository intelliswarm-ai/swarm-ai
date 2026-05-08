package ai.intelliswarm.swarmai.agent.background;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Tracks every {@link BackgroundTask} spawned for a single conversation. Hands
 * each task to its {@link BackgroundTaskRunner}, exposes lookup/list APIs to
 * the agent's {@code background_*} tools, and notifies listeners when a task
 * reaches a terminal state.
 *
 * <h2>Threading</h2>
 * Synchronized on the internal map for mutating methods; {@link #all()},
 * {@link #active()}, and {@link #completed()} return immutable snapshots so
 * concurrent reads from agent tool callbacks don't observe partial state.
 *
 * <h2>Notification</h2>
 * Listeners registered via {@link #addCompletionListener(Consumer)} fire once
 * per task when it transitions to a terminal status. Useful for harness
 * hooks — e.g. emitting a {@code <task-notification>} system reminder when
 * a long-running background task completes.
 */
public final class BackgroundTaskRegistry {

    private final Map<String, BackgroundTask> tasks = Collections.synchronizedMap(new LinkedHashMap<>());
    private final List<Consumer<BackgroundTask>> listeners = new CopyOnWriteArrayList<>();
    private final BackgroundTaskRunner runner;

    public BackgroundTaskRegistry(BackgroundTaskRunner runner) {
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    /**
     * Spawn a new task running {@code prompt}. Returns the task with status
     * PENDING; the runner will pick it up asynchronously.
     */
    public BackgroundTask spawn(String prompt) {
        Objects.requireNonNull(prompt, "prompt");
        if (prompt.isBlank()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }
        String id = "bg_" + UUID.randomUUID().toString().substring(0, 8);
        BackgroundTask task = new BackgroundTask(id, prompt);
        synchronized (tasks) {
            tasks.put(id, task);
        }
        // Wrap the runner so we can notify listeners on completion regardless
        // of which terminal state the runner produces.
        runner.runAsync(task);
        watchForCompletion(task);
        return task;
    }

    /** Look up a task by id. */
    public Optional<BackgroundTask> get(String id) {
        if (id == null) return Optional.empty();
        synchronized (tasks) {
            return Optional.ofNullable(tasks.get(id));
        }
    }

    /** All tasks ever spawned, in spawn order. */
    public List<BackgroundTask> all() {
        synchronized (tasks) {
            return List.copyOf(tasks.values());
        }
    }

    /** Tasks not yet at a terminal status (PENDING or RUNNING). */
    public List<BackgroundTask> active() {
        List<BackgroundTask> out = new ArrayList<>();
        synchronized (tasks) {
            for (BackgroundTask t : tasks.values()) {
                if (!t.isTerminal()) out.add(t);
            }
        }
        return List.copyOf(out);
    }

    /** Tasks that have reached a terminal status. */
    public List<BackgroundTask> completed() {
        List<BackgroundTask> out = new ArrayList<>();
        synchronized (tasks) {
            for (BackgroundTask t : tasks.values()) {
                if (t.isTerminal()) out.add(t);
            }
        }
        return List.copyOf(out);
    }

    /** Total tasks tracked. */
    public int size() {
        synchronized (tasks) {
            return tasks.size();
        }
    }

    /**
     * Subscribe to completion notifications. Listener fires exactly once per
     * task when it transitions to a terminal status. Throws from a listener
     * are swallowed so they cannot break other listeners.
     */
    public void addCompletionListener(Consumer<BackgroundTask> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeCompletionListener(Consumer<BackgroundTask> listener) {
        listeners.remove(listener);
    }

    private void watchForCompletion(BackgroundTask task) {
        // Use a daemon thread that polls the task's status every 50ms until
        // terminal. Lighter than wiring a CompletableFuture chain into every
        // runner implementation; keeps the SPI minimal.
        Thread watcher = new Thread(() -> {
            try {
                while (!task.isTerminal()) {
                    Thread.sleep(50);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            for (Consumer<BackgroundTask> l : listeners) {
                try {
                    l.accept(task);
                } catch (RuntimeException ignored) {
                    // listeners must not break the watcher
                }
            }
        }, "bg-watcher-" + task.id());
        watcher.setDaemon(true);
        watcher.start();
    }
}
