package ai.intelliswarm.swarmai.agent.background;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Builds the four agent-callable {@link ToolCallback}s that expose a
 * {@link BackgroundTaskRegistry} to the foreground agent:
 *
 * <ul>
 *   <li>{@code background_spawn(prompt)} — start a task, return its id</li>
 *   <li>{@code background_list()} — list every task with status + age</li>
 *   <li>{@code background_output(id)} — fetch status + output (or "still running")</li>
 *   <li>{@code background_stop(id)} — request cancellation</li>
 * </ul>
 *
 * <p>The tools are designed for Spring AI's auto-tool loop: a foreground agent
 * can spawn a task at the start of a turn, do other work, then poll
 * {@code background_output} later in the same {@code .call()} to retrieve
 * the result. Or, in a multi-turn pattern, spawn in turn 1 and read in turn 2.
 */
public final class BackgroundTaskTools {

    public static final String SPAWN_NAME = "background_spawn";
    public static final String LIST_NAME = "background_list";
    public static final String OUTPUT_NAME = "background_output";
    public static final String STOP_NAME = "background_stop";

    private BackgroundTaskTools() {}

    /** Bundle all four callbacks into one list — drop into {@code toolCallbacks(...)}. */
    public static List<ToolCallback> all(BackgroundTaskRegistry registry) {
        return List.of(
                spawnCallback(registry),
                listCallback(registry),
                outputCallback(registry),
                stopCallback(registry));
    }

    // ============== background_spawn ==============

    public static ToolCallback spawnCallback(BackgroundTaskRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        Function<SpawnInput, String> fn = input -> handleSpawn(registry, input);
        return FunctionToolCallback.builder(SPAWN_NAME, fn)
                .description(
                        "Start a background task. The harness runs the prompt asynchronously "
                                + "and returns an id immediately. Use background_output(id) later "
                                + "to fetch the result. "
                                + "Required: prompt (the work to perform). "
                                + "Returns: 'Spawned task <id>; check it with background_output.'")
                .inputType(SpawnInput.class)
                .build();
    }

    static String handleSpawn(BackgroundTaskRegistry registry, SpawnInput input) {
        if (input == null || input.prompt == null || input.prompt.isBlank()) {
            return "Error: 'prompt' is required and must not be blank.";
        }
        try {
            BackgroundTask task = registry.spawn(input.prompt);
            return "Spawned task " + task.id() + "; check it with background_output("
                    + task.id() + ").";
        } catch (RuntimeException e) {
            return "Error: " + e.getMessage();
        }
    }

    // ============== background_list ==============

    public static ToolCallback listCallback(BackgroundTaskRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        Function<ListInput, String> fn = input -> handleList(registry, input);
        return FunctionToolCallback.builder(LIST_NAME, fn)
                .description(
                        "List background tasks. "
                                + "Optional: status (filter to one of PENDING|RUNNING|COMPLETED|FAILED|CANCELLED — "
                                + "leave null/blank for ALL). "
                                + "Returns 'No tasks yet.' or a multi-line summary with one row per task.")
                .inputType(ListInput.class)
                .build();
    }

    static String handleList(BackgroundTaskRegistry registry, ListInput input) {
        List<BackgroundTask> all = registry.all();
        if (all.isEmpty()) return "No tasks yet.";
        // Optional status filter
        BackgroundTaskStatus filter = null;
        if (input != null && input.status != null && !input.status.isBlank()) {
            try {
                filter = BackgroundTaskStatus.valueOf(input.status.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return "Error: 'status' must be one of PENDING, RUNNING, COMPLETED, FAILED, CANCELLED.";
            }
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (BackgroundTask t : all) {
            if (filter != null && t.status() != filter) continue;
            sb.append("  ").append(t.id())
                    .append("  ").append(t.status())
                    .append("  age=").append(t.duration().toSeconds()).append("s\n");
            count++;
        }
        if (count == 0) {
            return "No tasks match status=" + filter + ".";
        }
        // Header on top
        sb.insert(0, count + " task(s):\n");
        // Trim trailing newline
        if (sb.charAt(sb.length() - 1) == '\n') sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    // ============== background_output ==============

    public static ToolCallback outputCallback(BackgroundTaskRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        Function<IdInput, String> fn = input -> handleOutput(registry, input);
        return FunctionToolCallback.builder(OUTPUT_NAME, fn)
                .description(
                        "Fetch a background task's status and output. "
                                + "Required: id (returned from background_spawn). "
                                + "Returns the output if COMPLETED, the error if FAILED, "
                                + "or 'Status: RUNNING/PENDING (age=Xs)' if not done yet — in "
                                + "which case poll again later or move on to other work.")
                .inputType(IdInput.class)
                .build();
    }

    static String handleOutput(BackgroundTaskRegistry registry, IdInput input) {
        if (input == null || input.id == null || input.id.isBlank()) {
            return "Error: 'id' is required and must not be blank.";
        }
        return registry.get(input.id).map(t -> switch (t.status()) {
            case COMPLETED -> "Status: COMPLETED (age=" + t.duration().toSeconds()
                    + "s)\nOutput:\n" + t.output().orElse("(empty)");
            case FAILED -> "Status: FAILED (age=" + t.duration().toSeconds()
                    + "s)\nError: " + t.error().orElse("(no error message)");
            case CANCELLED -> "Status: CANCELLED (age=" + t.duration().toSeconds() + "s)";
            case RUNNING -> "Status: RUNNING (age=" + t.duration().toSeconds()
                    + "s) — poll again or move on";
            case PENDING -> "Status: PENDING (age=" + t.duration().toSeconds()
                    + "s) — runner hasn't picked it up yet";
        }).orElse("Error: no task with id '" + input.id + "'.");
    }

    // ============== background_stop ==============

    public static ToolCallback stopCallback(BackgroundTaskRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        Function<IdInput, String> fn = input -> handleStop(registry, input);
        return FunctionToolCallback.builder(STOP_NAME, fn)
                .description(
                        "Cancel a background task. "
                                + "Required: id. Returns 'Cancelled' if effective, 'Already terminal' "
                                + "if the task already finished, or an error if no task exists.")
                .inputType(IdInput.class)
                .build();
    }

    static String handleStop(BackgroundTaskRegistry registry, IdInput input) {
        if (input == null || input.id == null || input.id.isBlank()) {
            return "Error: 'id' is required and must not be blank.";
        }
        return registry.get(input.id).map(t -> {
            boolean cancelled = t.cancel();
            return cancelled
                    ? "Cancelled task " + t.id() + "."
                    : "Already terminal: task " + t.id() + " is " + t.status() + ".";
        }).orElse("Error: no task with id '" + input.id + "'.");
    }

    // =================================================================
    // Tool input shapes
    // =================================================================

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SpawnInput {
        public String prompt;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class IdInput {
        public String id;
    }

    /**
     * Input for {@code background_list}. The {@code status} field is an
     * optional filter — empty/null returns all tasks. We keep at least one
     * property in the schema because OpenAI rejects tool definitions whose
     * input object has no properties.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ListInput {
        public String status;
    }

    /** Default cap used by callers waiting on tasks to finish — exposed for tests. */
    public static final Duration DEFAULT_AWAIT_TIMEOUT = Duration.ofSeconds(60);
}
