package ai.intelliswarm.swarmai.tool.discovery;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Builds an agent-callable {@link ToolCallback} that exposes a
 * {@link ToolSearchService} as the {@code tool_search} tool. Drop the returned
 * callback into a {@code chatClient.prompt().toolCallbacks(...)} invocation
 * — typically alongside no other tools — and the agent can discover further
 * tools by query without their schemas being preloaded into the system prompt.
 *
 * <p>This is the load-bearing primitive for deferred tool loading: at
 * conversation start, the only tool the agent sees is {@code tool_search}.
 * The agent uses it to enumerate the catalog by relevance, reads the
 * descriptions and {@code schemaSummary} of the matches, and then either
 * (a) plans a follow-up call with the harness loading the matched tool's
 * callback, or (b) reports back what it found.
 *
 * <h2>Tool input shape (as the LLM sees it)</h2>
 * <pre>
 *   { "query": "calculate something", "maxResults": 3 }
 * </pre>
 *
 * <h2>Tool output shape (returned to the LLM)</h2>
 * <pre>
 *   Found 3 of 15 catalogued tools matching "calculate":
 *
 *     1. calculator (score=4.0)
 *        Perform arithmetic on a math expression.
 *        args: expression (string)
 *        keywords: math, arithmetic, compute
 *
 *     2. statistics (score=2.0)
 *        Statistical analysis of a numeric array.
 *        args: data (array<number>)
 *
 *     3. ...
 *
 *   To request loading any of these for the next turn, ask the user
 *   to enable them.
 * </pre>
 *
 * <p>The "ask the user" trailing line is intentional: in this MVP the example
 * does not implement automatic tool-loading via a follow-up tool. The full
 * deferred-loading story requires a harness loop that reads the LLM's
 * preferences and re-enters {@code chatClient.prompt()} with the matched
 * callbacks attached.
 */
public final class ToolSearchTool {

    /** Default function name registered with Spring AI. */
    public static final String FUNCTION_NAME = "tool_search";

    private ToolSearchTool() {}

    /** Build a callback bound to the given service, with the default function name. */
    public static ToolCallback callback(ToolSearchService service) {
        return callback(service, FUNCTION_NAME);
    }

    /** Build a callback with a custom function name. */
    public static ToolCallback callback(ToolSearchService service, String functionName) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(functionName, "functionName");
        Function<ToolSearchInput, String> fn = input -> handle(service, input);
        return FunctionToolCallback.builder(functionName, fn)
                .description(
                        "Search the harness's catalogue of available tools by keyword. "
                                + "Use this when you don't have a specific tool loaded but think "
                                + "one might exist for the task. "
                                + "Required: query (free-text keywords). "
                                + "Optional: maxResults (default 5). "
                                + "Returns a ranked list of (name, description, schema-summary) "
                                + "for matching tools — schemas are NOT loaded into your tool set "
                                + "automatically; you'll need to act on the descriptions and ask "
                                + "the user to enable any tool you want to call.")
                .inputType(ToolSearchInput.class)
                .build();
    }

    /** Visible for testing. */
    static String handle(ToolSearchService service, ToolSearchInput input) {
        if (input == null || input.query == null || input.query.isBlank()) {
            return "Error: 'query' is required and must not be blank.";
        }
        int maxResults = (input.maxResults == null || input.maxResults <= 0)
                ? ToolSearchService.DEFAULT_MAX_RESULTS
                : input.maxResults;
        List<ToolMatch> matches = service.search(input.query, maxResults);
        int catalogSize = service.catalog().size();

        if (matches.isEmpty()) {
            return "No catalogued tools matched query: '" + input.query + "'. "
                    + "(Searched " + catalogSize + " tools.) Try different keywords "
                    + "or proceed without a tool.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(matches.size())
                .append(" of ").append(catalogSize)
                .append(" catalogued tools matching \"").append(input.query).append("\":\n\n");
        int rank = 1;
        for (ToolMatch m : matches) {
            CatalogEntry e = m.entry();
            sb.append("  ").append(rank).append(". ").append(e.name())
                    .append(" (score=").append(formatScore(m.score())).append(")\n");
            sb.append("     ").append(e.description()).append('\n');
            if (!e.schemaSummary().isEmpty()) {
                sb.append("     ").append(e.schemaSummary()).append('\n');
            }
            if (!e.keywords().isEmpty()) {
                sb.append("     keywords: ").append(String.join(", ", e.keywords())).append('\n');
            }
            sb.append('\n');
            rank++;
        }
        sb.append("To request loading any of these for the next turn, "
                + "ask the user to enable them.");
        return sb.toString();
    }

    private static String formatScore(double s) {
        // Strip trailing zero on integer-valued scores: 3.0 -> 3.0 stays as 3.0
        // for stable test assertions; non-integer like 4.5 also rendered as-is.
        return String.format(java.util.Locale.ROOT, "%.1f", s);
    }

    /** Tool input shape, deserialised by Spring AI from the LLM's JSON. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ToolSearchInput {
        public String query;
        public Integer maxResults;
    }
}
