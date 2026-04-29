package ai.intelliswarm.swarmai.tool.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Configuration for hosting SwarmAI tools as an MCP server.
 *
 * Bound from {@code swarmai.mcp.server.*} in application.yml.
 *
 * The server is OFF by default. Enable explicitly with
 * {@code swarmai.mcp.server.enabled=true} so a user starting an example
 * doesn't accidentally publish their internal tools over stdio.
 */
@ConfigurationProperties(prefix = "swarmai.mcp.server")
public class McpServerProperties {

    /** Enable the MCP server host. Default: false (servers are an explicit opt-in). */
    private boolean enabled = false;

    /** Server name advertised to MCP clients. */
    private String name = "swarmai";

    /** Server version advertised to MCP clients. */
    private String version = "1.0";

    /**
     * Optional human-readable instructions returned in the MCP {@code initialize}
     * response. Helps clients (Claude Desktop, Cursor, Cline) know how to use the
     * exposed tools. {@code null} means no instructions are sent.
     */
    private String instructions = null;

    /** Transport: only "stdio" supported in this first cut. SSE/HTTP coming next. */
    private String transport = "stdio";

    /**
     * If non-empty, ONLY tools whose {@link ai.intelliswarm.swarmai.tool.base.BaseTool#getFunctionName()}
     * matches one of these names are exposed. Take precedence over {@link #excludeTools}.
     */
    private List<String> includeTools = List.of();

    /**
     * Tools to exclude from the server. Useful when you want "everything except this".
     */
    private List<String> excludeTools = List.of();

    /**
     * If non-empty, ONLY agents whose MCP-name (slugified role) matches one of these
     * are exposed. Use to publish a coherent agent persona — "senior_code_reviewer",
     * "incident_responder" — alongside or instead of individual BaseTools.
     */
    private List<String> includeAgents = List.of();

    /**
     * Agents (by slugified MCP-name) to exclude. "everything except this" semantics.
     */
    private List<String> excludeAgents = List.of();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getInstructions() { return instructions; }
    public void setInstructions(String instructions) { this.instructions = instructions; }

    public String getTransport() { return transport; }
    public void setTransport(String transport) { this.transport = transport; }

    public List<String> getIncludeTools() { return includeTools; }
    public void setIncludeTools(List<String> includeTools) { this.includeTools = includeTools; }

    public List<String> getExcludeTools() { return excludeTools; }
    public void setExcludeTools(List<String> excludeTools) { this.excludeTools = excludeTools; }

    public List<String> getIncludeAgents() { return includeAgents; }
    public void setIncludeAgents(List<String> includeAgents) { this.includeAgents = includeAgents; }

    public List<String> getExcludeAgents() { return excludeAgents; }
    public void setExcludeAgents(List<String> excludeAgents) { this.excludeAgents = excludeAgents; }
}
