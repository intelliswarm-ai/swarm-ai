package ai.intelliswarm.swarmai.examples.competitive;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.OutputFormat;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.ToolHealthChecker;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Competitive Research Swarm - Showcase Example
 *
 * Demonstrates the SwarmCoordinator with parallel self-improving agents
 * for competitive research analysis using custom target parsing.
 *
 * Flow:
 * 1. DISCOVERY: A planner agent identifies the key companies/competitors to analyze
 * 2. FAN-OUT: SwarmCoordinator spawns parallel self-improving agents (one per company)
 * 3. PER-COMPANY: Each agent researches financials, products, news, market position,
 *    generating CODE skills as needed (e.g., revenue parser, market share calculator)
 * 4. SKILLS SHARED: A CODE skill generated for one company (e.g., SEC filing parser)
 *    is immediately available to other parallel agents via the shared SkillRegistry
 * 5. SYNTHESIS: All per-company reports merge into a comprehensive competitive strategy report
 */
@Component
public class CompetitiveResearchSwarm {

    private static final Logger logger = LoggerFactory.getLogger(CompetitiveResearchSwarm.class);

    private final ChatClient chatClient;
    private final ApplicationEventPublisher eventPublisher;
    private final List<BaseTool> tools;

    public CompetitiveResearchSwarm(
            ChatClient.Builder chatClientBuilder,
            ApplicationEventPublisher eventPublisher,
            List<BaseTool> tools) {
        this.chatClient = chatClientBuilder.build();
        this.eventPublisher = eventPublisher;
        this.tools = tools;
    }

