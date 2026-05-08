package ai.intelliswarm.swarmai.agent.background;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A single background unit of work — spawned by an agent's {@code background_spawn}
 * tool call and tracked by a {@link BackgroundTaskRegistry}. Carries the prompt
 * the foreground agent supplied, the lifecycle status, and (eventually) the
 * output or error from the runner.
 *
 * <p>Mutating methods ({@link #markRunning}, {@link #completeSuccess},
 * {@link #completeFailure}, {@link #markCancelled}) are package-private and
 * called only by the {@link BackgroundTaskRunner}. Agents reading task state
 * see the volatile snapshots through {@link #status}, {@link #output}, etc.
 */
public final class BackgroundTask {

    private final String id;
    private final String prompt;
    private final Instant spawnedAt;
    private final CompletableFuture<String> resultFuture = new CompletableFuture<>();

    // volatile so foreground threads see fresh values without locking
    private volatile BackgroundTaskStatus status = BackgroundTaskStatus.PENDING;
    private volatile String output;
    private volatile String error;
    private volatile Instant completedAt;

    public BackgroundTask(String id, String prompt) {
        this.id = Objects.requireNonNull(id, "id");
        this.prompt = Objects.requireNonNull(prompt, "prompt");
        if (id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        if (prompt.isBlank()) throw new IllegalArgumentException("prompt must not be blank");
        this.spawnedAt = Instant.now();
    }

    public String id() { return id; }
    public String prompt() { return prompt; }
    public Instant spawnedAt() { return spawnedAt; }
    public BackgroundTaskStatus status() { return status; }

    /** The output if {@link BackgroundTaskStatus#COMPLETED}, else empty. */
    public Optional<String> output() {
        return Optional.ofNullable(output);
    }

    /** The error message if {@link BackgroundTaskStatus#FAILED}, else empty. */
    public Optional<String> error() {
        return Optional.ofNullable(error);
    }

    /** When the task reached a terminal status, if it has. */
    public Optional<Instant> completedAt() {
        return Optional.ofNullable(completedAt);
    }

    /**
     * Wall-clock duration from spawn to completion (or now, if still running).
     */
    public Duration duration() {
        Instant end = completedAt != null ? completedAt : Instant.now();
        return Duration.between(spawnedAt, end);
    }

    /** True if the task has reached COMPLETED, FAILED, or CANCELLED. */
    public boolean isTerminal() {
        return status.isTerminal();
    }

    /**
     * Block up to {@code timeout} for the task to reach a terminal state.
     * Returns the terminal status. Times out via {@link TimeoutException}.
     */
    public BackgroundTaskStatus awaitTerminal(Duration timeout) throws InterruptedException, TimeoutException {
        Objects.requireNonNull(timeout, "timeout");
        try {
            resultFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            // future failed — task ended with FAILED or CANCELLED; status is set
        } catch (java.util.concurrent.CancellationException e) {
            // future was cancelled — task already marked CANCELLED
        }
        return status;
    }

    /**
     * Best-effort cancellation. Marks the task CANCELLED if not yet terminal
     * and signals the runner via the result future. Returns true if cancellation
     * was effective (task wasn't already terminal).
     */
    public synchronized boolean cancel() {
        if (status.isTerminal()) return false;
        status = BackgroundTaskStatus.CANCELLED;
        completedAt = Instant.now();
        resultFuture.cancel(true);
        return true;
    }

    // =================================================================
    // Package-private mutators — only the runner calls these
    // =================================================================

    synchronized void markRunning() {
        if (status == BackgroundTaskStatus.PENDING) {
            status = BackgroundTaskStatus.RUNNING;
        }
    }

    synchronized void completeSuccess(String result) {
        if (status.isTerminal()) return;
        this.output = result;
        this.status = BackgroundTaskStatus.COMPLETED;
        this.completedAt = Instant.now();
        resultFuture.complete(result);
    }

    synchronized void completeFailure(String message) {
        if (status.isTerminal()) return;
        this.error = message;
        this.status = BackgroundTaskStatus.FAILED;
        this.completedAt = Instant.now();
        resultFuture.completeExceptionally(new RuntimeException(message));
    }
}
