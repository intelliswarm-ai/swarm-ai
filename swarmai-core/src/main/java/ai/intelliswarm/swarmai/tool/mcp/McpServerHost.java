package ai.intelliswarm.swarmai.tool.mcp;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Hosts SwarmAI {@link BaseTool} and {@link Agent} beans as an MCP server.
 *
 * <p>This is the inverse of {@link McpToolAdapter} (which is an MCP <em>client</em>).
 * Once the server is up, any MCP client — Claude Desktop, Cursor, Cline,
 * other agents — can discover and call your local tools/agents without writing
 * a custom UI or REST API.
 *
 * <p>Two granularities are exposed in the same MCP namespace:
 * <ul>
 *   <li><b>Tools</b> — atomic capabilities (a {@link BaseTool} bean's
 *       {@code execute(...)} method).</li>
 *   <li><b>Agents</b> — coherent personas: a {@link Agent} bean's role + tools +
 *       LLM, invoked with a free-form {@code task} string.</li>
 * </ul>
 *
 * <p>Transports supported: <strong>stdio</strong> (matches Claude Desktop's default).
 * SSE / HTTP-streaming will follow in a separate pass.
 *
 * <p>This component is wired via {@link McpServerAutoConfiguration} and gated by
 * {@code swarmai.mcp.server.enabled=true}.
 */
public class McpServerHost {

    private static final Logger logger = LoggerFactory.getLogger(McpServerHost.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Cap on the agent's response text returned to the MCP client. Picked to be
     * roughly 4× a typical model context-window slice while still small enough
     * that a runaway agent can't blow up the client.
     */
    private static final int AGENT_RESPONSE_MAX_CHARS = 32_000;

    private final McpServerProperties props;
    private final List<BaseTool> tools;
    private final List<Agent> agents;
    private McpSyncServer server;
    /**
     * The tool specifications published in {@link #start()}, in registration order.
     * Held so {@link #listExposedTools()} and {@link #callTool} can introspect /
     * invoke without re-walking the bean lists.
     */
    private List<McpServerFeatures.SyncToolSpecification> specs;

    public McpServerHost(McpServerProperties props, List<BaseTool> tools, List<Agent> agents) {
        this.props = props;
        this.tools = tools != null ? tools : List.of();
        this.agents = agents != null ? agents : List.of();
    }

    @PostConstruct
    public void start() {
        if (!"stdio".equalsIgnoreCase(props.getTransport())) {
            throw new IllegalStateException("Unsupported transport: " + props.getTransport()
                    + " (this build supports 'stdio' only)");
        }

        List<BaseTool> exposedTools = filterTools(tools);
        List<Agent> exposedAgents = filterAgents(agents);
        if (exposedTools.isEmpty() && exposedAgents.isEmpty()) {
            logger.warn("McpServerHost: nothing to expose after filtering — server will start "
                    + "but advertise zero tools. Check swarmai.mcp.server.includeTools / "
                    + "includeAgents / excludeTools / excludeAgents.");
        }

        // Tools and Agents both compile to MCP SyncToolSpecifications; from the MCP client's
        // perspective they're indistinguishable. Names are checked for collisions because the
        // MCP protocol requires unique tool names per server.
        this.specs = new ArrayList<>(exposedTools.size() + exposedAgents.size());
        Set<String> seen = new HashSet<>();
        for (BaseTool t : exposedTools) {
            McpServerFeatures.SyncToolSpecification s = toToolSpec(t);
            if (seen.add(s.tool().name())) specs.add(s);
            else logger.warn("McpServerHost: skipping duplicate name '{}' (tool)", s.tool().name());
        }
        for (Agent a : exposedAgents) {
            McpServerFeatures.SyncToolSpecification s = toAgentSpec(a);
            if (seen.add(s.tool().name())) specs.add(s);
            else logger.warn("McpServerHost: skipping duplicate name '{}' (agent role collides "
                    + "with an existing tool name — rename the agent role or add to excludeAgents)",
                    s.tool().name());
        }

        StdioServerTransportProvider transport = new StdioServerTransportProvider(JSON);
        McpServer.SyncSpecification spec = McpServer.sync(transport)
                .serverInfo(props.getName(), props.getVersion())
                .tools(specs);
        if (props.getInstructions() != null && !props.getInstructions().isBlank()) {
            spec = spec.instructions(props.getInstructions());
        }
        this.server = spec.build();

        logger.info("McpServerHost started: name={} version={} transport={} tools={} agents={}",
                props.getName(), props.getVersion(), props.getTransport(),
                exposedTools.size(), exposedAgents.size());
        if (logger.isDebugEnabled()) {
            for (BaseTool t : exposedTools) {
                logger.debug("  tool : {} — {}", t.getFunctionName(), t.getDescription());
            }
            for (Agent a : exposedAgents) {
                logger.debug("  agent: {} (role='{}')", agentMcpName(a), a.getRole());
            }
        }
    }

    /**
     * Returns the catalog of MCP tools currently published by this server, in the
     * order they were registered. Available after {@link #start()} has run.
     * Useful for actuator endpoints, debug UIs, and example code that wants to
     * verify the server came up correctly.
     */
    public List<McpSchema.Tool> listExposedTools() {
        if (specs == null) return List.of();
        List<McpSchema.Tool> out = new ArrayList<>(specs.size());
        for (McpServerFeatures.SyncToolSpecification s : specs) out.add(s.tool());
        return out;
    }

    /**
     * Invokes one of the published tools the same way the MCP transport would
     * dispatch a {@code tools/call} request. Returns the result, or an error
     * result with a "Tool not found" message if the name is unknown. Catches
     * every throwable inside the spec's handler — see {@link #invoke} and
     * {@link #invokeAgent} for details.
     *
     * <p>Mainly useful for in-process verification (the {@code mcp-server-host}
     * example exercises this without standing up the stdio transport).
     */
    public McpSchema.CallToolResult callTool(String name, Map<String, Object> args) {
        if (specs == null || name == null) {
            return new McpSchema.CallToolResult("Server not started", true);
        }
        for (McpServerFeatures.SyncToolSpecification s : specs) {
            if (name.equals(s.tool().name())) {
                return s.call().apply(null, args != null ? args : Map.of());
            }
        }
        return new McpSchema.CallToolResult("Tool not found: " + name, true);
    }

    @PreDestroy
    public void stop() {
        if (server != null) {
            try {
                server.closeGracefully();
                logger.info("McpServerHost stopped");
            } catch (Exception e) {
                logger.warn("McpServerHost: error during shutdown: {}", e.getMessage());
            }
        }
    }

    /**
     * Builds a single MCP {@link McpServerFeatures.SyncToolSpecification} from a SwarmAI {@link BaseTool}.
     * Package-private so tests can drive it without standing up the whole stdio transport.
     */
    McpServerFeatures.SyncToolSpecification toToolSpec(BaseTool tool) {
        String inputSchemaJson = renderInputSchema(tool);
        McpSchema.Tool mcpTool = new McpSchema.Tool(
                tool.getFunctionName(),
                tool.getDescription() != null ? tool.getDescription() : "",
                inputSchemaJson);

        return new McpServerFeatures.SyncToolSpecification(
                mcpTool,
                (exchange, args) -> invoke(tool, args));
    }

    /**
     * Invokes a BaseTool from an incoming MCP {@code tools/call} request.
     * Catches every throwable so a buggy tool can't take down the server — failures
     * are surfaced as MCP error results instead.
     */
    private McpSchema.CallToolResult invoke(BaseTool tool, Map<String, Object> args) {
        try {
            Object result = tool.execute(args != null ? args : Map.of());
            String text = result == null ? "" : result.toString();
            // Defensive truncation: respect the tool's own maxResponseLength so an MCP
            // client doesn't get hit with a 50 MB payload from a misbehaving tool.
            int max = tool.getMaxResponseLength();
            if (text.length() > max) {
                text = text.substring(0, max) + "\n[truncated from " + text.length() + " chars]";
            }
            return new McpSchema.CallToolResult(text, false);
        } catch (Exception e) {
            logger.error("McpServerHost: tool '{}' threw: {}", tool.getFunctionName(), e.getMessage(), e);
            return new McpSchema.CallToolResult("Tool error: " + e.getMessage(), true);
        }
    }

    /**
     * Renders a BaseTool's parameter schema into a JSON string accepted by the MCP
     * {@link McpSchema.Tool} record. Returns an empty object schema when the tool
     * didn't declare one or serialization fails.
     *
     * <p>Contract: {@link BaseTool#getParameterSchema()} returns a JSON-Schema-shaped
     * map (top-level {@code type=object} with a {@code properties} block). Tools that
     * don't conform get an empty object schema — the MCP client will see "no inputs"
     * rather than a malformed schema.
     */
    private String renderInputSchema(BaseTool tool) {
        Map<String, Object> raw = tool.getParameterSchema();
        if (raw == null || raw.isEmpty()) {
            return EMPTY_OBJECT_SCHEMA;
        }
        try {
            return JSON.writeValueAsString(raw);
        } catch (Exception e) {
            logger.warn("McpServerHost: failed to serialize input schema for '{}': {}",
                    tool.getFunctionName(), e.getMessage());
            return EMPTY_OBJECT_SCHEMA;
        }
    }

    private static final String EMPTY_OBJECT_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";

    private List<BaseTool> filterTools(List<BaseTool> all) {
        return filter(all, BaseTool::getFunctionName,
                props.getIncludeTools(), props.getExcludeTools());
    }

    // =========================================================================
    // AGENT EXPOSURE — wrap an Agent (its persona + tools + LLM) as an MCP tool.
    // The MCP client gives a free-form 'task' string, the framework runs it
    // through the agent, and the rendered output comes back. Useful when you
    // want to publish a coherent role ("Senior Code Reviewer") rather than
    // individual capabilities ("file_read", "code_execution").
    // =========================================================================

    /**
     * Builds an MCP {@link McpServerFeatures.SyncToolSpecification} from a SwarmAI {@link Agent}.
     * Package-private for testing.
     */
    McpServerFeatures.SyncToolSpecification toAgentSpec(Agent agent) {
        String name = agentMcpName(agent);
        String description = buildAgentDescription(agent);
        McpSchema.Tool mcpTool = new McpSchema.Tool(name, description, agentInputSchemaJson());
        return new McpServerFeatures.SyncToolSpecification(
                mcpTool,
                (exchange, args) -> invokeAgent(agent, args));
    }

    /**
     * Invokes the agent with a Task assembled from the MCP tool-call args.
     * Like {@link #invoke}, every throwable is captured and returned as an
     * MCP error result so a buggy agent can't crash the server.
     */
    private McpSchema.CallToolResult invokeAgent(Agent agent, Map<String, Object> args) {
        try {
            Map<String, Object> a = args != null ? args : Map.of();
            String taskDescription = (String) a.get("task");
            if (taskDescription == null || taskDescription.isBlank()) {
                return new McpSchema.CallToolResult(
                        "Missing required argument 'task' (the description of what the agent should do)",
                        true);
            }

            Task.Builder taskBuilder = Task.builder()
                    .description(taskDescription)
                    .agent(agent);
            // Pass through expectedOutput only when the caller provided one; let the agent's
            // built-in defaults apply otherwise — injecting a placeholder string into the prompt
            // would just add noise.
            String expectedOutput = (String) a.get("expectedOutput");
            if (expectedOutput != null && !expectedOutput.isBlank()) {
                taskBuilder.expectedOutput(expectedOutput);
            }

            TaskOutput out = agent.executeTask(taskBuilder.build(), List.of());
            String text = out != null && out.getRawOutput() != null ? out.getRawOutput() : "";
            if (text.length() > AGENT_RESPONSE_MAX_CHARS) {
                text = text.substring(0, AGENT_RESPONSE_MAX_CHARS)
                        + "\n[truncated from " + text.length() + " chars]";
            }
            return new McpSchema.CallToolResult(text, false);
        } catch (Exception e) {
            logger.error("McpServerHost: agent '{}' threw: {}", agent.getRole(), e.getMessage(), e);
            return new McpSchema.CallToolResult("Agent error: " + e.getMessage(), true);
        }
    }

    /**
     * MCP tool input schema for an agent — a free-form task description plus an
     * optional expectedOutput hint. Same shape for every exposed agent because
     * the agent's own goal/backstory drives what counts as a sensible task.
     */
    private String agentInputSchemaJson() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("task", Map.of(
                "type", "string",
                "description", "What the agent should do — describe the task in natural language."));
        properties.put("expectedOutput", Map.of(
                "type", "string",
                "description", "Optional. Format/length expectation for the agent's response."));
        schema.put("properties", properties);
        schema.put("required", List.of("task"));
        try {
            return JSON.writeValueAsString(schema);
        } catch (Exception e) {
            return "{\"type\":\"object\",\"properties\":{\"task\":{\"type\":\"string\"}},\"required\":[\"task\"]}";
        }
    }

    /**
     * Renders a stable MCP-friendly tool name from the agent's role.
     * "Senior Data Analyst" → "senior_data_analyst".
     *
     * <p>Public so example code and external introspection callers can predict
     * the published name without instantiating an MCP server.
     */
    public static String agentMcpName(Agent agent) {
        String role = agent.getRole();
        if (role == null || role.isBlank()) return "agent";
        String s = role.trim().toLowerCase();
        // Replace any non-[a-z0-9] run with a single underscore.
        s = s.replaceAll("[^a-z0-9]+", "_");
        // Trim leading/trailing underscores.
        s = s.replaceAll("^_+|_+$", "");
        return s.isEmpty() ? "agent" : s;
    }

    /**
     * Combines the agent's role and goal into the description an MCP client sees.
     */
    private static String buildAgentDescription(Agent agent) {
        StringBuilder sb = new StringBuilder();
        sb.append(agent.getRole() != null ? agent.getRole() : "Agent");
        if (agent.getGoal() != null && !agent.getGoal().isBlank()) {
            sb.append(": ").append(agent.getGoal());
        }
        return sb.toString();
    }

    private List<Agent> filterAgents(List<Agent> all) {
        return filter(all, McpServerHost::agentMcpName,
                props.getIncludeAgents(), props.getExcludeAgents());
    }

    /**
     * Applies whitelist/blacklist rules. When {@code include} is non-empty it wins
     * (only matching items kept). Otherwise items in {@code exclude} are dropped.
     *
     * <p>Defends against null lists even though {@link McpServerProperties} setters
     * already normalise to empty — defence in depth in case a caller constructs the
     * host directly.
     */
    private static <T> List<T> filter(List<T> all, Function<T, String> nameOf,
                                       List<String> include, List<String> exclude) {
        if (include == null) include = List.of();
        if (exclude == null) exclude = List.of();
        List<T> out = new ArrayList<>(all.size());
        for (T item : all) {
            String n = nameOf.apply(item);
            if (!include.isEmpty()) {
                if (include.contains(n)) out.add(item);
            } else if (!exclude.contains(n)) {
                out.add(item);
            }
        }
        return out;
    }
}
