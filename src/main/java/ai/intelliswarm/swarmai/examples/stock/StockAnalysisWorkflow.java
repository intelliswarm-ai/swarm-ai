package ai.intelliswarm.swarmai.examples.stock;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.OutputFormat;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.observability.core.ObservabilityHelper;
import ai.intelliswarm.swarmai.observability.decision.DecisionTracer;
import ai.intelliswarm.swarmai.observability.decision.DecisionTree;
import ai.intelliswarm.swarmai.observability.replay.EventStore;
import ai.intelliswarm.swarmai.observability.replay.WorkflowRecording;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import ai.intelliswarm.swarmai.examples.stock.tools.CalculatorTool;
import ai.intelliswarm.swarmai.examples.stock.tools.WebSearchTool;
import ai.intelliswarm.swarmai.examples.stock.tools.SECFilingsTool;

@Component
public class StockAnalysisWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(StockAnalysisWorkflow.class);

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;
    private final CalculatorTool calculatorTool;
    private final WebSearchTool webSearchTool;
    private final SECFilingsTool secFilingsTool;

    // Observability components
    private final ObservabilityHelper observabilityHelper;
    private final DecisionTracer decisionTracer;
    private final EventStore eventStore;

    public StockAnalysisWorkflow(
            ChatClient.Builder chatClientBuilder,
            ApplicationEventPublisher eventPublisher,
            CalculatorTool stockCalculatorTool,
            WebSearchTool stockWebSearchTool,
            SECFilingsTool stockSECFilingsTool,
            @Autowired(required = false) ObservabilityHelper observabilityHelper,
            @Autowired(required = false) DecisionTracer decisionTracer,
            @Autowired(required = false) EventStore eventStore) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
        this.calculatorTool = stockCalculatorTool;
        this.webSearchTool = stockWebSearchTool;
        this.secFilingsTool = stockSECFilingsTool;
        this.observabilityHelper = observabilityHelper;
        this.decisionTracer = decisionTracer;
        this.eventStore = eventStore;
    }
    
    public void run(String... args) throws Exception {
        logger.info("📊 Starting Stock Analysis Workflow with SwarmAI Framework");
        
        try {
            // Default stock to analyze - can be overridden via command line args
            String companyStock = args.length > 0 ? args[0] : "AAPL";
            runStockAnalysisWorkflow(companyStock);
        } catch (Exception e) {
            logger.error("❌ Error running stock analysis workflow", e);
            throw e;
        }
    }
    
    private void runStockAnalysisWorkflow(String companyStock) {
        logger.info("Analyzing stock: {}", companyStock);

        ChatClient chatClient = chatClientBuilder.build();

        String toolEvidence = buildToolEvidence(companyStock);
        logEvidenceWarnings(toolEvidence, companyStock);

        // =====================================================================
        // AGENTS - Each agent has an accuracy-focused goal and rigorous backstory
        // =====================================================================

        Agent portfolioManager = Agent.builder()
                .role("Senior Portfolio Manager")
                .goal("Coordinate a rigorous, evidence-based stock analysis workflow. " +
                      "Ensure each specialist provides quantitative data with cited sources. " +
                      "Reject vague or unsubstantiated claims in the final synthesis.")
                .backstory("You are a CFA charterholder with 15 years managing institutional equity portfolios. " +
                           "You are known for demanding analytical rigor from your team. " +
                           "You reject reports that contain unsubstantiated claims or generic filler. " +
                           "Every number must have a source, and every recommendation must have a confidence level.")
                .chatClient(chatClient)
                .verbose(true)
                .allowDelegation(true)
                .maxRpm(10)
                .temperature(0.2)
                .build();

        Agent financialAnalyst = Agent.builder()
                .role("Senior Financial Analyst")
                .goal("Produce accurate, evidence-based financial analysis using quantitative data " +
                      "from SEC filings and market sources. Every claim must cite its data source. " +
                      "When data is unavailable, state it explicitly rather than estimating.")
                .backstory("You are a CFA-certified equity research analyst with 10 years of experience " +
                           "covering technology and large-cap stocks. You prioritize accuracy over narrative appeal. " +
                           "You never fabricate numbers or present estimates as facts. " +
                           "Your reports are trusted because they clearly distinguish between hard data, " +
                           "analyst estimates, and your own assumptions.")
                .chatClient(chatClient)
                .tool(calculatorTool)
                .tool(webSearchTool)
                .tool(secFilingsTool)
                .verbose(true)
                .maxRpm(10)
                .temperature(0.1)
                .build();

        Agent researchAnalyst = Agent.builder()
                .role("Senior Equity Research Analyst")
                .goal("Gather, verify, and summarize recent market intelligence including news, " +
                      "analyst opinions, and industry developments. Cite the source and date for " +
                      "every factual claim. Distinguish between confirmed facts and market speculation.")
                .backstory("You are a research analyst with 8 years of experience in equity research " +
                           "at a top-tier investment bank. You are methodical and skeptical: you only report " +
                           "information that has a verifiable source. You always note the date and origin " +
                           "of each piece of information. You clearly separate confirmed facts from " +
                           "analyst opinions and market rumors.")
                .chatClient(chatClient)
                .tool(webSearchTool)
                .tool(secFilingsTool)
                .verbose(true)
                .maxRpm(12)
                .temperature(0.2)
                .build();

        Agent investmentAdvisor = Agent.builder()
                .role("Senior Investment Advisor")
                .goal("Synthesize financial analysis, research findings, and SEC filing data into a " +
                      "calibrated investment recommendation. Ground every recommendation in specific " +
                      "evidence from prior analyses. State confidence levels explicitly.")
                .backstory("You are a Series 65 licensed investment advisor with 12 years of experience " +
                           "in wealth management. You provide calibrated assessments: you state your " +
                           "confidence level (HIGH/MEDIUM/LOW) for each recommendation. You never overstate " +
                           "certainty or understate risk. You always reference the specific data points that " +
                           "support your conclusions, citing which prior analysis they came from.")
                .chatClient(chatClient)
                .tool(calculatorTool)
                .tool(webSearchTool)
                .tool(secFilingsTool)
                .verbose(true)
                .maxRpm(10)
                .temperature(0.2)
                .build();

        // =====================================================================
        // TASKS - Specific, numbered requirements with data grounding rules
        // =====================================================================

        Task financialAnalysisTask = Task.builder()
                .description(String.format(
                        "Analyze %s's financial health using ONLY the tool evidence provided below.\n\n" +
                        "REQUIRED ANALYSIS (address each numbered item):\n" +
                        "1. Revenue and net income for the most recent reported period, with year-over-year change\n" +
                        "2. Key ratios: P/E, P/B, debt-to-equity, current ratio, ROE (cite the filing or source for each)\n" +
                        "3. Free cash flow trend: positive/negative, growing/shrinking\n" +
                        "4. Comparison with 2-3 industry peers on at least 3 financial metrics\n" +
                        "5. Top 3 financial risks with specific supporting evidence\n\n" +
                        "DATA RULES:\n" +
                        "- Use ONLY data from the tool evidence below\n" +
                        "- If a metric is unavailable, write \"DATA NOT AVAILABLE: [metric name]\" instead of guessing\n" +
                        "- Cite the source for every number (e.g., \"per 10-K filing dated 2024-11-01\")\n" +
                        "- Do NOT use phrases like \"as an AI\" or generic disclaimers\n\n" +
                        "TOOL EVIDENCE:\n%s",
                        companyStock, toolEvidence))
                .expectedOutput("Markdown report with exactly these sections:\n" +
                        "1. Executive Summary (3-5 bullet points, max 100 words)\n" +
                        "2. Financial Metrics Table (at least 5 metrics with values and sources)\n" +
                        "3. Peer Comparison Table (2-3 peers, 3+ shared metrics)\n" +
                        "4. Risk Assessment (3 risks, each with supporting evidence)\n" +
                        "5. Data Gaps (list any required metrics that were unavailable)")
                .agent(financialAnalyst)
                .outputFormat(OutputFormat.MARKDOWN)
                .maxExecutionTime(180000)
                .build();

        Task researchTask = Task.builder()
                .description(String.format(
                        "Research recent market intelligence for %s using ONLY the tool evidence below.\n\n" +
                        "REQUIRED SECTIONS (address each numbered item):\n" +
                        "1. Recent News: 3-5 most significant news items, each with date and source\n" +
                        "2. Market Sentiment: Bull case vs. bear case with specific evidence for each\n" +
                        "3. Industry Context: 2-3 industry trends affecting %s, with sources\n" +
                        "4. Upcoming Catalysts: Earnings dates, product launches, regulatory events\n" +
                        "5. Data Gaps: What data sources were unavailable or incomplete\n\n" +
                        "DATA RULES:\n" +
                        "- Only report news items that appear in the tool evidence\n" +
                        "- For each news item, state: date, source, and relevance to investment thesis\n" +
                        "- Distinguish between confirmed facts and analyst opinions\n" +
                        "- Do NOT invent or assume news events not in the evidence\n\n" +
                        "TOOL EVIDENCE:\n%s",
                        companyStock, companyStock, toolEvidence))
                .expectedOutput(String.format("Markdown report for %s with sections: " +
                        "Recent News (3-5 items with dates), Bull/Bear Cases (with evidence), " +
                        "Industry Trends (2-3 trends), Upcoming Catalysts, Data Gaps", companyStock))
                .agent(researchAnalyst)
                .outputFormat(OutputFormat.MARKDOWN)
                .maxExecutionTime(180000)
                .build();

        Task filingsAnalysisTask = Task.builder()
                .description(String.format(
                        "Analyze SEC filings data for %s from the tool evidence below.\n\n" +
                        "REQUIRED ANALYSIS (address each numbered item):\n" +
                        "1. Filing Inventory: List all filings found (type, date, accession number)\n" +
                        "2. Revenue & Income Trends: Extract multi-period revenue and net income from 10-K/10-Q\n" +
                        "3. Management Discussion: Key risks and opportunities management disclosed\n" +
                        "4. Insider Transactions: Any insider buying/selling activity found\n" +
                        "5. Material Changes: Significant changes from prior period filings\n\n" +
                        "DATA RULES:\n" +
                        "- Quote or paraphrase specific passages from filings when citing findings\n" +
                        "- Prefix each finding with the filing type and date (e.g., \"[10-K 2024-09-28]\")\n" +
                        "- If a filing type is missing from the evidence, state it explicitly\n" +
                        "- Do NOT fabricate filing data or dates\n\n" +
                        "TOOL EVIDENCE:\n%s",
                        companyStock, toolEvidence))
                .expectedOutput("Markdown report with sections: " +
                        "Filing Inventory (table), Revenue/Income Trends (with periods), " +
                        "Management Discussion Highlights, Insider Transactions, " +
                        "Material Changes, Data Gaps")
                .agent(financialAnalyst)
                .outputFormat(OutputFormat.MARKDOWN)
                .maxExecutionTime(180000)
                .build();

        Task recommendationTask = Task.builder()
                .description("Synthesize ALL prior task outputs into a final investment recommendation.\n\n" +
                        "REQUIRED (address each numbered item):\n" +
                        "1. Reference specific findings from each prior task by section name\n" +
                        "2. State a clear recommendation: STRONG BUY / BUY / HOLD / SELL / STRONG SELL\n" +
                        "3. State a confidence level: HIGH / MEDIUM / LOW with justification\n" +
                        "4. Provide a risk/reward assessment with at least 3 risks and 3 opportunities\n" +
                        "5. State a 12-month outlook or explain why one cannot be justified from available data\n\n" +
                        "RULES:\n" +
                        "- Do NOT introduce new data not present in prior task outputs\n" +
                        "- Cross-reference findings between tasks (e.g., \"The revenue growth of X% noted in " +
                        "the Financial Analysis is consistent with the positive sentiment in the Research report\")\n" +
                        "- If prior tasks had Data Gaps, assess how those gaps affect recommendation confidence\n" +
                        "- Do NOT use phrases like \"as a language model\" or provide generic disclaimers")
                .expectedOutput("Markdown report with sections:\n" +
                        "1. Executive Summary (recommendation + confidence level + 1-paragraph rationale)\n" +
                        "2. Financial Analysis Summary (key metrics from prior analysis, cross-referenced)\n" +
                        "3. Market & News Assessment (key findings from research, cross-referenced)\n" +
                        "4. SEC Filings Assessment (key findings from filings analysis, cross-referenced)\n" +
                        "5. Investment Recommendation (BUY/HOLD/SELL with detailed justification)\n" +
                        "6. Risk/Reward Matrix (3+ risks, 3+ opportunities, each with likelihood and impact)\n" +
                        "7. Data Gaps & Confidence Impact (how missing data affects the recommendation)")
                .agent(investmentAdvisor)
                .outputFormat(OutputFormat.MARKDOWN)
                .outputFile("stock_analysis_report.md")
                .maxExecutionTime(240000)
                .dependsOn(financialAnalysisTask)
                .dependsOn(researchTask)
                .dependsOn(filingsAnalysisTask)
                .build();
        
        // Create Swarm with Hierarchical Process
        Swarm stockAnalysisSwarm = Swarm.builder()
                .id("stock-analysis-swarm")
                .agent(financialAnalyst)
                .agent(researchAnalyst)
                .agent(investmentAdvisor)
                .managerAgent(portfolioManager)
                .task(financialAnalysisTask)
                .task(researchTask)
                .task(filingsAnalysisTask)
                .task(recommendationTask)
                .process(ProcessType.HIERARCHICAL) // Manager coordinates the workflow
                .verbose(true)
                .maxRpm(15)
                .language("en")
                .eventPublisher(eventPublisher)
                .config("analysisType", "stock")
                .config("ticker", companyStock)
                .config("outputFormat", "investment-report")
                .build();
        
        // Execute Workflow
        logger.info("🎯 Executing Stock Analysis Workflow for {}", companyStock);
        logger.info("👥 Team: Portfolio Manager + 3 Specialized Financial Agents");
        logger.info("📊 Process: Hierarchical coordination");
        logger.info("⏱️ Expected Duration: ~8-12 minutes");
        
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("company_stock", companyStock);
        inputs.put("analysisScope", "Comprehensive financial and market analysis");
        inputs.put("timeframe", "Current market state with forward-looking insights");
        inputs.put("investmentObjective", "Investment decision support");

        // Initialize decision tracing if enabled
        String correlationId = java.util.UUID.randomUUID().toString();
        if (decisionTracer != null && decisionTracer.isEnabled()) {
            decisionTracer.startTrace(correlationId, "stock-analysis-swarm");
            logger.info("🔍 Decision tracing enabled - Correlation ID: {}", correlationId);
        }

        long startTime = System.currentTimeMillis();
        SwarmOutput result = stockAnalysisSwarm.kickoff(inputs);
        long endTime = System.currentTimeMillis();

        // Complete decision tracing
        if (decisionTracer != null && decisionTracer.isEnabled()) {
            decisionTracer.completeTrace(correlationId);
        }

        // Display Results
        double durationMinutes = (endTime - startTime) / 60000.0;
        logger.info("\n" + "=".repeat(80));
        logger.info("✅ STOCK ANALYSIS WORKFLOW COMPLETED");
        logger.info("=".repeat(80));
        logger.info("📊 Stock Analyzed: {}", companyStock);
        logger.info("Duration: {} seconds", (endTime - startTime) / 1000);
        logger.info("📈 Final Investment Recommendation:\n{}", result.getFinalOutput());
        logger.info("=".repeat(80));

        // Display observability summary
        displayObservabilitySummary(correlationId);
    }

    private static final int MAX_EVIDENCE_PER_SOURCE = 15000; // ~4K tokens per source

    private String buildToolEvidence(String companyStock) {
        StringBuilder evidence = new StringBuilder();
        evidence.append("=== WEB SEARCH RESULTS ===\n");
        evidence.append("Query: \"").append(companyStock).append(" stock analysis\"\n");
        evidence.append("Retrieved: ").append(java.time.LocalDateTime.now()).append("\n\n");
        evidence.append(truncateEvidence(callWebSearch(companyStock), MAX_EVIDENCE_PER_SOURCE));
        evidence.append("\n\n=== SEC FILINGS DATA (EDGAR) ===\n");
        evidence.append("Company: ").append(companyStock).append("\n");
        evidence.append("Source: SEC EDGAR (public, no API key required)\n");
        evidence.append("Retrieved: ").append(java.time.LocalDateTime.now()).append("\n\n");
        evidence.append(truncateEvidence(callSecFilings(companyStock), MAX_EVIDENCE_PER_SOURCE));
        evidence.append("\n\n=== END OF TOOL EVIDENCE ===");
        return evidence.toString();
    }

    private String truncateEvidence(String evidence, int maxLength) {
        if (evidence == null || evidence.length() <= maxLength) {
            return evidence;
        }
        logger.info("Truncating tool evidence from {} to {} chars", evidence.length(), maxLength);
        return evidence.substring(0, maxLength) + "\n\n[... truncated, " + evidence.length() + " total chars ...]";
    }

    private String callWebSearch(String companyStock) {
        try {
            Object result = webSearchTool.execute(Map.of("query", companyStock + " stock analysis"));
            return result != null ? result.toString() : "No web search output.";
        } catch (Exception e) {
            return "Web search error: " + e.getMessage();
        }
    }

    private String callSecFilings(String companyStock) {
        try {
            Object result = secFilingsTool.execute(Map.of("input", companyStock + ":recent filings summary"));
            return result != null ? result.toString() : "No SEC filings output.";
        } catch (Exception e) {
            return "SEC filings error: " + e.getMessage();
        }
    }

    private void logEvidenceWarnings(String toolEvidence, String companyStock) {
        if (toolEvidence == null || toolEvidence.isEmpty()) {
            logger.warn("Tool evidence is empty for {}", companyStock);
            return;
        }

        String evidenceLower = toolEvidence.toLowerCase();
        if (evidenceLower.contains("configure") || evidenceLower.contains("api key")) {
            logger.warn("Tool evidence indicates missing API configuration for {}", companyStock);
        }
        if (evidenceLower.contains("error")) {
            logger.warn("Tool evidence contains errors for {}", companyStock);
        }
    }

    /**
     * Displays observability summary including event timeline and decision trace.
     */
    private void displayObservabilitySummary(String correlationId) {
        logger.info("\n" + "=".repeat(80));
        logger.info("📊 OBSERVABILITY SUMMARY");
        logger.info("=".repeat(80));

        // Display workflow recording if available
        if (eventStore != null) {
            Optional<WorkflowRecording> recordingOpt = eventStore.createRecording(correlationId);
            if (recordingOpt.isPresent()) {
                WorkflowRecording recording = recordingOpt.get();
                WorkflowRecording.WorkflowSummary summary = recording.getSummary();

                logger.info("📋 Workflow Recording:");
                logger.info("   Correlation ID: {}", recording.getCorrelationId());
                logger.info("   Status: {}", recording.getStatus());
                logger.info("   Duration: {} ms", recording.getDurationMs());
                logger.info("   Total Events: {}", summary.getTotalEvents());
                logger.info("   Unique Agents: {}", summary.getUniqueAgents());
                logger.info("   Unique Tasks: {}", summary.getUniqueTasks());
                logger.info("   Unique Tools: {}", summary.getUniqueTools());
                logger.info("   Error Count: {}", summary.getErrorCount());

                // Display event timeline
                logger.info("\n📅 Event Timeline:");
                for (WorkflowRecording.EventRecord event : recording.getTimeline()) {
                    logger.info("   [{} ms] {} - {} (agent: {}, task: {}, tool: {})",
                            event.getElapsedMs() != null ? event.getElapsedMs() : 0,
                            event.getEventType(),
                            truncate(event.getMessage(), 50),
                            event.getAgentId() != null ? truncate(event.getAgentId(), 20) : "-",
                            event.getTaskId() != null ? truncate(event.getTaskId(), 20) : "-",
                            event.getToolName() != null ? event.getToolName() : "-");
                }
            } else {
                logger.info("   No workflow recording available");
            }
        }

        // Display decision trace if available
        if (decisionTracer != null && decisionTracer.isEnabled()) {
            Optional<DecisionTree> treeOpt = decisionTracer.getDecisionTree(correlationId);
            if (treeOpt.isPresent()) {
                DecisionTree tree = treeOpt.get();
                logger.info("\n🧠 Decision Trace:");
                logger.info("   Total Decisions: {}", tree.getNodeCount());
                logger.info("   Unique Agents: {}", tree.getUniqueAgentIds().size());
                logger.info("   Unique Tasks: {}", tree.getUniqueTaskIds().size());

                // Display workflow explanation
                String explanation = decisionTracer.explainWorkflow(correlationId);
                logger.info("\n📝 Workflow Explanation:\n{}", explanation);
            } else {
                logger.info("   No decision trace available (enable decision-tracing-enabled in config)");
            }
        } else {
            logger.info("   Decision tracing not enabled");
        }

        logger.info("=".repeat(80));
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 3) + "...";
    }
}
