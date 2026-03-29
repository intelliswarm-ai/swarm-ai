package ai.intelliswarm.swarmai.tool.mcp;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Adapter that connects to an MCP server via stdio transport and
 * exposes its tools as SwarmAI BaseTool instances.
 *
 * Uses the official MCP Java SDK (io.modelcontextprotocol.sdk:mcp).
 *
 * Usage:
 *   // Connect to an MCP server and get all its tools
 *   List<BaseTool> tools = McpToolAdapter.fromServer("uvx", "mcp-server-fetch");
 *
 *   // Add to agent
 *   Agent agent = Agent.builder()
 *       .tools(tools)
 *       .build();
 */
public class McpToolAdapter implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(McpToolAdapter.class);

    private final String name;
    private final String description;
    private final McpSyncClient mcpClient;
    private final Map<String, Object> parameterSchema;

    private McpToolAdapter(String name, String description, Map<String, Object> parameterSchema, McpSyncClient mcpClient) {
        this.name = name;
        this.description = description;
        this.parameterSchema = parameterSchema != null ? parameterSchema : Map.of();
        this.mcpClient = mcpClient;
    }

    /**
     * Connects to an MCP server via stdio and returns all its tools as BaseTools.
     *
     * @param command The command to run the MCP server (e.g., "uvx", "npx", "python")
     * @param args    Arguments for the command (e.g., "mcp-server-fetch")
     * @return List of BaseTool instances, one per MCP tool
     */
    public static List<BaseTool> fromServer(String command, String... args) {
        try {
            logger.info("Connecting to MCP server: {} {}", command, String.join(" ", args));

            ServerParameters params = ServerParameters.builder(command)
                    .args(args)
                    .build();

            StdioClientTransport transport = new StdioClientTransport(params);
            McpSyncClient client = McpClient.sync(transport).build();
            client.initialize();

            // List available tools from the MCP server
            McpSchema.ListToolsResult toolsResult = client.listTools();
            List<BaseTool> tools = new ArrayList<>();

            for (McpSchema.Tool mcpTool : toolsResult.tools()) {
                String toolName = mcpTool.name();
                String toolDesc = mcpTool.description();

                // Convert the JSON schema to a Map
                Map<String, Object> schema = Map.of();
                if (mcpTool.inputSchema() != null) {
                    schema = Map.of("type", "object");
                }

                tools.add(new McpToolAdapter(toolName, toolDesc, schema, client));
                logger.info("MCP tool discovered: {} - {}", toolName, toolDesc);
            }

            logger.info("Connected to MCP server with {} tools", tools.size());
            return tools;

        } catch (Exception e) {
            logger.error("Failed to connect to MCP server: {} {} - {}", command, String.join(" ", args), e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public String getFunctionName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        try {
            logger.debug("MCP tool call: {} with params: {}", name, parameters);

            McpSchema.CallToolResult result = mcpClient.callTool(
                    new McpSchema.CallToolRequest(name, parameters != null ? parameters : Map.of()));

            // Extract text content from the result
            if (result.content() != null && !result.content().isEmpty()) {
                String output = result.content().stream()
                        .filter(c -> c instanceof McpSchema.TextContent)
                        .map(c -> ((McpSchema.TextContent) c).text())
                        .collect(Collectors.joining("\n"));

                logger.debug("MCP tool {} returned {} chars", name, output.length());

                // Respect max response length
                if (output.length() > getMaxResponseLength()) {
                    output = output.substring(0, getMaxResponseLength())
                            + "\n[Truncated from " + output.length() + " chars]";
                }

                return output;
            }

            return "No content returned from MCP tool " + name;

        } catch (Exception e) {
            logger.error("MCP tool {} execution failed: {}", name, e.getMessage());
            return "MCP tool error: " + e.getMessage();
        }
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        return new HashMap<>(parameterSchema);
    }

    @Override
    public boolean isAsync() {
        return false;
    }

    @Override
    public int getMaxResponseLength() {
        return 15000; // MCP tools can return large content
    }
}
