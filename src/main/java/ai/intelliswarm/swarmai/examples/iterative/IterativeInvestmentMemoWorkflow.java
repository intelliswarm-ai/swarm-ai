package ai.intelliswarm.swarmai.examples.iterative;

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
 * Iterative Investment Memo Workflow
 *
 * Demonstrates the ITERATIVE process type with a real-world use case:
 * producing an institutional-quality investment memo through iterative refinement.
 *
 * This example showcases:
 *   1. ITERATIVE PROCESS - Tasks execute, get reviewed, receive feedback, and re-execute
 *   2. REVIEWER-DRIVEN QUALITY GATES - A Managing Director agent reviews each iteration
 *      against an explicit rubric and provides specific, actionable feedback
 *   3. FEEDBACK-DRIVEN IMPROVEMENT - Each iteration receives the reviewer's feedback
 *      as additional context, resulting in measurably better output
 *   4. TOOL-AUGMENTED RESEARCH - Agents use WebSearch, SEC filings, and Calculator
 *      tools to ground claims in real data
 *   5. CONFIGURABLE ITERATION LIMITS - maxIterations and qualityCriteria are set
 *      via Swarm config, controlling cost vs. quality tradeoffs
 *
 * Workflow:
 *   ┌──────────────────────────────────────────────────────────────┐
 *   │                    ITERATION LOOP                           │
 *   │                                                              │
 *   │   [Research Analyst] ──→ [Memo Writer] ──→ [MD Reviewer]    │
 *   │         │                      │                 │          │
 *   │    Gathers data           Drafts memo      Reviews against  │
 *   │    from SEC + web         with evidence     quality rubric  │
 *   │                                                  │          │
 *   │                              ┌───────────────────┤          │
 *   │                              │                   │          │
 *   │                        NEEDS_REFINEMENT     APPROVED        │
 *   │                        + specific feedback       │          │
 *   │                              │                   ↓          │
 *   │                              └──→ loop back    DONE         │
 *   └──────────────────────────────────────────────────────────────┘
 *
 * Why iterative refinement matters here:
 *   - First drafts often miss peer comparisons, misstate risk factors, or
 *     lack supporting data citations. A single pass rarely meets institutional bar.
 *   - The reviewer catches specific gaps ("Section 3 has no peer comparison",
 *     "Risk matrix is missing likelihood ratings") that the writer addresses.
 *   - Each iteration narrows the gap between the draft and the quality standard,
 *     converging toward a publish-ready memo.
 */
