package ai.intelliswarm.swarmai.examples.duediligence;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.OutputFormat;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.tool.common.CalculatorTool;
import ai.intelliswarm.swarmai.tool.common.WebSearchTool;
import ai.intelliswarm.swarmai.tool.common.SECFilingsTool;
import org.springframework.ai.chat.client.ChatClient;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Due Diligence Workflow — Comprehensive company analysis using parallel agents.
 *
 * This example is designed to showcase:
 * 1. PARALLEL PROCESS — 3 independent research streams run concurrently
 * 2. DYNAMIC CONTEXT MANAGEMENT — each agent gets optimal context budget
 * 3. Anti-hallucination guardrails — every claim must cite its source
 * 4. Token economics — full cost tracking across parallel tasks
 *
 * Workflow structure:
 *   Layer 1 (parallel):  Financial Health | News & Sentiment | Legal & Regulatory
 *   Layer 2 (sequential): Due Diligence Summary (depends on all 3)
 */
@Component
public class DueDiligenceWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(DueDiligenceWorkflow.class);

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;
    private final CalculatorTool calculatorTool;
    private final WebSearchTool webSearchTool;
    private final SECFilingsTool secFilingsTool;

    public DueDiligenceWorkflow(
            ChatClient.Builder chatClientBuilder,
            ApplicationEventPublisher eventPublisher,
            CalculatorTool calculatorTool,
            WebSearchTool webSearchTool,
            SECFilingsTool secFilingsTool) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
        this.calculatorTool = calculatorTool;
        this.webSearchTool = webSearchTool;
        this.secFilingsTool = secFilingsTool;
    }

    public void run(String... args) throws Exception {
        logger.info("Starting Due Diligence Workflow");

        try {
            String companyTicker = args.length > 0 ? args[0].toUpperCase() : "AAPL";
            runDueDiligence(companyTicker);
        } catch (Exception e) {
            logger.error("Error running due diligence workflow", e);
            throw e;
        }
    }

    private void runDueDiligence(String ticker) {
        logger.info("Due Diligence target: {}", ticker);

        ChatClient chatClient = chatClientBuilder.build();

        // =====================================================================
        // AGENTS — Specialized due diligence analysts
        // =====================================================================

        Agent ddManager = Agent.builder()
                .role("Due Diligence Program Director")
                .goal("Coordinate a rigorous due diligence investigation of " + ticker + ". " +
                      "Ensure each research stream delivers evidence-based findings with cited sources. " +
                      "Synthesize all findings into a clear investment decision framework.")
                .backstory("You are a due diligence director with 15 years at a top-tier investment bank. " +
                           "You coordinate multi-stream investigations and demand analytical rigor. " +
                           "You synthesize complex findings into clear go/no-go investment recommendations.")
                .chatClient(chatClient)
                .verbose(true)
                .allowDelegation(true)
                .temperature(0.2)
                .modelName("gpt-4o-mini")
                .build();

        Agent financialAnalyst = Agent.builder()
                .role("Senior Financial Due Diligence Analyst")
                .goal("Analyze " + ticker + "'s financial health from SEC filings. " +
                      "Extract revenue, margins, debt levels, cash flow, and red flags. " +
                      "Every number must cite the specific filing and date.")
                .backstory("You are a CPA and CFA with 10 years in financial due diligence. " +
                           "You specialize in detecting financial irregularities, aggressive accounting, " +
                           "and hidden liabilities in SEC filings. You never estimate — you extract and cite.")
                .chatClient(chatClient)
                .tool(calculatorTool)
                .tool(secFilingsTool)
                .verbose(true)
                .temperature(0.1)
                .modelName("gpt-4o-mini")
                .build();

        Agent newsAnalyst = Agent.builder()
                .role("Senior Market Intelligence Analyst")
                .goal("Research recent news, analyst opinions, and market sentiment about " + ticker + ". " +
                      "Identify material events, management changes, lawsuits, and market positioning. " +
                      "Cite the source and date for every claim.")
                .backstory("You are a market intelligence analyst with 8 years covering public companies. " +
                           "You identify material non-public-filing events: management changes, lawsuits, " +
                           "partnerships, product launches. You clearly distinguish facts from speculation.")
                .chatClient(chatClient)
                .tool(webSearchTool)
                .verbose(true)
                .temperature(0.2)
                .modelName("gpt-4o-mini")
                .build();

        Agent legalAnalyst = Agent.builder()
                .role("Senior Legal & Regulatory Analyst")
                .goal("Assess legal and regulatory risks for " + ticker + ". " +
                      "Review SEC filing disclosures for litigation, regulatory actions, " +
                      "insider transactions, and compliance issues. Cite every finding.")
                .backstory("You are a JD with 8 years in corporate law specializing in securities regulation. " +
                           "You review risk factor disclosures, legal proceeding sections, and insider " +
                           "transaction filings (Form 3, Form 4). You flag material legal risks precisely.")
                .chatClient(chatClient)
                .tool(secFilingsTool)
                .tool(webSearchTool)
                .verbose(true)
                .temperature(0.1)
                .modelName("gpt-4o-mini")
                .build();

        // =====================================================================
        // TASKS — Three independent research streams + synthesis
        //
        // KEY: Financial, News, and Legal tasks have NO dependencies between them.
        //      With ProcessType.PARALLEL, they would run concurrently.
        //      Currently runs sequentially — the parallel process will fix this.
        // =====================================================================

        Task financialTask = Task.builder()
                .id("financial-health")
                .description(String.format(
                        "Conduct financial due diligence on %s using SEC filings.\n\n" +
                        "REQUIRED ANALYSIS:\n" +
                        "1. Revenue & Profitability: Last 2-3 reported periods with YoY trends\n" +
                        "2. Balance Sheet Health: Total assets, liabilities, debt-to-equity ratio\n" +
                        "3. Cash Flow: Operating cash flow, free cash flow, burn rate if negative\n" +
                        "4. Red Flags: Aggressive revenue recognition, related-party transactions, " +
                        "auditor changes, going concern opinions\n" +
                        "5. Capital Structure: Dilution risk, convertible notes, warrants outstanding\n\n" +
                        "DATA RULES:\n" +
                        "- Use ONLY data from SEC filings (use the sec_filings tool)\n" +
                        "- Cite every number with filing type, date, and accession number\n" +
                        "- If data is unavailable, write 'DATA NOT AVAILABLE: [item]'\n" +
                        "- Flag any data quality concerns explicitly",
                        ticker))
                .expectedOutput("Financial Due Diligence Report with sections:\n" +
                        "Revenue & Profitability, Balance Sheet, Cash Flow, Red Flags, Capital Structure, Data Gaps")
                .agent(financialAnalyst)
                .outputFormat(OutputFormat.MARKDOWN)
                .maxExecutionTime(180000)
                .build();

        Task newsTask = Task.builder()
                .id("news-sentiment")
                .description(String.format(
                        "Research recent news and market sentiment about %s.\n\n" +
                        "REQUIRED ANALYSIS:\n" +
                        "1. Material Events: 5-7 most significant recent events with dates and sources\n" +
                        "2. Management & Governance: CEO/CFO changes, board actions, insider transactions\n" +
                        "3. Market Sentiment: Bull vs. bear arguments with supporting evidence\n" +
                        "4. Competitive Position: How the company is positioned vs. peers\n" +
                        "5. Upcoming Catalysts: Earnings dates, regulatory decisions, product launches\n\n" +
                        "DATA RULES:\n" +
                        "- Use the web_search tool to find recent information\n" +
                        "- Cite source and date for every claim\n" +
                        "- If no news is found, state 'NO RECENT NEWS FOUND' explicitly\n" +
                        "- Distinguish between [CONFIRMED] facts and [SPECULATION]",
                        ticker))
                .expectedOutput("News & Sentiment Report with sections:\n" +
                        "Material Events, Management & Governance, Market Sentiment, Competitive Position, Catalysts")
                .agent(newsAnalyst)
                .outputFormat(OutputFormat.MARKDOWN)
                .maxExecutionTime(120000)
                .build();

        Task legalTask = Task.builder()
                .id("legal-regulatory")
                .description(String.format(
                        "Assess legal and regulatory risks for %s.\n\n" +
                        "REQUIRED ANALYSIS:\n" +
                        "1. Litigation: Active lawsuits, settlements, class actions from SEC filings\n" +
                        "2. Regulatory Actions: SEC inquiries, FDA actions, EPA violations, etc.\n" +
                        "3. Insider Transactions: Recent Form 3/4 filings — buying vs. selling patterns\n" +
                        "4. Risk Factor Changes: New or modified risk factors vs. prior filings\n" +
                        "5. Compliance: Any disclosure of material weaknesses in internal controls\n\n" +
                        "DATA RULES:\n" +
                        "- Use sec_filings and web_search tools for data\n" +
                        "- Cite the specific filing section (e.g., 'Item 1A - Risk Factors, 20-F 2025-04-29')\n" +
                        "- If no legal issues found, state 'NO MATERIAL LEGAL ISSUES IDENTIFIED'\n" +
                        "- Rate overall legal risk as LOW / MEDIUM / HIGH with justification",
                        ticker))
                .expectedOutput("Legal & Regulatory Report with sections:\n" +
                        "Litigation, Regulatory Actions, Insider Transactions, Risk Factor Changes, " +
                        "Compliance, Overall Legal Risk Rating")
                .agent(legalAnalyst)
                .outputFormat(OutputFormat.MARKDOWN)
                .maxExecutionTime(180000)
                .build();

        // Synthesis task — depends on ALL three research streams
        Task synthesisTask = Task.builder()
                .id("dd-synthesis")
                .description("Synthesize ALL prior due diligence findings into a final investment assessment.\n\n" +
                        "REQUIRED:\n" +
                        "1. Executive Summary: 1-paragraph verdict with GO / CAUTION / NO-GO rating\n" +
                        "2. Financial Assessment: Key findings from the financial analysis (cross-reference)\n" +
                        "3. Market Assessment: Key findings from news & sentiment (cross-reference)\n" +
                        "4. Legal Assessment: Key findings from legal analysis (cross-reference)\n" +
                        "5. Risk Matrix: Top 5 risks ranked by likelihood and impact\n" +
                        "6. Investment Decision: GO / CAUTION / NO-GO with confidence level (HIGH/MEDIUM/LOW)\n" +
                        "7. Data Quality Assessment: What data was available vs. what was missing\n\n" +
                        "RULES:\n" +
                        "- Reference specific findings from each prior task\n" +
                        "- Do NOT introduce new information not present in prior task outputs\n" +
                        "- If prior tasks reported data gaps, factor that into your confidence level\n" +
                        "- Be direct: state GO, CAUTION, or NO-GO clearly in the first sentence")
                .expectedOutput("Due Diligence Summary with:\n" +
                        "Executive Summary (GO/CAUTION/NO-GO), Financial/Market/Legal Assessments, " +
                        "Risk Matrix, Investment Decision with confidence, Data Quality Assessment")
                .agent(ddManager)
                .outputFormat(OutputFormat.MARKDOWN)
                .outputFile("output/due_diligence_report.md")
                .maxExecutionTime(240000)
                .dependsOn(financialTask)
                .dependsOn(newsTask)
                .dependsOn(legalTask)
                .build();

        // =====================================================================
        // SWARM — Uses PARALLEL process type (falls back to SEQUENTIAL until implemented)
        // =====================================================================

        Swarm ddSwarm = Swarm.builder()
                .id("due-diligence-" + ticker.toLowerCase())
                .agent(financialAnalyst)
                .agent(newsAnalyst)
                .agent(legalAnalyst)
                .task(financialTask)
                .task(newsTask)
                .task(legalTask)
                .task(synthesisTask)
                .process(ProcessType.PARALLEL)
                .verbose(true)
                .maxRpm(20)
                .language("en")
                .eventPublisher(eventPublisher)
                .config("analysisType", "due-diligence")
                .config("ticker", ticker)
                .build();

        // =====================================================================
        // EXECUTE
        // =====================================================================

        logger.info("=".repeat(60));
        logger.info("DUE DILIGENCE: {}", ticker);
        logger.info("=".repeat(60));
        logger.info("Research streams: Financial Health | News & Sentiment | Legal & Regulatory");
        logger.info("Process: {}", ddSwarm.getProcessType());
        logger.info("Agents: DD Director + 3 Specialist Analysts");
        logger.info("=".repeat(60));

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("ticker", ticker);
        inputs.put("analysis_type", "due_diligence");

        long startTime = System.currentTimeMillis();
        SwarmOutput result = ddSwarm.kickoff(inputs);
        long endTime = System.currentTimeMillis();

        // =====================================================================
        // RESULTS
        // =====================================================================

        logger.info("\n" + "=".repeat(60));
        logger.info("DUE DILIGENCE COMPLETE: {}", ticker);
        logger.info("=".repeat(60));
        logger.info("Duration: {} seconds", (endTime - startTime) / 1000);
        logger.info("Tasks completed: {}", result.getTaskOutputs().size());
        logger.info("\n{}", result.getTokenUsageSummary("gpt-4o-mini"));

        // Show per-stream results
        for (var taskOutput : result.getTaskOutputs()) {
            String desc = taskOutput.getDescription();
            if (desc != null && desc.length() > 50) desc = desc.substring(0, 47) + "...";
            logger.info("Stream [{}]: {} chars, {} tokens",
                    desc,
                    taskOutput.getRawOutput() != null ? taskOutput.getRawOutput().length() : 0,
                    taskOutput.getTotalTokens() != null ? taskOutput.getTotalTokens() : 0);
        }

        logger.info("\nFinal Due Diligence Report:\n{}", result.getFinalOutput());
        logger.info("=".repeat(60));
    }
}
