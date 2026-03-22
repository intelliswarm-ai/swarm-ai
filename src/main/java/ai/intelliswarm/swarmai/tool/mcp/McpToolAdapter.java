package ai.intelliswarm.swarmai.tool.mcp;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Adapter that wraps an external MCP (Model Context Protocol) tool endpoint
 * as a SwarmAI BaseTool, enabling agents to call MCP-compatible tools.
 *
 * Supports MCP over HTTP/SSE transport.
 * When Spring AI 1.1+ MCP starters are available, this can be replaced with
 * native @McpTool integration.
 *
 * Usage:
 *   BaseTool mcpTool = McpToolAdapter.builder()
 *       .name("weather")
 *       .description("Get weather for a location")
 *       .endpoint("http://localhost:3000/mcp")
 *       .build();
 *
 *   agent.tool(mcpTool);
 */
public class McpToolAdapter implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(McpToolAdapter.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final String name;
    private final String description;
    private final String endpoint;
    private final Map<String, Object> parameterSchema;
    private final int timeoutMs;

    private McpToolAdapter(Builder builder) {
        this.name = builder.name;
        this.description = builder.description;
        this.endpoint = builder.endpoint;
        this.parameterSchema = new HashMap<>(builder.parameterSchema);
        this.timeoutMs = builder.timeoutMs;
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
            // Build MCP JSON-RPC request
            Map<String, Object> request = Map.of(
                    "jsonrpc", "2.0",
                    "method", "tools/call",
                    "params", Map.of(
                            "name", name,
                            "arguments", parameters != null ? parameters : Map.of()
                    ),
                    "id", System.currentTimeMillis()
            );

            String requestBody = objectMapper.writeValueAsString(request);
            logger.debug("MCP call to {}: {}", endpoint, requestBody);

            // HTTP POST to MCP endpoint
            HttpURLConnection conn = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.getBytes());
            }

            int responseCode = conn.getResponseCode();
            String responseBody;

            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(responseCode < 400 ? conn.getInputStream() : conn.getErrorStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
                responseBody = sb.toString();
            }

            if (responseCode >= 400) {
                logger.error("MCP call failed ({}): {}", responseCode, responseBody);
                return "MCP tool error: HTTP " + responseCode + " - " + responseBody;
            }

            // Parse MCP JSON-RPC response
            @SuppressWarnings("unchecked")
            Map<String, Object> response = objectMapper.readValue(responseBody, Map.class);
            Object result = response.get("result");

            logger.debug("MCP response: {}", result);
            return result != null ? result.toString() : "No result from MCP tool";

        } catch (Exception e) {
            logger.error("MCP call to {} failed: {}", endpoint, e.getMessage());
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

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private String description = "";
        private String endpoint;
        private Map<String, Object> parameterSchema = new HashMap<>();
        private int timeoutMs = 30000;

        public Builder name(String name) { this.name = name; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder endpoint(String endpoint) { this.endpoint = endpoint; return this; }
        public Builder parameterSchema(Map<String, Object> schema) { this.parameterSchema = new HashMap<>(schema); return this; }
        public Builder timeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; return this; }

        public McpToolAdapter build() {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("MCP tool name is required");
            if (endpoint == null || endpoint.isBlank()) throw new IllegalArgumentException("MCP endpoint URL is required");
            return new McpToolAdapter(this);
        }
    }
}