    public void run(String... args) {
        String query = args.length > 0 ? String.join(" ", args)
                : "Analyze the top 5 cloud providers AWS vs Azure vs GCP vs Oracle Cloud vs IBM Cloud";

        logger.info("\n" + "=".repeat(80));
        logger.info("COMPETITIVE RESEARCH SWARM");
        logger.info("=".repeat(80));
        logger.info("Query: {}", query);
        logger.info("Process: SWARM (discover competitors -> parallel analysis per company -> synthesize)");
        logger.info("=".repeat(80));

        // =====================================================================
        // TOOLS - select research-relevant tools
        // =====================================================================
        List<BaseTool> researchTools = tools.stream()
                .filter(t -> {
                    String name = t.getFunctionName();
                    return name.equals("http_request") || name.equals("web_scrape") ||
                           name.equals("calculator") || name.equals("file_write") ||
                           name.equals("json_transform") || name.equals("csv_analysis") ||
                           name.equals("shell_command");
                })
                .collect(Collectors.toList());

        List<BaseTool> healthyTools = ToolHealthChecker.filterOperational(researchTools);
        logger.info("Healthy tools: {}", healthyTools.stream()
                .map(BaseTool::getFunctionName).collect(Collectors.joining(", ")));

        // =====================================================================
        // AGENTS
        // =====================================================================

        // Research analyst - cloned per company by SwarmCoordinator
        Agent researchAnalyst = Agent.builder()
                .role("Senior Market Research Analyst")
                .goal("Conduct deep competitive analysis on the assigned company. " +
                      "Use http_request to fetch real data from Wikipedia API, company websites, " +
                      "and financial data sources. Use calculator for financial metrics. " +
                      "Generate CODE skills for any repeatable data processing (revenue parsing, " +
                      "market share calculation, product feature extraction). " +
                      "Write all output files to /app/output/.")
                .backstory("You are a senior analyst at a top-tier strategy consulting firm. " +
                           "You always back claims with real data from reliable sources. " +
                           "You use the Wikipedia API (https://en.wikipedia.org/api/rest_v1/page/summary/TOPIC) " +
                           "and other public APIs to gather factual data. " +
                           "You never fabricate financial figures.")
                .chatClient(chatClient)
                .tools(healthyTools)
                .verbose(true)
                .temperature(0.2)
                .modelName("gpt-4o-mini")
                .build();

        // Report writer - synthesizes all company analyses
        Agent reportWriter = Agent.builder()
                .role("Chief Strategy Officer")
                .goal("Write a comprehensive competitive landscape report combining analyses of ALL companies. " +
                      "Include: Executive Summary, Company Profiles (with financials), Product Comparison Matrix, " +
                      "Market Share Analysis, SWOT per company, Strategic Recommendations, and Appendices.")
                .backstory("You are a CSO who writes board-level strategy reports. " +
                           "Your reports are data-driven, include comparison tables, and end with actionable recommendations.")
                .chatClient(chatClient)
                .verbose(true)
                .temperature(0.3)
                .modelName("gpt-4o-mini")
                .build();

        // Reviewer - drives deeper analysis with NEXT_COMMANDS
        Agent reviewer = Agent.builder()
                .role("Research Quality Director")
                .goal("Review the competitive analysis output. Ensure each company has: " +
                      "revenue/market cap data, product offerings, market position, and competitive advantages. " +
                      "When data is missing, provide NEXT_COMMANDS with specific http_request URLs to fetch it.\n\n" +
                      "NEXT_COMMANDS RULES:\n" +
                      "- Use Wikipedia API: http_request with url=https://en.wikipedia.org/api/rest_v1/page/summary/Company_Name\n" +
                      "- Use web_scrape for company pages\n" +
                      "- Suggest calculator for financial metric computation\n" +
                      "- Maximum 5 commands per review")
                .backstory("You ensure research quality. When financial data or product details are missing, " +
                           "you prescribe specific API calls and web scrapes to fill the gaps.")
                .chatClient(chatClient)
                .verbose(true)
                .temperature(0.1)
                .modelName("gpt-4.1")
                .build();

        // =====================================================================
        // TASKS
        // =====================================================================

        // Discovery task - identifies which companies to analyze
        Task discoveryTask = Task.builder()
                .description("Identify the key competitors to analyze from this query: \"" + query + "\"\n" +
                    "Use http_request to verify each company exists by fetching their Wikipedia summary.\n" +
                    "For each company, output ONE LINE per company in this exact format:\n" +
                    "COMPETITOR: Company Name (Ticker: SYMBOL)\n" +
                    "Example:\n" +
                    "COMPETITOR: Amazon Web Services (Ticker: AMZN)\n" +
                    "COMPETITOR: Microsoft Azure (Ticker: MSFT)\n\n" +
                    "IMPORTANT: Output the COMPETITOR: lines clearly. The system uses these to fan out parallel agents.")
                .expectedOutput("List of competitors with names and tickers")
                .agent(researchAnalyst)
                .maxExecutionTime(120000)
                .build();

        // Per-company analysis task template (SwarmCoordinator uses this as template)
        Task analysisTask = Task.builder()
                .description("Perform deep competitive analysis on the assigned company.\n" +
                    "1. Fetch company overview from Wikipedia API: " +
                    "http_request url=https://en.wikipedia.org/api/rest_v1/page/summary/COMPANY\n" +
                    "2. Research their products, services, and market position\n" +
                    "3. Gather financial data (revenue, market cap, growth)\n" +
                    "4. Identify competitive advantages and weaknesses\n" +
                    "5. Compare with known competitors\n\n" +
                    "Save findings to /app/output/analysis_COMPANY.md")
                .expectedOutput("Comprehensive company analysis with financials and competitive positioning")
                .agent(researchAnalyst)
                .maxExecutionTime(300000)
                .build();

        // Synthesis report task
        Task reportTask = Task.builder()
                .description("Write a comprehensive competitive landscape report combining ALL company analyses.\n" +
                    "Sections:\n" +
                    "1. Executive Summary (2-3 paragraphs)\n" +
                    "2. Market Overview (total market size, growth trends)\n" +
                    "3. Company Profiles (table: company, revenue, market cap, key products, market share)\n" +
                    "4. Product Comparison Matrix (features vs companies)\n" +
                    "5. SWOT Analysis per company\n" +
                    "6. Strategic Recommendations\n" +
                    "7. Appendices (data sources, methodology)")
                .expectedOutput("Professional competitive landscape report in markdown")
                .agent(reportWriter)
                .dependsOn(discoveryTask)
                .outputFormat(OutputFormat.MARKDOWN)
                .outputFile("output/competitive_landscape_report.md")
                .maxExecutionTime(180000)
                .build();

        // =====================================================================
        // EXECUTE SWARM
        // =====================================================================

        Swarm swarm = Swarm.builder()
                .id("competitive-research-swarm")
                .agent(researchAnalyst)
                .agent(reportWriter)
                .managerAgent(reviewer)
                .task(discoveryTask)
                .task(analysisTask)
                .task(reportTask)
                .process(ProcessType.SWARM)
                .config("maxIterations", 5)
                .config("maxParallelAgents", 5)
                .config("targetPrefix", "COMPETITOR:")
                .config("qualityCriteria",
                    "1. Each company has real financial data (revenue, market cap) with sources cited\n" +
                    "2. Product comparison includes specific features and pricing tiers\n" +
                    "3. SWOT analysis is company-specific, not generic\n" +
                    "4. Recommendations are actionable and backed by the analysis\n" +
                    "5. All data from API calls, not LLM knowledge")
                .verbose(true)
                .eventPublisher(eventPublisher)
                .build();

        long startTime = System.currentTimeMillis();
        SwarmOutput result = swarm.kickoff(Map.of("query", query));
        long duration = (System.currentTimeMillis() - startTime) / 1000;

        // =====================================================================
        // RESULTS
        // =====================================================================

        logger.info("\n" + "=".repeat(80));
        logger.info("COMPETITIVE RESEARCH SWARM COMPLETE");
        logger.info("=".repeat(80));
        logger.info("Query: {}", query);
        logger.info("Duration: {} seconds", duration);
        logger.info("Tasks completed: {}", result.getTaskOutputs().size());
        logger.info("Skills generated: {}", result.getMetadata().getOrDefault("skillsGenerated", 0));
        logger.info("Skills reused: {}", result.getMetadata().getOrDefault("skillsReused", 0));

        // Token usage
        logger.info("\nToken Usage:\n{}", result.getTokenUsageSummary("gpt-4.1"));

        // Final report
        String report = result.getTaskOutputs().stream()
                .map(TaskOutput::getRawOutput)
                .filter(Objects::nonNull)
                .reduce((a, b) -> b)
                .orElse("(no report generated)");
        logger.info("\nFinal Report:\n{}", report);
        logger.info("=".repeat(80));
    }
}
