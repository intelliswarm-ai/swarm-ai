package ai.intelliswarm.swarmai.tool.permission;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.Map;
import java.util.Objects;

/**
 * What the permission engine evaluates against. Passed to
 * {@link PermissionRules#evaluate(PermissionRequest)} and consumed by the
 * {@link PermissionRule.Specifier} matchers.
 *
 * @param toolName    the tool the agent is about to invoke (e.g. {@code "Bash"},
 *                    {@code "Read"}, {@code "WebFetch"}, {@code "MCP"})
 * @param arguments   parsed tool arguments — the matchers look up specific keys
 *                    (a {@code Bash} rule reads {@code "command"}, a
 *                    {@code Read} rule reads {@code "path"}, etc.)
 * @param mcpServer   when {@code toolName} is {@code "MCP"}, the upstream MCP
 *                    server name; otherwise {@code null}
 * @param mcpTool     when {@code toolName} is {@code "MCP"}, the upstream MCP
 *                    tool name; otherwise {@code null}
 */
@PublicApi(since = "1.0.24")
public record PermissionRequest(
        String toolName,
        Map<String, Object> arguments,
        String mcpServer,
        String mcpTool) {

    public PermissionRequest {
        Objects.requireNonNull(toolName, "toolName must not be null");
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }

    public static PermissionRequest of(String toolName, Map<String, Object> arguments) {
        return new PermissionRequest(toolName, arguments, null, null);
    }

    public static PermissionRequest mcp(String mcpServer, String mcpTool, Map<String, Object> arguments) {
        return new PermissionRequest("MCP", arguments, mcpServer, mcpTool);
    }

    /** First string argument under any of the supplied keys, or {@code null}. */
    public String firstStringArg(String... keys) {
        for (String key : keys) {
            Object v = arguments.get(key);
            if (v instanceof String s) {
                return s;
            }
        }
        return null;
    }
}