@Component
public class IterativeInvestmentMemoWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(IterativeInvestmentMemoWorkflow.class);
    private static final int MAX_EVIDENCE_PER_SOURCE = 15000;

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;
    private final CalculatorTool calculatorTool;
    private final WebSearchTool webSearchTool;
    private final SECFilingsTool secFilingsTool;

    public IterativeInvestmentMemoWorkflow(
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
        logger.info("Starting Iterative Investment Memo Workflow");

        try {
            String ticker = args.length > 0 ? args[0].toUpperCase() : "NVDA";
            int maxIterations = 3;
            if (args.length > 1) {
                try {
                    maxIterations = Integer.parseInt(args[1]);
                } catch (NumberFormatException ignored) {}
            }
            runIterativeMemoWorkflow(ticker, maxIterations);
        } catch (Exception e) {
            logger.error("Error running iterative investment memo workflow", e);
            throw e;
        }
    }

    private void runIterativeMemoWorkflow(String ticker, int maxIterations) {
        logger.info("Target: {} | Max iterations: {}", ticker, maxIterations);

        ChatClient chatClient = chatClientBuilder.build();

        // Pre-fetch tool evidence so all agents share the same data foundation
        String toolEvidence = buildToolEvidence(ticker);
        logEvidenceWarnings(toolEvidence, ticker);

        // =====================================================================
        // AGENTS
        // =====================================================================

        // Research Analyst: gathers and structures raw data
        Agent researchAnalyst = Agent.builder()
                .role("Senior Equity Research Analyst")
                .goal("Produce a comprehensive, data-grounded research brief on " + ticker + ". " +
                      "Extract every available financial metric, news event, and filing insight from the " +
                      "provided tool evidence. Organize findings into a structured brief that a memo " +
                      "writer can directly reference. When data is unavailable, state 'DATA NOT AVAILABLE' " +
                      "rather than estimating.")
                .backstory("You are a research analyst with 10 years at a top-tier investment bank. " +
                           "You are meticulous about data extraction: every number has a source citation, " +
                           "every claim is marked [CONFIRMED] or [ESTIMATE]. You produce research briefs " +
                           "that portfolio managers trust because they never contain fabricated data. " +
                           "You organize findings for maximum usability by downstream analysts.")
                .chatClient(chatClient)
                .tool(calculatorTool)
                .tool(webSearchTool)
                .tool(secFilingsTool)
                .verbose(true)
                .maxRpm(12)
                .temperature(0.1)
                .modelName("gpt-4o-mini")
                .build();

        // Memo Writer: transforms research into institutional-quality prose
        Agent memoWriter = Agent.builder()
                .role("Senior Investment Analyst & Memo Author")
                .goal("Write an institutional-quality investment memo on " + ticker + " that would pass " +
                      "review at a top-tier investment bank. Synthesize all research findings into a " +
                      "structured, data-backed memo with a clear BUY/HOLD/SELL thesis. " +
                      "When receiving reviewer feedback, address EVERY point raised — do not skip any. " +
                      "Each revision should show measurable improvement over the previous draft.")
                .backstory("You are a VP-level investment analyst who writes memos for the investment committee " +
                           "at a $50B AUM fund. Your memos have a reputation for rigorous analysis and clear " +
                           "writing. You take editorial feedback seriously: when the MD sends a memo back, " +
                           "you address every single point and produce a substantially better draft. " +
                           "You never repeat the same mistake across revisions. " +
                           "You cite specific data from the research brief for every quantitative claim.")
                .chatClient(chatClient)
                .tool(calculatorTool)
                .verbose(true)
                .maxRpm(10)
                .temperature(0.3)
                .modelName("gpt-4o-mini")
                .build();

        // Managing Director: reviews and provides feedback (REVIEWER agent)
        Agent managingDirector = Agent.builder()
                .role("Managing Director — Investment Committee Chair")
                .goal("Review investment memos against institutional-quality standards. " +
                      "Provide specific, actionable feedback that enables the analyst to improve. " +
                      "Only approve memos that meet ALL quality criteria. " +
                      "You are protecting the fund's reputation — every approved memo must be " +
                      "defensible to LPs and regulators.")
                .backstory("You are the MD chairing the investment committee at a top-5 hedge fund. " +
                           "You have rejected more memos than you have approved. Your standards are legendary: " +
                           "every claim needs a source, every number needs a calculation, every risk needs " +
                           "a mitigation plan. But your feedback is always constructive — you tell analysts " +
                           "exactly what to fix, not just that something is wrong. " +
                           "You grade on a rubric and only approve when all criteria score 4+ out of 5.")
                .chatClient(chatClient)
                .verbose(true)
                .maxRpm(10)
                .temperature(0.2)
                .modelName("gpt-4o-mini")
                .build();

        // =====================================================================
        // TASKS
        // =====================================================================

        Task researchTask = Task.builder()
                .id("research-brief")
                .description(String.format(
                        "Create a comprehensive research brief on %s using the tool evidence below.\n\n" +
                        "REQUIRED SECTIONS:\n" +
                        "1. COMPANY SNAPSHOT\n" +
                        "   - Name, ticker, sector, market cap (if available)\n" +
                        "   - Business description in 2-3 sentences\n" +
                        "   - Key products/segments and revenue breakdown\n\n" +
                        "2. FINANCIAL METRICS (cite filing type and date for each)\n" +
                        "   - Revenue: most recent period + YoY growth\n" +
                        "   - Net income / EPS\n" +
                        "   - Gross margin, operating margin, net margin\n" +
                        "   - Free cash flow\n" +
                        "   - Debt-to-equity ratio\n" +
                        "   - P/E ratio (if calculable from available data)\n\n" +
                        "3. RECENT DEVELOPMENTS (with dates and sources)\n" +
                        "   - 5-7 material events from the last 12 months\n" +
                        "   - Earnings surprises, guidance changes, product launches\n" +
                        "   - Management changes, M&A activity\n\n" +
                        "4. COMPETITIVE LANDSCAPE\n" +
                        "   - 3-4 direct competitors with comparison on 3+ metrics\n" +
                        "   - Market share or positioning data if available\n\n" +
                        "5. RISK FACTORS (from SEC filings Item 1A)\n" +
                        "   - Top 5 risks the company has disclosed\n" +
                        "   - Any new risks added in the most recent filing\n\n" +
                        "6. DATA GAPS\n" +
                        "   - List every metric that was unavailable\n" +
                        "   - Rate overall data completeness: HIGH / MEDIUM / LOW\n\n" +
                        "DATA RULES:\n" +
                        "- Use ONLY data from the tool evidence below — do NOT fabricate\n" +
                        "- Cite the source for every number: e.g., '[10-K 2024-09-28]' or '[Web: Reuters, 2025-01]'\n" +
                        "- Mark confirmed data as [CONFIRMED] and your estimates as [ESTIMATE]\n" +
                        "- If a required metric is unavailable, write 'DATA NOT AVAILABLE: [metric]'\n\n" +
                        "TOOL EVIDENCE:\n%s",
                        ticker, toolEvidence))
                .expectedOutput("A structured research brief with all 6 sections, every number cited, " +
                        "and a clear data gaps assessment")
                .agent(researchAnalyst)
                .outputFormat(OutputFormat.MARKDOWN)
                .maxExecutionTime(180000)
                .build();

        Task memoTask = Task.builder()
                .id("investment-memo")
                .description(String.format(
                        "Write an institutional-quality investment memo on %s.\n\n" +
                        "Use the research brief from the previous task as your SOLE data source.\n\n" +
                        "REQUIRED MEMO STRUCTURE:\n\n" +
                        "1. EXECUTIVE SUMMARY (max 250 words)\n" +
                        "   - Investment thesis in 1-2 sentences\n" +
                        "   - Recommendation: STRONG BUY / BUY / HOLD / SELL / STRONG SELL\n" +
                        "   - Confidence level: HIGH / MEDIUM / LOW (with 1-sentence justification)\n" +
                        "   - Target price or return expectation (if data supports it, otherwise state why not)\n\n" +
                        "2. BUSINESS OVERVIEW (200-300 words)\n" +
                        "   - What the company does and why it matters\n" +
                        "   - Key competitive advantages (moat analysis)\n" +
                        "   - Revenue drivers and growth trajectory\n\n" +
                        "3. FINANCIAL ANALYSIS (with data table)\n" +
                        "   - Financial metrics table: metric | value | source | trend\n" +
                        "   - Margin analysis with YoY comparison\n" +
                        "   - Cash flow and balance sheet health assessment\n" +
                        "   - Peer comparison table: company | metric1 | metric2 | metric3\n\n" +
                        "4. CATALYST ANALYSIS\n" +
                        "   - 3-5 near-term catalysts (with expected dates)\n" +
                        "   - For each: what it is, probability (HIGH/MED/LOW), potential impact\n\n" +
                        "5. RISK ASSESSMENT\n" +
                        "   - Risk matrix table: risk | likelihood | impact | mitigation\n" +
                        "   - At least 5 risks, each with a specific mitigation strategy\n" +
                        "   - Bear case scenario with quantified downside\n\n" +
                        "6. INVESTMENT RECOMMENDATION\n" +
                        "   - Detailed justification referencing specific data from sections 2-5\n" +
                        "   - Bull case / Base case / Bear case with return estimates\n" +
                        "   - Time horizon and position sizing guidance\n" +
                        "   - Key monitoring metrics (what would change the thesis)\n\n" +
                        "7. DATA QUALITY DISCLAIMER\n" +
                        "   - What data was available vs. missing\n" +
                        "   - How data gaps affect confidence in the recommendation\n\n" +
                        "WRITING RULES:\n" +
                        "- Every quantitative claim must cite the research brief section\n" +
                        "- Use tables for financial data (not prose with inline numbers)\n" +
                        "- Cross-reference between sections (e.g., 'The margin expansion noted in " +
                        "Section 3 supports the bull catalyst in Section 4')\n" +
                        "- Write for an institutional audience: precise, data-driven, no filler\n" +
                        "- Do NOT introduce data not present in the research brief\n" +
                        "- Do NOT use phrases like 'as an AI' or generic disclaimers",
                        ticker))
                .expectedOutput("A complete investment memo with all 7 sections, tables for financial data, " +
                        "a clear BUY/HOLD/SELL recommendation with confidence level, and full citations")
                .agent(memoWriter)
                .outputFormat(OutputFormat.MARKDOWN)
                .outputFile("output/investment_memo_" + ticker.toLowerCase() + ".md")
                .dependsOn(researchTask)
                .maxExecutionTime(240000)
                .build();

        // =====================================================================
        // QUALITY CRITERIA — The rubric the MD reviews against
        // =====================================================================

        String qualityCriteria =
                "Grade each criterion on a 1-5 scale. ALL must score 4+ for APPROVED.\n\n" +
                "1. THESIS CLARITY (Is the BUY/HOLD/SELL recommendation stated in the first paragraph " +
                "with a clear 1-2 sentence justification?)\n\n" +
                "2. DATA GROUNDING (Does every quantitative claim cite a specific source from the " +
                "research brief? Are financial metrics presented in tables, not buried in prose?)\n\n" +
                "3. PEER COMPARISON (Is there a comparison table with 3+ competitors on 3+ metrics? " +
                "Are the competitors real companies, not placeholders?)\n\n" +
                "4. RISK ANALYSIS (Are there 5+ risks in a matrix with likelihood, impact, AND " +
                "mitigation for each? Is there a quantified bear case?)\n\n" +
                "5. CATALYST IDENTIFICATION (Are there 3+ catalysts with expected dates and " +
                "probability ratings? Are they specific, not generic?)\n\n" +
                "6. CROSS-REFERENCING (Do sections reference each other? E.g., does the " +
                "recommendation section cite specific findings from the financial analysis?)\n\n" +
                "7. COMPLETENESS (Are all 7 required memo sections present and substantive? " +
                "Is the data quality disclaimer honest about gaps?)";

        // =====================================================================
        // SWARM — ITERATIVE process with MD as reviewer
        // =====================================================================

        Swarm memoSwarm = Swarm.builder()
                .id("iterative-memo-" + ticker.toLowerCase())
                .agent(researchAnalyst)
                .agent(memoWriter)
                .agent(managingDirector)
                .task(researchTask)
                .task(memoTask)
                .process(ProcessType.ITERATIVE)
                .managerAgent(managingDirector) // MD is the reviewer
                .verbose(true)
                .maxRpm(15)
                .language("en")
                .eventPublisher(eventPublisher)
                .config("maxIterations", maxIterations)
                .config("qualityCriteria", qualityCriteria)
                .config("analysisType", "iterative-investment-memo")
                .config("ticker", ticker)
                .build();

        // =====================================================================
        // EXECUTE
        // =====================================================================

        logger.info("\n" + "=".repeat(80));
        logger.info("ITERATIVE INVESTMENT MEMO WORKFLOW");
        logger.info("=".repeat(80));
        logger.info("Target:          {}", ticker);
        logger.info("Max Iterations:  {}", maxIterations);
        logger.info("Process:         ITERATIVE (execute -> review -> refine -> repeat)");
        logger.info("Team:");
        logger.info("  Research Analyst  — gathers data from SEC filings + web");
        logger.info("  Memo Writer       — drafts institutional-quality memo");
        logger.info("  Managing Director — reviews against 7-point quality rubric");
        logger.info("=".repeat(80));

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("ticker", ticker);
        inputs.put("analysis_type", "investment_memo");

        long startTime = System.currentTimeMillis();
        SwarmOutput result = memoSwarm.kickoff(inputs);
        long endTime = System.currentTimeMillis();

        // =====================================================================
        // RESULTS
        // =====================================================================

        Map<String, Object> metrics = result.getUsageMetrics();
        int iterations = (int) metrics.getOrDefault("iterations", 0);
        boolean approved = (boolean) metrics.getOrDefault("approved", false);

        logger.info("\n" + "=".repeat(80));
        logger.info("ITERATIVE INVESTMENT MEMO — RESULTS");
        logger.info("=".repeat(80));
        logger.info("Ticker:             {}", ticker);
        logger.info("Duration:           {} seconds", (endTime - startTime) / 1000);
        logger.info("Iterations:         {}/{}", iterations, maxIterations);
        logger.info("Reviewer Verdict:   {}", approved ? "APPROVED" : "MAX ITERATIONS REACHED");
        logger.info("Total LLM calls:    {}", metrics.getOrDefault("totalTasks", "N/A"));

        // Show per-iteration breakdown
        logger.info("\nIteration Breakdown:");
        int taskIndex = 0;
        for (var taskOutput : result.getTaskOutputs()) {
            taskIndex++;
            String taskId = taskOutput.getTaskId() != null ? taskOutput.getTaskId() : "unknown";
            String type;
            if (taskId.startsWith("review-iteration-")) {
                type = "REVIEW";
            } else if (taskId.equals("research-brief")) {
                type = "RESEARCH";
            } else if (taskId.equals("investment-memo")) {
                type = "MEMO";
            } else {
                type = "TASK";
            }
            logger.info("  [{}] {}: {} chars, {} prompt + {} completion tokens",
                    type, taskId,
                    taskOutput.getRawOutput() != null ? taskOutput.getRawOutput().length() : 0,
                    taskOutput.getPromptTokens() != null ? taskOutput.getPromptTokens() : 0,
                    taskOutput.getCompletionTokens() != null ? taskOutput.getCompletionTokens() : 0);
        }

        logger.info("\n{}", result.getTokenUsageSummary("gpt-4o-mini"));
        logger.info("\nFinal Investment Memo:\n{}", result.getFinalOutput());
        logger.info("=".repeat(80));
    }

    // =========================================================================
    // Tool Evidence Gathering
    // =========================================================================

    private String buildToolEvidence(String ticker) {
        StringBuilder evidence = new StringBuilder();

        evidence.append("=== WEB SEARCH RESULTS ===\n");
        evidence.append("Query: \"").append(ticker).append(" stock analysis investment\"\n");
        evidence.append("Retrieved: ").append(java.time.LocalDateTime.now()).append("\n\n");
        evidence.append(truncateEvidence(callWebSearch(ticker + " stock analysis investment thesis"), MAX_EVIDENCE_PER_SOURCE));

        evidence.append("\n\n=== WEB SEARCH: COMPETITORS ===\n");
        evidence.append("Query: \"").append(ticker).append(" competitors market share comparison\"\n");
        evidence.append("Retrieved: ").append(java.time.LocalDateTime.now()).append("\n\n");
        evidence.append(truncateEvidence(callWebSearch(ticker + " competitors market share comparison"), MAX_EVIDENCE_PER_SOURCE));

        evidence.append("\n\n=== SEC FILINGS DATA (EDGAR) ===\n");
        evidence.append("Company: ").append(ticker).append("\n");
        evidence.append("Source: SEC EDGAR (public, no API key required)\n");
        evidence.append("Retrieved: ").append(java.time.LocalDateTime.now()).append("\n\n");
        evidence.append(truncateEvidence(callSecFilings(ticker), MAX_EVIDENCE_PER_SOURCE));

        evidence.append("\n\n=== END OF TOOL EVIDENCE ===");
        return evidence.toString();
    }

    private String callWebSearch(String query) {
        try {
            Object result = webSearchTool.execute(Map.of("query", query));
            return result != null ? result.toString() : "No web search output.";
        } catch (Exception e) {
            return "Web search error: " + e.getMessage();
        }
    }

    private String callSecFilings(String ticker) {
        try {
            Object result = secFilingsTool.execute(Map.of("input", ticker + ":recent filings summary"));
            return result != null ? result.toString() : "No SEC filings output.";
        } catch (Exception e) {
            return "SEC filings error: " + e.getMessage();
        }
    }

    private String truncateEvidence(String evidence, int maxLength) {
        if (evidence == null || evidence.length() <= maxLength) {
            return evidence;
        }
        logger.info("Truncating tool evidence from {} to {} chars", evidence.length(), maxLength);
        return evidence.substring(0, maxLength) + "\n\n[... truncated, " + evidence.length() + " total chars ...]";
    }

    private void logEvidenceWarnings(String toolEvidence, String ticker) {
        if (toolEvidence == null || toolEvidence.isEmpty()) {
            logger.warn("Tool evidence is empty for {}", ticker);
            return;
        }
        String lower = toolEvidence.toLowerCase();
        if (lower.contains("configure") || lower.contains("api key")) {
            logger.warn("Tool evidence indicates missing API configuration for {}", ticker);
        }
        if (lower.contains("error")) {
            logger.warn("Tool evidence contains errors for {}", ticker);
        }
    }
}
