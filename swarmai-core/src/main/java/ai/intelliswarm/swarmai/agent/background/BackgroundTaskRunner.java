package ai.intelliswarm.swarmai.agent.background;

/**
 * SPI: how a {@link BackgroundTask} actually runs. Implementations decide the
 * threading model, timeout policy, and what kind of work the task performs.
 *
 * <p>Built-in implementations:
 * <ul>
 *   <li>{@link LlmBackgroundTaskRunner} — runs the task as an LLM call against
 *       a pre-built {@code ChatClient} with its own tool list</li>
 *   <li>(custom) — Java-only background work (file scans, expensive computations,
 *       async I/O) by implementing this interface directly</li>
 * </ul>
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>Implementations must mark the task as RUNNING via the package-private
 *       {@code markRunning()} call before doing real work.</li>
 *   <li>On success, call {@code completeSuccess(output)} with the result string.</li>
 *   <li>On failure, call {@code completeFailure(message)} with a short reason.</li>
 *   <li>Honour {@code task.status() == CANCELLED} as an exit signal — the
 *       foreground may have called {@link BackgroundTask#cancel()} concurrently.</li>
 * </ul>
 */
@FunctionalInterface
public interface BackgroundTaskRunner {

    /** Begin executing the task asynchronously. Returns immediately. */
    void runAsync(BackgroundTask task);
}
