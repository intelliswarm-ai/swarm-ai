package ai.intelliswarm.swarmai.tool.mcp;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link McpServerHost#toToolSpec(BaseTool)} produces a valid
 * {@link McpServerFeatures.SyncToolSpecification} for a SwarmAI {@link BaseTool},
 * and that calling its handler invokes the tool and surfaces the result.
 *
 * Drives the round-trip without standing up the stdio transport — calling the
 * SyncToolSpecification's BiFunction is exactly what the MCP server runtime does
 * when a {@code tools/call} request comes in over the wire.
 */
class McpServerHostTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A trivial BaseTool: returns "hello, <name>!" given a {name} param. */
    static class GreeterTool implements BaseTool {
        @Override public String getFunctionName() { return "greet"; }
        @Override public String getDescription() { return "Says hello to a name."; }
        @Override public boolean isAsync() { return false; }
        @Override public Object execute(Map<String, Object> parameters) {
            String name = parameters != null ? (String) parameters.getOrDefault("name", "world") : "world";
            return "hello, " + name + "!";
        }
        @Override public Map<String, Object> getParameterSchema() {
            return Map.of(
                    "type", "object",
                    "properties", Map.of("name", Map.of("type", "string", "description", "Who to greet")),
                    "required", List.of("name"));
        }
    }

    /** A tool that throws — proves errors become MCP error results, not crashes. */
    static class ExplodingTool implements BaseTool {
        @Override public String getFunctionName() { return "boom"; }
        @Override public String getDescription() { return "Always fails."; }
        @Override public boolean isAsync() { return false; }
        @Override public Object execute(Map<String, Object> parameters) {
            throw new IllegalStateException("kaboom");
        }
        @Override public Map<String, Object> getParameterSchema() { return Map.of(); }
    }

    private McpServerHost host(List<BaseTool> tools, McpServerProperties extras) {
        McpServerProperties p = extras != null ? extras : new McpServerProperties();
        return new McpServerHost(p, tools, List.of());
    }

    // ---------------------------------------------------------------- toToolSpec

    @Test
    void toToolSpec_carriesNameDescriptionAndSchema() throws Exception {
        McpServerHost h = host(List.of(new GreeterTool()), null);

        McpServerFeatures.SyncToolSpecification spec = h.toToolSpec(new GreeterTool());
        McpSchema.Tool tool = spec.tool();

        assertThat(tool.name()).isEqualTo("greet");
        assertThat(tool.description()).isEqualTo("Says hello to a name.");

        // Schema is parsed back from JSON to confirm valid structure round-trips
        JsonNode schema = JSON.valueToTree(tool.inputSchema());
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("properties").path("name").path("type").asText()).isEqualTo("string");
    }

    @Test
    void toToolSpec_emptySchema_yieldsObjectShape() {
        McpServerHost h = host(List.of(new ExplodingTool()), null);
        McpServerFeatures.SyncToolSpecification spec = h.toToolSpec(new ExplodingTool());
        // No exception, schema present
        assertThat(spec.tool().inputSchema()).isNotNull();
    }

    // ---------------------------------------------------------------- handler

    @Test
    void handler_invokesTool_andReturnsTextResult() {
        McpServerHost h = host(List.of(new GreeterTool()), null);
        McpServerFeatures.SyncToolSpecification spec = h.toToolSpec(new GreeterTool());

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("name", "Alice");
        McpSchema.CallToolResult result = spec.call().apply(null, args);

        assertThat(result.isError()).isFalse();
        assertThat(result.content()).isNotEmpty();
        assertThat(((McpSchema.TextContent) result.content().get(0)).text())
                .isEqualTo("hello, Alice!");
    }

    @Test
    void handler_toolThrows_returnsErrorResultNotException() {
        McpServerHost h = host(List.of(new ExplodingTool()), null);
        McpServerFeatures.SyncToolSpecification spec = h.toToolSpec(new ExplodingTool());

        McpSchema.CallToolResult result = spec.call().apply(null, Map.of());

        assertThat(result.isError()).isTrue();
        assertThat(((McpSchema.TextContent) result.content().get(0)).text())
                .contains("kaboom");
    }

    // ---------------------------------------------------------------- include/exclude filters

    @Test
    void filterTools_includeWhitelist_winsOverExclude() throws Exception {
        // Use reflection to test the private filter — it's the only behavior on
        // the host class that doesn't need stdio to verify.
        McpServerProperties p = new McpServerProperties();
        p.setIncludeTools(List.of("greet"));
        p.setExcludeTools(List.of("greet"));     // intentionally contradictory
        McpServerHost h = host(List.of(new GreeterTool(), new ExplodingTool()), p);

        java.lang.reflect.Method m = McpServerHost.class.getDeclaredMethod("filterTools", List.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<BaseTool> kept = (List<BaseTool>) m.invoke(h, List.of(new GreeterTool(), new ExplodingTool()));
        assertThat(kept).hasSize(1);
        assertThat(kept.get(0).getFunctionName()).isEqualTo("greet");
    }

    @Test
    void filterTools_excludeBlacklist_dropsMatching() throws Exception {
        McpServerProperties p = new McpServerProperties();
        p.setExcludeTools(List.of("boom"));
        McpServerHost h = host(List.of(new GreeterTool(), new ExplodingTool()), p);

        java.lang.reflect.Method m = McpServerHost.class.getDeclaredMethod("filterTools", List.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<BaseTool> kept = (List<BaseTool>) m.invoke(h, List.of(new GreeterTool(), new ExplodingTool()));
        assertThat(kept).hasSize(1);
        assertThat(kept.get(0).getFunctionName()).isEqualTo("greet");
    }

    @Test
    void filterTools_nullIncludeAndExcludeLists_doNotNpe() throws Exception {
        // Reproduces the codex P2 finding — yaml `~` or programmatic setNullList(null)
        // used to NPE on startup. Properties setters now normalise null to empty list,
        // and the filter() helper defends as a second line of safety.
        McpServerProperties p = new McpServerProperties();
        p.setIncludeTools(null);
        p.setExcludeTools(null);
        assertThat(p.getIncludeTools()).isEmpty();
        assertThat(p.getExcludeTools()).isEmpty();

        McpServerHost h = host(List.of(new GreeterTool()), p);
        java.lang.reflect.Method m = McpServerHost.class.getDeclaredMethod("filterTools", List.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<BaseTool> kept = (List<BaseTool>) m.invoke(h, List.of(new GreeterTool()));
        assertThat(kept).hasSize(1);
    }
}
