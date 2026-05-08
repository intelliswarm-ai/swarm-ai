package ai.intelliswarm.swarmai.agent.background;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

/**
 * Default {@link BackgroundTaskRunner} that runs each task as an LLM call
 * against a pre-built {@link ChatClient} with its own tool list and system
 * prompt. The submitted prompt becomes the user message; the LLM's reply
 * becomes the task's output.
 *
 * <p>Crucially, the {@code toolCallbacks} list passed here should NOT include
 * the {@code background_spawn} tool itself — otherwise a background task
 * could spawn its own background tasks recursively. Pair this with
 * {@link BackgroundTaskTools#spawnCallback(BackgroundTaskRegistry)} on the
 * <em>foreground</em> agent only.
 */
public final class LlmBackgroundTaskRunner implements BackgroundTaskRunner {

    private final ChatClient chatClient;
    private final ExecutorService executor;
    private final String systemPrompt;
    private final List<ToolCallback> toolCallbacks;

    public LlmBackgroundTaskRunner(ChatClient chatClient,
                                    ExecutorService executor,
                                    String systemPrompt,
                                    List<ToolCallback> toolCallbacks) {
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt");
        this.toolCallbacks = (toolCallbacks == null) ? List.of() : List.copyOf(toolCallbacks);
    }

    /** Convenience: no tools, just a system prompt + the user-supplied task prompt. */
    public LlmBackgroundTaskRunner(ChatClient chatClient,
                                    ExecutorService executor,
                                    String systemPrompt) {
        this(chatClient, executor, systemPrompt, List.of());
    }

    @Override
    public void runAsync(BackgroundTask task) {
        Objects.requireNonNull(task, "task");
        executor.submit(() -> {
            // Race check: cancellation may have happened between spawn and pick-up
            if (task.status() == BackgroundTaskStatus.CANCELLED) return;
            task.markRunning();
            try {
                ChatClient.ChatClientRequestSpec req = chatClient.prompt()
                        .system(systemPrompt)
                        .user(task.prompt());
                if (!toolCallbacks.isEmpty()) {
                    req = req.toolCallbacks(toolCallbacks.toArray(new ToolCallback[0]));
                }
                String content = req.call().content();
                if (task.status() == BackgroundTaskStatus.CANCELLED) return;
                task.completeSuccess(content == null ? "" : content);
            } catch (RuntimeException e) {
                if (task.status() != BackgroundTaskStatus.CANCELLED) {
                    task.completeFailure(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        });
    }
}
