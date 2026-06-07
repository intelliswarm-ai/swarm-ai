package ai.intelliswarm.swarmai.agent.subagent;

import org.springframework.ai.chat.client.ChatClient;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default {@link SubagentSpawner} that runs subagents in the parent JVM on a
 * supplied {@link ExecutorService}. Each spawn:
 * <ol>
 *   <li>Materialises the workspace (if one is requested by the spec).</li>
 *   <li>Constructs an {@link InProcessSubagent} with the supplied
 *       {@link ChatClient}.</li>
 *   <li>Starts it via {@link InProcessSubagent#start()}.</li>
 *   <li>Returns the handle so the caller can {@link Subagent#awaitResult}.</li>
 * </ol>
 *
 * <p>If workspace materialisation throws, the spawner returns a
 * {@link FailedSubagent} that immediately yields a
 * {@link SubagentStatus#FAILED} result — the caller doesn't have to special-case
 * spawn errors.
 *
 * <p>Bring-your-own-executor — pass one that's bounded and named (so subagent
 * threads are easy to spot in stack dumps). The single-arg constructor builds
 * a default cached pool with daemon threads named {@code subagent-N}.
 */
public class InProcessSubagentSpawner implements SubagentSpawner {

    private final ChatClient chatClient;
    private final ExecutorService executor;

    public InProcessSubagentSpawner(ChatClient chatClient) {
        this(chatClient, defaultExecutor());
    }

    public InProcessSubagentSpawner(ChatClient chatClient, ExecutorService executor) {
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public Subagent spawn(SubagentSpec spec) {
        Objects.requireNonNull(spec, "spec");

        IsolatedWorkspace workspace = null;
        if (spec.workspaceFactory() != null) {
            try {
                workspace = spec.workspaceFactory().get();
            } catch (RuntimeException e) {
                return new FailedSubagent(spec.id(),
                        "workspace materialisation failed: " + e.getMessage(),
                        e.getClass().getName());
            }
        }

        InProcessSubagent subagent = new InProcessSubagent(spec, chatClient, executor, workspace);
        subagent.start();
        return subagent;
    }

    public ExecutorService executor() {
        return executor;
    }

    private static ExecutorService defaultExecutor() {
        AtomicLong counter = new AtomicLong();
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "subagent-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Subagent handle returned when workspace materialisation fails. Already
     * terminal in {@link SubagentStatus#FAILED} from construction.
     */
    private static final class FailedSubagent implements Subagent {
        private final String id;
        private final SubagentResult result;

        FailedSubagent(String id, String message, String errorClass) {
            this.id = id;
            this.result = SubagentResult.failure(id, message,
                    SubagentResult.metricsBuilder()
                            .put("errorClass", errorClass)
                            .put("durationMs", 0L)
                            .build());
        }

        @Override public String id() { return id; }
        @Override public SubagentStatus status() { return SubagentStatus.FAILED; }
        @Override public SubagentResult awaitResult(java.time.Duration waitUntil) { return result; }
        @Override public void cancel() { /* no-op */ }
    }
}
