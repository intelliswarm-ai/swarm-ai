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
        logger.info("🔍 Analyzing stock: {}", companyStock);
        
        // Create ChatClient instance
        ChatClient chatClient = chatClientBuilder.build();

        String toolEvidence = buildToolEvidence(companyStock);
        logEvidenceWarnings(toolEvidence, companyStock);
        
        // Create Portfolio Manager Agent (coordinates the workflow)
        Agent portfolioManager = Agent.builder()
                .role("Senior Portfolio Manager")
                .goal("Coordinate comprehensive stock analysis and provide strategic investment guidance")
                .backstory("You are an experienced portfolio manager with 15+ years in investment management. You excel at coordinating financial analysis teams and synthesizing complex investment research into actionable recommendations.")
                .chatClient(chatClient)
                .verbose(true)
                .allowDelegation(true)
                .maxRpm(10)
                .temperature(0.3)
                .build();
        
        // Create Financial Analyst Agent
        Agent financialAnalyst = Agent.builder()
                .role("The Best Financial Analyst")
                .goal("Impress all customers with your financial data and market trends analysis")
                .backstory("The most seasoned financial analyst with lots of expertise in stock market analysis and investment strategies that is working for a super important customer.")
                .chatClient(chatClient)
                .tool(calculatorTool)
                .tool(webSearchTool)
                .tool(secFilingsTool)
                .verbose(true)
                .maxRpm(10)
                .temperature(0.1) // Conservative for financial analysis
                .build();
        
        // Create Research Analyst Agent
        Agent researchAnalyst = Agent.builder()
                .role("Staff Research Analyst")
                .goal("Being the best at gathering, interpreting data and amazing your customer with it")
                .backstory("Known as the BEST research analyst, you're skilled in sifting through news, company announcements, and market sentiments. Now you're working on a super important customer.")
                .chatClient(chatClient)
                .tool(webSearchTool)
                .tool(secFilingsTool)
                .verbose(true)
                .maxRpm(12)
                .temperature(0.3) // Moderate creativity for research
                .build();
        
        // Create Investment Advisor Agent
        Agent investmentAdvisor = Agent.builder()
                .role("Private Investment Advisor")
                .goal("Impress your customers with full analyses over stocks and complete investment recommendations")
                .backstory("You're the most experienced investment advisor and you combine various analytical insights to formulate strategic investment advice. You are now working for a super important customer you need to impress.")
                .chatClient(chatClient)
                .tool(calculatorTool)
                .tool(webSearchTool)
                .verbose(true)
                .maxRpm(10)
                .temperature(0.2) // Balanced for recommendations
                .build();
        
        // Create Tasks
        Task financialAnalysisTask = Task.builder()
                .description(String.format(
                        "Conduct a thorough analysis of %s's stock financial health and market performance. " +
                        "Use the tool evidence below as your factual basis. " +
                        "If the evidence contains configuration notes or errors, include a 'Data Availability' section " +
                        "explaining what is missing and how it impacts the analysis. " +
                        "Avoid generic AI disclaimers.\n\n" +
                        "Tool evidence:\n%s",
                        companyStock, toolEvidence))
                .expectedOutput("Provide a structured report with sections: Executive Summary, Financial Metrics, Peer Comparison, Risks, Data Availability. Use only facts from the tool evidence and clearly mark any assumptions.")
                .agent(financialAnalyst)
                .outputFormat(OutputFormat.MARKDOWN)
                .maxExecutionTime(180000)
                .build();
        
        Task researchTask = Task.builder()
                .description(String.format(
                        "Collect and summarize recent news articles, press releases, and market analyses related to the %s stock and its industry. " +
                        "Use the tool evidence below as your factual basis. " +
                        "If the evidence contains configuration notes or errors, include a 'Data Availability' section " +
                        "explaining what is missing and how it impacts the analysis. " +
                        "Avoid generic AI disclaimers.\n\n" +
                        "Tool evidence:\n%s",
                        companyStock, toolEvidence))
                .expectedOutput(String.format("Provide a structured report with sections: News Summary, Market Sentiment, Upcoming Events, Data Availability. Include the stock ticker %s and clearly mark any assumptions.", companyStock))
                .agent(researchAnalyst)
                .outputFormat(OutputFormat.MARKDOWN)
                .maxExecutionTime(180000)
                .build();
        
        Task filingsAnalysisTask = Task.builder()
                .description(String.format(
                        "Analyze the latest 10-Q and 10-K filings from EDGAR for the stock %s in question. " +
                        "Use the tool evidence below as your factual basis. " +
                        "If the evidence contains configuration notes or errors, include a 'Data Availability' section " +
                        "explaining what is missing and how it impacts the analysis. " +
                        "Avoid generic AI disclaimers.\n\n" +
                        "Tool evidence:\n%s",
                        companyStock, toolEvidence))
                .expectedOutput("Provide a structured report with sections: Filings Overview, Key Findings, Insider Activity, Risks, Data Availability. Use only facts from the tool evidence and clearly mark any assumptions.")
                .agent(financialAnalyst)
                .outputFormat(OutputFormat.MARKDOWN)
                .maxExecutionTime(180000)
                .build();
        
        Task recommendationTask = Task.builder()
                .description("Review and synthesize the analyses provided by the Financial Analyst and the Research Analyst. " +
                        "Combine these insights to form a comprehensive investment recommendation. " +
                        "You MUST consider all aspects, including financial health, market sentiment, and qualitative data from EDGAR filings. " +
                        "Include insider trading activity, upcoming events, and a Data Availability section if any inputs were missing. " +
                        "Avoid generic AI disclaimers.")
                .expectedOutput("Provide a structured report with sections: Executive Summary, Financial Analysis, Market Research, SEC Filings, Recommendation, Risks, Data Availability. Use only facts from prior task outputs and clearly mark any assumptions.")
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
        logger.info("⏱️ Duration: {:.1f} minutes", durationMinutes);
        logger.info("📈 Final Investment Recommendation:\n{}", result.getFinalOutput());
        logger.info("=".repeat(80));

        // Display observability summary
        displayObservabilitySummary(correlationId);
    }

    private String buildToolEvidence(String companyStock) {
        StringBuilder evidence = new StringBuilder();
        evidence.append("WEB_SEARCH\n");
        evidence.append(callWebSearch(companyStock));
        evidence.append("\n\nSEC_FILINGS\n");
        evidence.append(callSecFilings(companyStock));
        return evidence.toString();
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
