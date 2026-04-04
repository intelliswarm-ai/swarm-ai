package ai.intelliswarm.swarmai.tool.base;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.List;
import java.util.Map;

@PublicApi(since = "1.0")
public interface BaseTool {

    String getFunctionName();

    String getDescription();

    Object execute(Map<String, Object> parameters);

    Map<String, Object> getParameterSchema();

    boolean isAsync();

    default int getMaxUsageCount() {
        return Integer.MAX_VALUE;
    }

    default boolean isCacheable() {
        return false;
    }

    /**
     * Maximum response length in characters. Tool output will be truncated to this limit
     * to prevent exceeding LLM context windows. Override to customize per tool.
     * Default: 8000 chars (~2000 tokens).
     */
    default int getMaxResponseLength() {
        return 8000;
    }

    // ==================== Permission Level ====================

    /**
     * The permission level required to use this tool.
     * Agents with a permissionMode below this level cannot invoke the tool.
     * Default: READ_ONLY (most permissive — any agent can use it).
     */
    default PermissionLevel getPermissionLevel() {
        return PermissionLevel.READ_ONLY;
    }

    // ==================== Routing (P0) ====================

    /**
     * Conditions under which this tool should be selected by the LLM.
     * Enables description-based routing for accurate tool selection.
     * Example: "User asks about financial data, stock prices, or market analysis"
     */
    default String getTriggerWhen() {
        return null;
    }

    /**
     * Conditions under which this tool should NOT be selected.
     * Prevents misuse and reduces hallucinated tool calls.
     * Example: "Question is about frontend UI or CSS styling"
     */
    default String getAvoidWhen() {
        return null;
    }

    // ==================== Requirements (P0) ====================

    /**
     * Declares runtime requirements: environment variables, binaries, services.
     * Enables pre-flight checks before assigning tools to agents.
     *
     * Supported keys:
     * - "env": List of required environment variable names (e.g., ["ALPHA_VANTAGE_API_KEY"])
     * - "bins": List of required binaries on PATH (e.g., ["python3", "ffmpeg"])
     * - "services": List of required services (e.g., ["ollama", "redis"])
     * - "os": List of supported platforms (e.g., ["linux", "darwin"])
     */
    default ToolRequirements getRequirements() {
        return ToolRequirements.NONE;
    }

    // ==================== Categories & Tags (P1) ====================

    /**
     * Category for tool discovery and filtering.
     * Examples: "data-io", "web", "computation", "analysis", "communication"
     */
    default String getCategory() {
        return "general";
    }

    /**
     * Tags for fine-grained discovery. Used alongside category for relevance scoring.
     * Examples: ["finance", "api", "file-system", "search"]
     */
    default List<String> getTags() {
        return List.of();
    }

    // ==================== Output Schema (P2) ====================

    /**
     * Describes the output format of this tool, helping downstream agents parse results
     * and enabling output validation.
     *
     * Returns a map with:
     * - "type": "string" | "json" | "markdown" | "csv" | "structured"
     * - "description": Human-readable description of the output format
     * - "sections": (optional) List of expected output sections for structured output
     * - "example": (optional) Example output snippet
     */
    default Map<String, Object> getOutputSchema() {
        return Map.of(
            "type", "string",
            "description", "Plain text output"
        );
    }

    // ==================== Smoke Test (P3) ====================

    /**
     * Quick health check to verify the tool is operational before including it
     * in an agent's toolset. Returns null if healthy, or an error message if not.
     * Should complete in under 5 seconds.
     */
    default String smokeTest() {
        return null; // healthy by default
    }
}
