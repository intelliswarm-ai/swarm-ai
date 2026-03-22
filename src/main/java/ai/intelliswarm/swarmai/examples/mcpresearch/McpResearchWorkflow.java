package ai.intelliswarm.swarmai.examples.mcpresearch;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.OutputFormat;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.mcp.McpToolAdapter;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * MCP Research Workflow — Demonstrates external tool integration via MCP protocol.
 *
 * This example shows how SwarmAI agents can use external MCP-compatible tools
 * for real-time web fetching, search, and data retrieval — producing research
 * grounded in live data rather than LLM knowledge.
 *
 * MCP tools used:
 *   - fetch: Fetches and extracts content from web URLs (MCP Fetch server)
 *   - brave_search: Real web search via Brave Search API (MCP Brave Search server)
 *
 * Setup:
 *   1. Install MCP servers:
 *      npx -y @anthropic-ai/mcp-fetch          (runs on port 3100)
 *      npx -y @anthropic-ai/mcp-brave-search    (runs on port 3101, needs BRAVE_API_KEY)
 *
 *   2. Or use any MCP-compatible HTTP server at the configured endpoints.
 *
 *   3. Configure endpoints in .env:
 *      MCP_FETCH_URL=http://host.docker.internal:3100/mcp
 *      MCP_SEARCH_URL=http://host.docker.internal:3101/mcp
 *
 * Usage:
 *   docker compose -f docker-compose.run.yml run --rm mcp-research "impact of AI agents on enterprise software 2026"
 */
