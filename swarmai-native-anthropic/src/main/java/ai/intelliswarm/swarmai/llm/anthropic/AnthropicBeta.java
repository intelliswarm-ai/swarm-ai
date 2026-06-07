package ai.intelliswarm.swarmai.llm.anthropic;

import ai.intelliswarm.swarmai.api.PublicApi;

/**
 * Common Anthropic beta-header constants. Add via {@link AnthropicRequest.Builder#beta(String)}.
 *
 * <p>The wire format expects comma-separated headers; the adapter handles
 * concatenation. Values intentionally exposed as strings rather than enum so
 * forward-compatible beta names can be passed without an SDK upgrade.
 */
@PublicApi(since = "1.0.24")
public final class AnthropicBeta {

    /** Allow thinking blocks between tool calls (extended-thinking mode INTERLEAVED). */
    public static final String INTERLEAVED_THINKING = "interleaved-thinking-2025-05-14";

    /** Files API (multipart uploads, {@code container_upload}). */
    public static final String FILES_API = "files-api-2025-04-14";

    /** MCP connector (server-side MCP servers reached over HTTP). */
    public static final String MCP_CLIENT = "mcp-client-2025-11-20";

    /** Computer-use server tool. */
    public static final String COMPUTER_USE = "computer-use-2025-11-24";

    /** Advisor server tool. */
    public static final String ADVISOR_TOOL = "advisor-tool-2026-03-01";

    /** Managed Agents REST. */
    public static final String MANAGED_AGENTS = "managed-agents-2026-04-01";

    private AnthropicBeta() {}
}
