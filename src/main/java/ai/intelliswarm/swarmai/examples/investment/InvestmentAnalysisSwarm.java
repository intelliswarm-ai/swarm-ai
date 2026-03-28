package ai.intelliswarm.swarmai.examples.investment;

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
 * Multi-Company Investment Analysis Swarm — showcases the full SwarmAI platform.
 *
 * Given a query like "Compare AAPL, MSFT, GOOGL, NVDA for investment", this workflow:
 * 1. DISCOVERS tickers from the query (planner agent)
 * 2. FANS OUT parallel self-improving agents (one per company)
 * 3. Each agent: fetches SEC filings, Wikipedia data, financial metrics
 * 4. Generates CODE skills at runtime (P/E calculator, revenue parser, earnings analyzer)
 * 5. Skills are SHARED — a skill generated for AAPL is immediately used by MSFT, GOOGL, etc.
 * 6. Reviewer drives deeper analysis with NEXT_COMMANDS (specific API URLs to fetch)
 * 7. SYNTHESIZES all per-company analyses into a professional investment memo
 * 8. Scan/research results are CACHED for future runs
 *
 * This demonstrates:
 * - SwarmCoordinator (SWARM process type) with parallel fan-out
 * - Self-improving CODE skill generation with formal verification
 * - Reviewer-driven task evolution with NEXT_COMMANDS
 * - Command ledger preventing redundant API calls
 * - Cross-agent skill sharing via shared SkillRegistry
 */
@Component
public class InvestmentAnalysisSwarm {

    private static final Logger logger = LoggerFactory.getLogger(InvestmentAnalysisSwarm.class);

    private final ChatClient chatClient;
    private final ApplicationEventPublisher eventPublisher;
    private final List<BaseTool> tools;

    public InvestmentAnalysisSwarm(
            ChatClient.Builder chatClientBuilder,
            ApplicationEventPublisher eventPublisher,
            List<BaseTool> tools) {
        this.chatClient = chatClientBuilder.build();
        this.eventPublisher = eventPublisher;
        this.tools = tools;
    }