@Component
public class McpResearchWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(McpResearchWorkflow.class);

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;

    public McpResearchWorkflow(
            ChatClient.Builder chatClientBuilder,
            ApplicationEventPublisher eventPublisher) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
    }

    public void run(String... args) throws Exception {
        logger.info("Starting MCP Research Workflow");

        try {
            String query;
            if (args.length > 0) {
                query = String.join(" ", args);
            } else {
                query = "impact of AI agents on enterprise software development in 2026";
            }
            runMcpResearch(query);
        } catch (Exception e) {
            logger.error("Error running MCP research workflow", e);
            throw e;
        }
    }

    private void runMcpResearch(String query) {
        logger.info("Research query: {}", query);

        ChatClient chatClient = chatClientBuilder.build();

        // =====================================================================
        // MCP TOOLS — External tools connected via Model Context Protocol
        // =====================================================================

        // Try to connect to MCP servers via stdio transport
        // The MCP_FETCH_CMD env var specifies the command to launch the MCP fetch server
        // Default: "uvx mcp-server-fetch" (Python) or "npx @modelcontextprotocol/server-fetch"
        String mcpFetchEnabled = System.getenv("MCP_FETCH_ENABLED");

        List<BaseTool> researchTools = new ArrayList<>();
        List<String> availableTools = new ArrayList<>();

        if ("true".equalsIgnoreCase(mcpFetchEnabled)) {
            String fetchCmd = System.getenv("MCP_FETCH_CMD");
            if (fetchCmd == null || fetchCmd.isBlank()) {
                fetchCmd = "uvx";
            }
            String fetchArgs = System.getenv("MCP_FETCH_ARGS");
            if (fetchArgs == null || fetchArgs.isBlank()) {
                fetchArgs = "mcp-server-fetch";
            }

            logger.info("Connecting to MCP Fetch server: {} {}", fetchCmd, fetchArgs);
            List<BaseTool> mcpTools = McpToolAdapter.fromServer(fetchCmd, fetchArgs.split("\\s+"));
            researchTools.addAll(mcpTools);
            for (BaseTool tool : mcpTools) {
                availableTools.add(tool.getFunctionName() + " (MCP stdio)");
            }
        }

        String mcpSearchEnabled = System.getenv("MCP_SEARCH_ENABLED");
        if ("true".equalsIgnoreCase(mcpSearchEnabled)) {
            String searchCmd = System.getenv("MCP_SEARCH_CMD");
            if (searchCmd == null || searchCmd.isBlank()) {
                searchCmd = "uvx";
            }
            String searchArgs = System.getenv("MCP_SEARCH_ARGS");
            if (searchArgs == null || searchArgs.isBlank()) {
                searchArgs = "mcp-server-brave-search";
            }

            logger.info("Connecting to MCP Search server: {} {}", searchCmd, searchArgs);
            List<BaseTool> mcpTools = McpToolAdapter.fromServer(searchCmd, searchArgs.split("\\s+"));
            researchTools.addAll(mcpTools);
            for (BaseTool tool : mcpTools) {
                availableTools.add(tool.getFunctionName() + " (MCP stdio)");
            }
        }

        if (researchTools.isEmpty()) {
            logger.warn("=".repeat(60));
            logger.warn("NO MCP TOOLS CONFIGURED");
            logger.warn("=".repeat(60));
            logger.warn("Agents will use their built-in knowledge only.");
            logger.warn("For live web data, add to .env:");
            logger.warn("  MCP_FETCH_ENABLED=true");
            logger.warn("  # Requires: pip install mcp-server-fetch");
            logger.warn("=".repeat(60));
        } else {
            logger.info("MCP tools available: {}", availableTools);
        }

        // =====================================================================
        // AGENTS — Research specialists with MCP tool access
        // =====================================================================

        Agent.Builder primaryResearcherBuilder = Agent.builder()
                .role("Senior Research Analyst")
                .goal("Conduct thorough primary research on the topic using available tools. " +
                      "Search the web for recent articles, reports, and data. " +
                      "Fetch and read full content from the most relevant URLs. " +
                      "Cite every source with URL and date.")
                .backstory("You are a research analyst who excels at finding and synthesizing " +
                          "information from multiple web sources. You use search tools to find " +
                          "relevant articles, then fetch and read the full content to extract " +
                          "detailed insights. You never fabricate sources — if you can't find " +
                          "information, you say so explicitly.")
                .chatClient(chatClient)
                .verbose(true)
                .temperature(0.2)
                .modelName("gpt-4o-mini");

        // Add MCP tools to the primary researcher
        for (BaseTool tool : researchTools) {
            primaryResearcherBuilder.tool(tool);
        }

        Agent primaryResearcher = primaryResearcherBuilder.build();

        Agent analyst = Agent.builder()
                .role("Senior Analysis Specialist")
                .goal("Analyze the primary research findings to identify patterns, trends, " +
                      "and strategic implications. Create structured comparisons and assessments. " +
                      "Base ALL analysis on data from the prior research — do not invent data.")
                .backstory("You are an analyst who transforms raw research into structured insights. " +
                          "You create comparison frameworks, identify trends, and assess implications. " +
                          "You clearly distinguish between data-backed findings and your own inferences.")
                .chatClient(chatClient)
                .verbose(true)
                .temperature(0.1)
                .modelName("gpt-4o-mini")
                .build();

        Agent reportWriter = Agent.builder()
                .role("Executive Report Writer")
                .goal("Synthesize all research and analysis into a comprehensive executive report. " +
                      "Every claim must reference a specific finding from prior tasks. " +
                      "Include 5 key takeaways, each backed by a data point.")
                .backstory("You write executive-level reports that are clear, concise, and actionable. " +
                          "Every conclusion cross-references the supporting research. " +
                          "You lead with insights, not methodology.")
                .chatClient(chatClient)
                .verbose(true)
                .temperature(0.3)
                .modelName("gpt-4o-mini")
                .build();

        // =====================================================================
        // TASKS — Research pipeline with MCP tool usage
        // =====================================================================

        Task primaryResearchTask = Task.builder()
                .id("primary-research")
                .description(String.format(
                        "Conduct primary research on the following topic:\n" +
                        "\"%s\"\n\n" +
                        "REQUIRED DELIVERABLES:\n" +
                        "1. Search for 5-10 relevant sources (articles, reports, blog posts)\n" +
                        "2. For the top 3-5 most relevant results, fetch and read the full content\n" +
                        "3. Extract key data points, quotes, and findings from each source\n" +
                        "4. Compile a research brief with:\n" +
                        "   - Source inventory (title, URL, date, key finding)\n" +
                        "   - Major themes identified across sources\n" +
                        "   - Key statistics and data points found\n" +
                        "   - Notable expert opinions or predictions\n\n" +
                        "DATA RULES:\n" +
                        "- Cite every source with URL\n" +
                        "- If search tools are not available, use your knowledge but mark everything as " +
                        "[FROM KNOWLEDGE] rather than [FROM SOURCE]\n" +
                        "- If a topic has no information available, state it explicitly\n" +
                        "- Never fabricate URLs or article titles",
                        query))
                .expectedOutput("Research brief with: Source Inventory, Major Themes, Key Data Points, Expert Opinions")
                .agent(primaryResearcher)
                .outputFormat(OutputFormat.MARKDOWN)
                .maxExecutionTime(120000)
                .build();

        Task analysisTask = Task.builder()
                .id("analysis")
                .description(String.format(
                        "Analyze the primary research findings on:\n\"%s\"\n\n" +
                        "REQUIRED DELIVERABLES:\n" +
                        "1. Trend Analysis: 3-5 key trends with supporting evidence from the research\n" +
                        "2. Impact Assessment: Who is affected and how (short-term vs. long-term)\n" +
                        "3. Comparison Framework: If multiple approaches/players exist, compare them\n" +
                        "4. Opportunity Gaps: 3+ areas underserved or emerging\n" +
                        "5. Confidence Assessment: Rate data quality (HIGH/MEDIUM/LOW) for each finding\n\n" +
                        "RULES:\n" +
                        "- Base ALL analysis on data from the Primary Research task\n" +
                        "- Do NOT introduce new data not present in the research\n" +
                        "- Mark inferences clearly with [INFERENCE]",
                        query))
                .expectedOutput("Analysis with: Trends, Impact Assessment, Comparisons, Opportunity Gaps, Confidence Ratings")
                .agent(analyst)
                .dependsOn(primaryResearchTask)
                .outputFormat(OutputFormat.MARKDOWN)
                .maxExecutionTime(120000)
                .build();

        Task reportTask = Task.builder()
                .id("final-report")
                .description("Create the final executive report by synthesizing ALL prior task outputs.\n\n" +
                        "REQUIRED STRUCTURE:\n" +
                        "1. Executive Summary (max 250 words):\n" +
                        "   - 5 key takeaways, each backed by a specific data point or source\n" +
                        "   - Overall assessment in one sentence\n" +
                        "2. Research Findings (from Primary Research)\n" +
                        "3. Analysis & Trends (from Analysis task)\n" +
                        "4. Strategic Implications & Recommendations\n" +
                        "5. Sources & References (list all URLs cited)\n" +
                        "6. Data Quality Note (what was sourced live vs. from knowledge)\n\n" +
                        "RULES:\n" +
                        "- Use ONLY information from prior task outputs\n" +
                        "- Cross-reference findings between tasks\n" +
                        "- If research was based on [FROM KNOWLEDGE] rather than live sources, " +
                        "note this prominently in the Data Quality section")
                .expectedOutput("Executive report with: Summary (5 takeaways), Findings, Analysis, " +
                        "Recommendations, Sources, Data Quality")
                .agent(reportWriter)
                .dependsOn(analysisTask)
                .outputFormat(OutputFormat.MARKDOWN)
                .outputFile("mcp_research_report.md")
                .maxExecutionTime(180000)
                .build();

        // =====================================================================
        // SWARM — Sequential pipeline (research → analysis → report)
        // =====================================================================

        Swarm researchSwarm = Swarm.builder()
                .id("mcp-research")
                .agent(primaryResearcher)
                .agent(analyst)
                .agent(reportWriter)
                .task(primaryResearchTask)
                .task(analysisTask)
                .task(reportTask)
                .process(ProcessType.SEQUENTIAL)
                .verbose(true)
                .maxRpm(20)
                .eventPublisher(eventPublisher)
                .config("query", query)
                .config("mcpTools", availableTools.size())
                .build();

        // =====================================================================
        // EXECUTE
        // =====================================================================

        logger.info("=".repeat(60));
        logger.info("MCP RESEARCH WORKFLOW");
        logger.info("=".repeat(60));
        logger.info("Query: {}", query);
        logger.info("MCP Tools: {}", availableTools.isEmpty() ? "NONE (using knowledge only)" : availableTools);
        logger.info("Pipeline: Primary Research -> Analysis -> Report");
        logger.info("=".repeat(60));

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("query", query);

        long startTime = System.currentTimeMillis();
        SwarmOutput result = researchSwarm.kickoff(inputs);
        long endTime = System.currentTimeMillis();

        // =====================================================================
        // RESULTS
        // =====================================================================

        logger.info("\n" + "=".repeat(60));
        logger.info("MCP RESEARCH COMPLETE");
        logger.info("=".repeat(60));
        logger.info("Query: {}", query);
        logger.info("Duration: {} seconds", (endTime - startTime) / 1000);
        logger.info("Tasks completed: {}", result.getTaskOutputs().size());
        logger.info("MCP Tools used: {}", availableTools.isEmpty() ? "NONE" : availableTools);
        logger.info("\n{}", result.getTokenUsageSummary("gpt-4o-mini"));
        logger.info("Final Report:\n{}", result.getFinalOutput());
        logger.info("=".repeat(60));
    }
}
