/*
 * SwarmAI Framework - A Java implementation inspired by CrewAI
 * 
 * This file is part of SwarmAI, a derivative work based on CrewAI.
 * Original CrewAI: Copyright (c) 2025 crewAI, Inc. (MIT License)
 * SwarmAI adaptations: Copyright (c) 2025 IntelliSwarm.ai (MIT License)
 * 
 * Licensed under the MIT License. See LICENSE file for details.
 */
package ai.intelliswarm.swarmai.examples.research;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.OutputFormat;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.examples.research.tools.WebSearchTool;
import ai.intelliswarm.swarmai.examples.research.tools.DataAnalysisTool;
import ai.intelliswarm.swarmai.examples.research.tools.ReportGeneratorTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Competitive Analysis Workflow Example
 * 
 * This example demonstrates a comprehensive multi-agent workflow for conducting
 * competitive analysis research. It showcases:
 * 
 * 1. Market Research Agent - Gathers market intelligence
 * 2. Data Analyst Agent - Processes and analyzes data
 * 3. Strategy Consultant Agent - Provides strategic insights
 * 4. Report Writer Agent - Creates comprehensive reports
 * 
 * The workflow follows a hierarchical process with a Project Manager agent
 * coordinating the team to analyze competitors in the AI/ML industry.
 */
