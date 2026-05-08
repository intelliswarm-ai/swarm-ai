package ai.intelliswarm.swarmai.tool.discovery;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.util.Objects;
import java.util.function.Function;

/**
 * Builds an agent-callable {@link ToolCallback} that exposes a
 * {@link ToolLoader} as the {@code tool_load} tool. This is the second half
 * of the deferred-loading story: pair this with {@link ToolSearchTool} to give
 * the agent a complete discovery → load → call workflow.
 *
 * <h2>Usage from the harness</h2>
 * <pre>{@code
 * ToolCatalog catalog = ...;
 * ToolLoader loader = new ToolLoader(catalog);
 * loader.registerCallback("calculator", () -> calculatorCallback);
 * loader.registerCallback("current_time", () -> timeCallback);
 *
 * ToolCallback searchCb = ToolSearchTool.callback(new ToolSearchService(catalog));
 * ToolCallback loadCb   = ToolLoadTool.callback(loader);
 *
 * List<ToolCallback> turnTools = new ArrayList<>(List.of(searchCb, loadCb));
 *
 * for (int turn = 0; turn < maxTurns; turn++) {
 *     String reply = chatClient.prompt().messages(history).toolCallbacks(turnTools.toArray(new ToolCallback[0])).call().content();
 *     int newlyLoaded = loader.applyPendingLoads();
 *     if (newlyLoaded == 0) break;
 *     turnTools.clear();
 *     turnTools.add(searchCb);
 *     turnTools.add(loadCb);
 *     turnTools.addAll(loader.loadedCallbacks());
 * }
 * }</pre>
 *
 * <h2>Tool input shape</h2>
 * <pre>{ "name": "calculator" }</pre>
 *
 * <h2>Tool output shape</h2>
 * Returns one of these strings, all designed for the LLM to recover from:
 * <ul>
 *   <li>{@code "Tool 'X' loaded; you can call it on the next turn."}</li>
 *   <li>{@code "Tool 'X' is already loaded."} — the agent can use it now</li>
 *   <li>{@code "Error: tool 'X' is not in the catalog. Use tool_search first."}</li>
 *   <li>{@code "Error: tool 'X' has no callback registered. Try a different tool."}</li>
 * </ul>
 */
public final class ToolLoadTool {

    /** Default function name registered with Spring AI. */
    public static final String FUNCTION_NAME = "tool_load";

    private ToolLoadTool() {}

    /** Build a callback bound to the given loader, with the default function name. */
    public static ToolCallback callback(ToolLoader loader) {
        return callback(loader, FUNCTION_NAME);
    }

    /** Build a callback with a custom function name. */
    public static ToolCallback callback(ToolLoader loader, String functionName) {
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(functionName, "functionName");
        Function<ToolLoadInput, String> fn = input -> handle(loader, input);
        return FunctionToolCallback.builder(functionName, fn)
                .description(
                        "Request that a catalogued tool be loaded into your tool set for the "
                                + "next conversation turn. Use this AFTER tool_search has surfaced "
                                + "the tool's name. Required: name (the exact tool name from "
                                + "search results). Returns one of: 'loaded', 'already loaded', "
                                + "or an error explaining what to do next. Calling tool_load does "
                                + "NOT execute the tool — the harness will load its callback so "
                                + "you can call it on your next turn.")
                .inputType(ToolLoadInput.class)
                .build();
    }

    /** Visible for testing. */
    static String handle(ToolLoader loader, ToolLoadInput input) {
        if (input == null || input.name == null || input.name.isBlank()) {
            return "Error: 'name' is required and must not be blank.";
        }
        LoadRequestStatus status = loader.requestLoad(input.name);
        return switch (status) {
            case PENDING -> "Tool '" + input.name + "' loaded; you can call it on the next turn.";
            case ALREADY_LOADED -> "Tool '" + input.name + "' is already loaded — call it directly.";
            case NOT_IN_CATALOG -> "Error: tool '" + input.name
                    + "' is not in the catalog. Use tool_search first to find available tool names.";
            case NO_CALLBACK_REGISTERED -> "Error: tool '" + input.name
                    + "' is catalogued but has no callback registered. Try a different tool.";
        };
    }

    /** Tool input shape, deserialised by Spring AI from the LLM's JSON. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ToolLoadInput {
        public String name;
    }
}
