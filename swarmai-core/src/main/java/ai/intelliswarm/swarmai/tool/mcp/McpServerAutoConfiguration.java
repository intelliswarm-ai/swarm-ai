package ai.intelliswarm.swarmai.tool.mcp;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Spring Boot auto-configuration that publishes the user's {@link BaseTool}
 * beans as an MCP server.
 *
 * <p>Off by default. Enable in application.yml:
 * <pre>
 *   swarmai:
 *     mcp:
 *       server:
 *         enabled: true
 *         name: my-jira-toolkit
 *         version: 1.0
 *         instructions: "Tools for interacting with company Jira"
 *         includeTools: [jira, slack_webhook]   # optional whitelist
 * </pre>
 *
 * <p>Then point Claude Desktop / Cursor / Cline at the launched JAR via stdio:
 * <pre>
 *   {
 *     "mcpServers": {
 *       "my-jira-toolkit": {
 *         "command": "java",
 *         "args": ["-jar", "/path/to/your-app.jar", "--swarmai.mcp.server.enabled=true"]
 *       }
 *     }
 *   }
 * </pre>
 *
 * <p>The {@code @ConditionalOnClass} guard means the auto-config is silently
 * skipped if a consumer hasn't pulled in the MCP SDK transitively (the same
 * {@code <optional>true</optional>} pattern we use for the rest of the
 * tools layer).
 */
@Configuration
@ConditionalOnClass(name = "io.modelcontextprotocol.server.McpServer")
@ConditionalOnProperty(prefix = "swarmai.mcp.server", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(McpServerProperties.class)
public class McpServerAutoConfiguration {

    @Bean(destroyMethod = "stop")
    public McpServerHost mcpServerHost(McpServerProperties props,
                                       List<BaseTool> tools,
                                       List<Agent> agents) {
        return new McpServerHost(props, tools, agents);
    }
}
