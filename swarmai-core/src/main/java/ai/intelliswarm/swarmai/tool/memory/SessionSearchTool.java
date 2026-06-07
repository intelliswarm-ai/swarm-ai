package ai.intelliswarm.swarmai.tool.memory;

import ai.intelliswarm.swarmai.api.PublicApi;
import ai.intelliswarm.swarmai.memory.fts.SessionIndex;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Agent-callable wrapper over {@link SessionIndex}. Lets an LLM agent search
 * its own past conversation transcripts mid-loop — "what did we decide last
 * time about ACME?" — without re-loading entire session histories.
 *
 * <p>Input shape:
 * <pre>{@code
 * {"query": "WACC ACME", "agentId": "analyst-1", "limit": 5}
 * }</pre>
 *
 * <p>Output: ordered list of matches with snippets + session + timestamp.
 * The LLM uses the session/timestamp to fetch fuller context if needed.
 */
@PublicApi(since = "1.0.24")
public final class SessionSearchTool implements BaseTool {

    private final SessionIndex index;

    public SessionSearchTool(SessionIndex index) {
        this.index = Objects.requireNonNull(index, "index must not be null");
    }

    @Override public String getFunctionName() { return "session_search"; }

    @Override
    public String getDescription() {
        return "Search past conversation transcripts by keywords. Returns ranked snippets with "
                + "session id and timestamp. Use to recall earlier decisions, context, or facts "
                + "across sessions without reloading entire histories.";
    }

    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.READ_ONLY; }
    @Override public boolean isAsync() { return false; }
    @Override public String getCategory() { return "memory"; }
    @Override public List<String> getTags() { return List.of("memory", "recall", "search", "transcript"); }

    @Override public String getTriggerWhen() {
        return "User asks 'what did we decide/say/discuss' about something from a prior session, "
                + "or asks for context that may exist in past transcripts";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of("type", "string",
                                "description", "FTS5 query (AND/OR, prefix*, NEAR/n proximity)"),
                        "agentId", Map.of("type", "string",
                                "description", "Optional — restrict to a specific agent"),
                        "limit", Map.of("type", "integer",
                                "description", "Max results (default 5, max 50)")),
                "required", List.of("query"));
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        Object queryObj = parameters.get("query");
        if (!(queryObj instanceof String query) || query.isBlank()) {
            return Map.of("ok", false, "error", "Missing or invalid 'query'");
        }
        String agentId = parameters.get("agentId") instanceof String s ? s : null;
        int limit = clampLimit(parameters.get("limit"));

        try {
            List<SessionIndex.SearchHit> hits = index.search(query, agentId, limit);
            List<Map<String, Object>> hitsOut = new ArrayList<>();
            for (SessionIndex.SearchHit h : hits) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("sessionId", h.sessionId());
                row.put("agentId", h.agentId());
                row.put("role", h.role());
                row.put("timestamp", h.timestamp());
                row.put("snippet", h.snippet());
                row.put("rank", h.rank());
                hitsOut.add(row);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("query", query);
            out.put("hits", hitsOut);
            out.put("count", hitsOut.size());
            return out;
        } catch (RuntimeException e) {
            return Map.of("ok", false, "error", "Search failed: " + e.getMessage());
        }
    }

    private static int clampLimit(Object v) {
        int n = 5;
        if (v instanceof Number number) n = number.intValue();
        else if (v instanceof String s) try { n = Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        if (n < 1) n = 1;
        if (n > 50) n = 50;
        return n;
    }
}