@Component
public class CompetitiveAnalysisWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(CompetitiveAnalysisWorkflow.class);

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;
    private final WebSearchTool webSearchTool;
    private final DataAnalysisTool dataAnalysisTool;
    private final ReportGeneratorTool reportGeneratorTool;

    public CompetitiveAnalysisWorkflow(
            ChatClient.Builder chatClientBuilder, 
            ApplicationEventPublisher eventPublisher,
            WebSearchTool webSearchTool,
            DataAnalysisTool dataAnalysisTool,
            ReportGeneratorTool reportGeneratorTool) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
        this.webSearchTool = webSearchTool;
        this.dataAnalysisTool = dataAnalysisTool;
        this.reportGeneratorTool = reportGeneratorTool;
    }

    public void run(String... args) throws Exception {
        logger.info("🚀 Starting Competitive Analysis Workflow with SwarmAI Framework");
        
        try {
            runCompetitiveAnalysisWorkflow();
        } catch (Exception e) {
            logger.error("❌ Error running competitive analysis workflow", e);
            throw e;
        }
    }

    private void runCompetitiveAnalysisWorkflow() {
        // Use injected Spring bean tools

        // Create ChatClient instance
        ChatClient chatClient = chatClientBuilder.build();

        // 1. PROJECT MANAGER AGENT - Coordinates the entire workflow
        Agent projectManager = Agent.builder()
            .role("Senior Project Manager")
            .goal("Coordinate a comprehensive competitive analysis of AI/ML platforms and provide strategic recommendations")
            .backstory("You are an experienced project manager with 10+ years in tech strategy consulting. " +
                      "You excel at breaking down complex analysis projects and coordinating diverse teams of specialists.")
            .chatClient(chatClient)
            .verbose(true)
            .allowDelegation(true)
            .maxRpm(10)
            .temperature(0.3) // Lower temperature for consistent project management
            .build();

        // 2. MARKET RESEARCH AGENT - Gathers intelligence
        Agent marketResearcher = Agent.builder()
            .role("Senior Market Research Analyst")
            .goal("Conduct comprehensive market research on AI/ML platform competitors")
            .backstory("You are a market research specialist with expertise in the AI/ML industry. " +
                      "You have 8+ years of experience analyzing tech companies, market trends, and competitive landscapes. " +
                      "You're skilled at finding reliable data sources and identifying key market indicators.")
            .chatClient(chatClient)
            .tool(webSearchTool)
            .verbose(true)
            .maxRpm(15)
            .temperature(0.4)
            .build();

        // 3. DATA ANALYST AGENT - Processes and analyzes data
        Agent dataAnalyst = Agent.builder()
            .role("Senior Data Analyst")
            .goal("Analyze market data and competitor metrics to identify patterns and insights")
            .backstory("You are a data analysis expert with advanced skills in statistical analysis and data interpretation. " +
                      "With 6+ years in business intelligence, you excel at transforming raw data into actionable insights. " +
                      "You're particularly skilled at financial analysis and market sizing.")
            .chatClient(chatClient)
            .tool(dataAnalysisTool)
            .verbose(true)
            .maxRpm(12)
            .temperature(0.2) // Very low temperature for analytical precision
            .build();

        // 4. STRATEGY CONSULTANT AGENT - Provides strategic insights
        Agent strategist = Agent.builder()
            .role("Senior Strategy Consultant")
            .goal("Develop strategic recommendations based on competitive analysis findings")
            .backstory("You are a strategy consultant with 12+ years at top-tier consulting firms. " +
                      "You specialize in technology sector strategy and have helped numerous companies navigate competitive landscapes. " +
                      "You're known for your ability to identify strategic opportunities and threats.")
            .chatClient(chatClient)
            .verbose(true)
            .maxRpm(8)
            .temperature(0.5) // Higher temperature for creative strategic thinking
            .build();

        // 5. REPORT WRITER AGENT - Creates comprehensive reports
        Agent reportWriter = Agent.builder()
            .role("Senior Business Report Writer")
            .goal("Create professional, comprehensive reports that communicate findings clearly to executives")
            .backstory("You are an expert business writer with 7+ years of experience creating executive-level reports. " +
                      "You excel at synthesizing complex information into clear, actionable recommendations. " +
                      "Your reports are known for their clarity, professional formatting, and strategic focus.")
            .chatClient(chatClient)
            .tool(reportGeneratorTool)
            .verbose(true)
            .maxRpm(10)
            .temperature(0.6) // Moderate temperature for engaging writing
            .build();

        // CREATE TASKS WITH DEPENDENCIES
        
        // Task 1: Market Research & Data Collection
        Task marketResearchTask = Task.builder()
            .description("Conduct comprehensive market research on AI/ML platforms including:\n" +
                        "- Identify top 5-7 competitors in the AI/ML platform space\n" +
                        "- Gather information on their products, pricing, target markets\n" +
                        "- Collect recent news, funding rounds, partnerships\n" +
                        "- Research market size, growth trends, and key metrics\n" +
                        "- Focus on platforms like OpenAI, Anthropic, Google AI, Microsoft AI, AWS Bedrock")
            .expectedOutput("Detailed market research report with competitor profiles, market data, and key findings")
            .agent(marketResearcher)
            .outputFormat(OutputFormat.MARKDOWN)
            .maxExecutionTime(120000) // 2 minutes
            .build();

        // Task 2: Data Analysis & Pattern Recognition
        Task dataAnalysisTask = Task.builder()
            .description("Analyze the market research data to identify:\n" +
                        "- Market share and positioning of each competitor\n" +
                        "- Pricing strategy analysis and comparison\n" +
                        "- Product feature comparison matrix\n" +
                        "- Growth trends and market dynamics\n" +
                        "- Strengths and weaknesses analysis\n" +
                        "- Market gaps and opportunities")
            .expectedOutput("Analytical report with data visualizations, comparison matrices, and quantified insights")
            .agent(dataAnalyst)
            .dependsOn(marketResearchTask)
            .outputFormat(OutputFormat.MARKDOWN)
            .maxExecutionTime(180000) // 3 minutes
            .build();

        // Task 3: Strategic Analysis & Recommendations
        Task strategyTask = Task.builder()
            .description("Based on the market research and data analysis, develop:\n" +
                        "- Strategic positioning recommendations\n" +
                        "- Competitive differentiation opportunities\n" +
                        "- Market entry or expansion strategies\n" +
                        "- Risk assessment and mitigation strategies\n" +
                        "- Short-term and long-term strategic recommendations\n" +
                        "- Key success factors for competing in this market")
            .expectedOutput("Strategic analysis with actionable recommendations and implementation roadmap")
            .agent(strategist)
            .dependsOn(dataAnalysisTask)
            .outputFormat(OutputFormat.MARKDOWN)
            .maxExecutionTime(180000) // 3 minutes
            .build();

        // Task 4: Executive Report Generation
        Task reportTask = Task.builder()
            .description("Create a comprehensive executive report that includes:\n" +
                        "- Executive summary with key findings\n" +
                        "- Market overview and competitive landscape\n" +
                        "- Detailed competitor analysis\n" +
                        "- Strategic recommendations with rationale\n" +
                        "- Implementation timeline and resource requirements\n" +
                        "- Risk assessment and success metrics\n" +
                        "- Professional formatting suitable for C-level presentation")
            .expectedOutput("Professional executive report in markdown format, ready for presentation")
            .agent(reportWriter)
            .dependsOn(strategyTask)
            .outputFormat(OutputFormat.MARKDOWN)
            .outputFile("competitive_analysis_report.md")
            .maxExecutionTime(240000) // 4 minutes
            .build();

        // CREATE SWARM WITH HIERARCHICAL PROCESS
        Swarm competitiveAnalysisSwarm = Swarm.builder()
            .id("competitive-analysis-swarm")
            .agent(marketResearcher)
            .agent(dataAnalyst)
            .agent(strategist)
            .agent(reportWriter)
            .managerAgent(projectManager)
            .task(marketResearchTask)
            .task(dataAnalysisTask)
            .task(strategyTask)
            .task(reportTask)
            .process(ProcessType.HIERARCHICAL) // Manager coordinates the workflow
            .verbose(true)
            .maxRpm(20)
            .language("en")
            .eventPublisher(eventPublisher)
            .config("analysisType", "competitive")
            .config("industry", "AI/ML")
            .config("outputFormat", "executive-report")
            .build();

        // EXECUTE WORKFLOW
        logger.info("🎯 Executing Competitive Analysis Workflow");
        logger.info("👥 Team: Project Manager + 4 Specialized Agents");
        logger.info("📊 Process: Hierarchical coordination");
        logger.info("⏱️ Expected Duration: ~10-15 minutes");

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("targetMarket", "AI/ML Platform Services");
        inputs.put("analysisScope", "Global market with focus on enterprise customers");
        inputs.put("timeframe", "Current market state with 2-year outlook");
        inputs.put("businessObjective", "Strategic market entry planning");

        long startTime = System.currentTimeMillis();
        SwarmOutput result = competitiveAnalysisSwarm.kickoff(inputs);
        long endTime = System.currentTimeMillis();

        // DISPLAY RESULTS
        logger.info("\n" + "=".repeat(80));
        logger.info("🎉 COMPETITIVE ANALYSIS WORKFLOW COMPLETED");
        logger.info("=".repeat(80));
        
        logger.info("📈 Execution Statistics:");
        logger.info("  • Total Execution Time: {} seconds", (endTime - startTime) / 1000);
        logger.info("  • Success Rate: {:.1f}%", result.getSuccessRate() * 100);
        logger.info("  • Tasks Completed: {}/{}", 
            result.getSuccessfulOutputs().size(), result.getTaskOutputs().size());
        logger.info("  • Swarm ID: {}", result.getSwarmId());

        if (result.isSuccessful()) {
            logger.info("\n📋 EXECUTIVE SUMMARY:");
            logger.info("{}", truncateOutput(result.getFinalOutput(), 500));
            
            logger.info("\n📊 Task Breakdown:");
            result.getTaskOutputs().forEach(output -> {
                logger.info("  ✅ {}: {}", 
                    output.getDescription().split("\n")[0], 
                    output.isSuccessful() ? "Success" : "Failed");
            });

            if (result.getFinalOutput().length() > 1000) {
                logger.info("\n📄 Full report has been generated and saved to 'competitive_analysis_report.md'");
            }
        } else {
            logger.error("❌ Workflow completed with errors:");
            result.getFailedOutputs().forEach(output -> {
                logger.error("  • Failed Task: {}", output.getDescription().split("\n")[0]);
            });
        }

        logger.info("\n🎯 This workflow demonstrates:");
        logger.info("  • Multi-agent collaboration with specialized roles");
        logger.info("  • Hierarchical process management");
        logger.info("  • Task dependencies and data flow");
        logger.info("  • Tool integration for enhanced capabilities");
        logger.info("  • Professional output generation");
        logger.info("=".repeat(80));
    }

    private String truncateOutput(String output, int maxLength) {
        if (output == null || output.length() <= maxLength) {
            return output;
        }
        return output.substring(0, maxLength) + "\n... [truncated - see full report for complete analysis]";
    }
}