    public void run(String... args) {
        String query = args.length > 0
            ? String.join(" ", args)
            : "Compare AAPL, MSFT, GOOGL, NVDA, TSLA for investment — analyze financials, competitive position, and provide buy/hold/sell recommendations";

        logger.info("\n" + "=".repeat(80));
        logger.info("INVESTMENT ANALYSIS SWARM");
        logger.info("=".repeat(80));
        logger.info("Query: {}", query);
        logger.info("Process: SWARM (discover tickers → parallel self-improving agent per company → investment memo)");
        logger.info("Features: CODE skill generation, skill sharing, reviewer NEXT_COMMANDS, command ledger, scan cache");
        logger.info("=".repeat(80));

        // =====================================================================
        // PHASE 1: SELECT & HEALTH-CHECK TOOLS
        // =====================================================================

        List<BaseTool> researchTools = tools.stream()
            .filter(t -> {
                String name = t.getFunctionName();
                return name.equals("browse") || name.equals("http_request") ||
                       name.equals("web_scrape") || name.equals("calculator") ||
                       name.equals("file_write") || name.equals("file_read") ||
                       name.equals("json_transform") || name.equals("csv_analysis") ||
                       name.equals("sec_filings") || name.equals("shell_command");
            })
            .collect(Collectors.toList());

        List<BaseTool> healthyTools = ToolHealthChecker.filterOperational(researchTools);
        logger.info("Healthy tools: {}", healthyTools.stream()
            .map(BaseTool::getFunctionName).collect(Collectors.joining(", ")));

        // =====================================================================
        // PHASE 2: CREATE AGENTS
        // =====================================================================

        // Financial analyst — cloned per company by SwarmCoordinator
        Agent financialAnalyst = Agent.builder()
            .role("Senior Financial Analyst")
            .goal("Conduct deep investment analysis on the assigned company. " +
                  "Use the 'browse' tool to fetch REAL data from Yahoo Finance (https://finance.yahoo.com/quote/TICKER), " +
                  "Google Finance (https://www.google.com/finance/quote/TICKER:NASDAQ), " +
                  "and company investor relations pages. " +
                  "Use calculator for financial metrics (P/E ratio, revenue growth, margins). " +
                  "Generate CODE skills for reusable financial calculations. " +
                  "Always cite data sources. Write analysis to /app/output/.")
            .backstory("You are a CFA-certified financial analyst at a top-tier investment bank. " +
                       "You ALWAYS use real data — never fabricate financial figures. " +
                       "Your PRIMARY tool is 'browse' (headless browser) for scraping:\n" +
                       "- Yahoo Finance: browse url=https://finance.yahoo.com/quote/TICKER\n" +
                       "- Google Finance: browse url=https://www.google.com/finance/quote/TICKER:NASDAQ\n" +
                       "- SEC EDGAR: browse url=https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=TICKER&type=10-K\n" +
                       "- Company IR pages: browse url=https://investor.COMPANY.com\n" +
                       "You generate CODE skills when you find yourself repeating calculations " +
                       "(P/E ratios, revenue growth rates, margin analysis).")
            .chatClient(chatClient)
            .tools(healthyTools)
            .verbose(true)
            .temperature(0.2)
            .modelName("gpt-4o-mini")
            .build();

        // Investment memo writer — synthesizes all company analyses
        Agent memoWriter = Agent.builder()
            .role("Chief Investment Officer")
            .goal("Write a comprehensive investment memo combining analyses of ALL companies. " +
                  "Include: Executive Summary, Per-Company Analysis (with financial tables), " +
                  "Comparative Metrics Table, Risk Analysis, Portfolio Recommendations " +
                  "(Buy/Hold/Sell with confidence levels), and Appendices.")
            .backstory("You are a CIO writing board-level investment memos. " +
                       "Your memos are data-driven with comparison tables, include confidence levels " +
                       "for each recommendation, and clearly distinguish [CONFIRMED] data from [ESTIMATE].")
            .chatClient(chatClient)
            .verbose(true)
            .temperature(0.3)
            .modelName("gpt-4o-mini")
            .build();

        // Reviewer — drives deeper analysis with NEXT_COMMANDS
        Agent reviewer = Agent.builder()
            .role("Investment Research Director")
            .goal("Review the investment analysis output. Ensure each company has: " +
                  "revenue, net income, P/E ratio, market cap, revenue growth, and competitive positioning. " +
                  "When financial data is missing, provide NEXT_COMMANDS with specific API URLs.\n\n" +
                  "NEXT_COMMANDS RULES:\n" +
                  "- For financial data: browse url=https://finance.yahoo.com/quote/TICKER\n" +
                  "- For detailed financials: browse url=https://finance.yahoo.com/quote/TICKER/financials\n" +
                  "- For SEC filings: use sec_filings tool or browse url=https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=TICKER&type=10-K\n" +
                  "- For calculations: use calculator with specific formulas (e.g., PE = price / EPS)\n" +
                  "- Maximum 5 commands per review\n\n" +
                  "CAPABILITY_GAPS: Only flag if a CODE skill would enable reusable financial computation " +
                  "(e.g., a P/E ratio calculator, a revenue growth analyzer, a margin comparison tool). " +
                  "Do NOT flag gaps for things the LLM already knows.")
            .backstory("You are an investment research director who ensures analytical rigor. " +
                       "You always push for real financial data over LLM estimates. " +
                       "When you see [ESTIMATE] tags, you prescribe API calls to replace them with [CONFIRMED] data.")
            .chatClient(chatClient)
            .verbose(true)
            .temperature(0.1)
            .modelName("gpt-4.1")
            .build();

        // =====================================================================
        // PHASE 3: CREATE TASKS
        // =====================================================================

        // Discovery task — identifies which companies/tickers to analyze
        Task discoveryTask = Task.builder()
            .description("Identify the companies to analyze from this query: \"" + query + "\"\n\n" +
                "For each company, verify it exists by browsing its Yahoo Finance page:\n" +
                "browse url=https://finance.yahoo.com/quote/TICKER\n\n" +
                "Output ONE LINE per company in this EXACT format:\n" +
                "TICKER: SYMBOL — Company Full Name\n\n" +
                "Example output:\n" +
                "TICKER: AAPL — Apple Inc.\n" +
                "TICKER: MSFT — Microsoft Corporation\n" +
                "TICKER: GOOGL — Alphabet Inc.\n\n" +
                "IMPORTANT: The lines starting with 'TICKER:' are parsed by the system to fan out parallel agents.")
            .expectedOutput("List of tickers with company names, one per line")
            .agent(financialAnalyst)
            .maxExecutionTime(120000)
            .build();

        // Per-company analysis task template (SwarmCoordinator clones per target)
        Task analysisTask = Task.builder()
            .description("Perform deep investment analysis on the assigned company.\n\n" +
                "Step 1 — Company Overview & Financials (use browse tool):\n" +
                "  browse url=https://finance.yahoo.com/quote/TICKER\n" +
                "  browse url=https://finance.yahoo.com/quote/TICKER/financials\n" +
                "  Extract: Revenue, Net Income, EPS, Market Cap, Stock Price\n\n" +
                "Step 2 — SEC Filings:\n" +
                "  Use sec_filings tool or browse url=https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=TICKER&type=10-K\n" +
                "  Extract: Revenue, Net Income, EPS, Total Assets, Total Debt\n\n" +
                "Step 3 — Financial Metrics (use calculator):\n" +
                "  P/E Ratio = Stock Price / EPS\n" +
                "  Revenue Growth = (Current Revenue - Prior Revenue) / Prior Revenue * 100\n" +
                "  Net Margin = Net Income / Revenue * 100\n" +
                "  Debt-to-Equity = Total Debt / (Total Assets - Total Debt)\n\n" +
                "Step 4 — Competitive Position:\n" +
                "  Market position, key products, competitive advantages, threats\n\n" +
                "Step 5 — Investment Thesis:\n" +
                "  Buy/Hold/Sell recommendation with confidence level and reasoning\n\n" +
                "Save analysis to /app/output/analysis_TICKER.md\n" +
                "Mark all data as [CONFIRMED] (from API) or [ESTIMATE] (from LLM knowledge)")
            .expectedOutput("Comprehensive financial analysis with metrics and recommendation")
            .agent(financialAnalyst)
            .maxExecutionTime(300000)
            .build();

        // Synthesis report task
        Task reportTask = Task.builder()
            .description("Write a professional investment memo combining ALL company analyses.\n\n" +
                "Required sections:\n" +
                "1. Executive Summary (2-3 paragraphs with top-line recommendations)\n" +
                "2. Market Context (macro trends affecting all companies)\n" +
                "3. Per-Company Analysis (for each: overview, financials table, competitive position, recommendation)\n" +
                "4. Comparative Metrics Table:\n" +
                "   | Company | Revenue | Net Income | P/E | Revenue Growth | Net Margin | Recommendation |\n" +
                "5. Risk Analysis (systematic risks, company-specific risks)\n" +
                "6. Portfolio Recommendations (allocation percentages, rebalancing suggestions)\n" +
                "7. Appendices (data sources, methodology, confidence levels)\n\n" +
                "Use [CONFIRMED] and [ESTIMATE] tags throughout.")
            .expectedOutput("Professional investment memo in markdown")
            .agent(memoWriter)
            .dependsOn(discoveryTask)
            .outputFormat(OutputFormat.MARKDOWN)
            .outputFile("output/investment_memo.md")
            .maxExecutionTime(180000)
            .build();

        // =====================================================================
        // PHASE 4: EXECUTE SWARM
        // =====================================================================

        Swarm swarm = Swarm.builder()
            .id("investment-analysis-swarm")
            .agent(financialAnalyst)
            .agent(memoWriter)
            .managerAgent(reviewer)
            .task(discoveryTask)
            .task(analysisTask)
            .task(reportTask)
            .process(ProcessType.SWARM)
            .config("maxIterations", 5)
            .config("maxParallelAgents", 5)
            .config("targetPrefix", "TICKER:")
            .config("qualityCriteria",
                "1. Each company has revenue, net income, and P/E ratio with [CONFIRMED] or [ESTIMATE] tags\n" +
                "2. Financial metrics are calculated (not just stated) using real data\n" +
                "3. Competitive positioning is specific to each company, not generic\n" +
                "4. Buy/Hold/Sell recommendations include confidence levels\n" +
                "5. Comparative metrics table includes ALL companies analyzed")
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
        logger.info("INVESTMENT ANALYSIS SWARM COMPLETE");
        logger.info("=".repeat(80));
        logger.info("Query: {}", query);
        logger.info("Duration: {} seconds", duration);
        logger.info("Tasks completed: {}", result.getTaskOutputs().size());
        logger.info("Skills generated: {}", result.getMetadata().getOrDefault("skillsGenerated", 0));
        logger.info("Skills reused: {}", result.getMetadata().getOrDefault("skillsReused", 0));

        if (result.getMetadata().containsKey("registryStats")) {
            logger.info("Skill Registry: {}", result.getMetadata().get("registryStats"));
        }

        // Token usage
        logger.info("\nToken Usage:\n{}", result.getTokenUsageSummary("gpt-4.1"));

        // Final report
        String report = result.getTaskOutputs().stream()
            .map(TaskOutput::getRawOutput)
            .filter(Objects::nonNull)
            .reduce((a, b) -> b)
            .orElse("(no report generated)");
        logger.info("\nFinal Investment Memo:\n{}", report);
        logger.info("=".repeat(80));
    }
}
