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
        ChatClient chatClient = chatClientBuilder.build();

        // =====================================================================
        // AGENTS - Accuracy-focused goals with data grounding requirements
        // =====================================================================

        Agent projectManager = Agent.builder()
            .role("Senior Strategy Program Manager")
            .goal("Coordinate a rigorous competitive analysis that produces data-backed strategic " +
                  "recommendations. Ensure each specialist delivers quantified findings with sources. " +
                  "Reject generic advice that lacks supporting evidence.")
            .backstory("You are a strategy program manager with 10+ years at McKinsey and BCG. " +
                      "You demand evidence-backed analysis from your team. You reject deliverables " +
                      "that contain unsubstantiated market claims or generic consulting jargon. " +
                      "Every recommendation must tie back to a specific data point or competitive insight.")
            .chatClient(chatClient)
            .verbose(true)
            .allowDelegation(true)
            .maxRpm(10)
            .temperature(0.2)
            .build();

        Agent marketResearcher = Agent.builder()
            .role("Senior Market Intelligence Analyst")
            .goal("Gather verifiable competitive intelligence on AI/ML platforms including funding, " +
                  "revenue estimates, product capabilities, and pricing. Cite the source for every " +
                  "data point. Distinguish between confirmed data and analyst estimates.")
            .backstory("You are a market intelligence analyst with 8 years covering enterprise AI. " +
                      "You specialize in building competitor profiles backed by public data: SEC filings, " +
                      "press releases, Crunchbase, analyst reports. You never present rumors as facts. " +
                      "When citing estimates, you name the source and date.")
            .chatClient(chatClient)
            .tool(webSearchTool)
            .verbose(true)
            .maxRpm(15)
            .temperature(0.3)
            .build();

        Agent dataAnalyst = Agent.builder()
            .role("Senior Competitive Intelligence Analyst")
            .goal("Transform raw market research into quantitative competitor comparisons. " +
                  "Create structured comparison matrices with specific numbers. " +
                  "State confidence level for each estimate. Flag data gaps explicitly.")
            .backstory("You are a competitive intelligence analyst with 6 years in enterprise " +
                      "software. You build decision-grade comparison frameworks. Every cell in your " +
                      "comparison matrices has a value and a source. You use consistent scoring " +
                      "rubrics (1-5 or 1-10) and explain your methodology. When data is missing, " +
                      "you write 'N/A (no public data)' rather than guessing.")
            .chatClient(chatClient)
            .tool(dataAnalysisTool)
            .verbose(true)
            .maxRpm(12)
            .temperature(0.1)
            .build();

        Agent strategist = Agent.builder()
            .role("Senior Strategy Consultant")
            .goal("Develop 3-5 specific, prioritized strategic recommendations. Each recommendation " +
                  "must reference specific data from prior analyses and include estimated effort, " +
                  "timeline, and expected impact. Avoid generic strategy frameworks without data backing.")
            .backstory("You are a strategy consultant with 12 years at top-tier firms specializing " +
                      "in technology sector strategy. You are known for actionable recommendations: " +
                      "each one includes a specific 'what', 'why' (tied to competitive data), 'how' " +
                      "(implementation steps), and 'when' (timeline). You prioritize recommendations " +
                      "by expected ROI and feasibility.")
            .chatClient(chatClient)
            .verbose(true)
            .maxRpm(8)
            .temperature(0.4)
            .build();

        Agent reportWriter = Agent.builder()
            .role("Senior Executive Communications Specialist")
            .goal("Synthesize all prior analyses into a structured executive report. Use ONLY data " +
                  "from prior task outputs. Include an executive summary with 5 key takeaways, each " +
                  "backed by a specific data point from the analysis.")
            .backstory("You are an executive communications specialist with 7 years creating " +
                      "board-level strategy documents. You follow a strict rule: every claim in the " +
                      "executive summary must cross-reference a finding from the detailed analysis " +
                      "sections. You write concisely and lead with insights, not methodology.")
            .chatClient(chatClient)
            .tool(reportGeneratorTool)
            .verbose(true)
            .maxRpm(10)
            .temperature(0.4)
            .build();

        // =====================================================================
        // TASKS - Numbered requirements with quality rubrics
        // =====================================================================

        Task marketResearchTask = Task.builder()
            .description("Conduct competitive intelligence research on AI/ML platforms.\n\n" +
                        "REQUIRED DELIVERABLES (address each numbered item):\n" +
                        "1. Competitor Profiles (5-7 companies): For each, provide:\n" +
                        "   - Company name, founding year, headquarters\n" +
                        "   - Primary products/services with brief descriptions\n" +
                        "   - Funding raised or public market cap (cite source)\n" +
                        "   - Revenue or ARR estimate if available (cite source)\n" +
                        "   - Target customer segment (enterprise, SMB, developer, consumer)\n" +
                        "2. Market Size: Total addressable market estimate with source and year\n" +
                        "3. Growth Rate: Market CAGR with source\n" +
                        "4. Recent Developments: 2-3 significant events per competitor (with dates)\n" +
                        "5. Pricing: Published pricing tiers for at least 3 competitors\n\n" +
                        "FOCUS ON: OpenAI, Anthropic, Google (Vertex AI/Gemini), Microsoft (Azure AI), " +
                        "AWS Bedrock, Meta AI, Cohere\n\n" +
                        "DATA RULES:\n" +
                        "- Cite the source for every data point\n" +
                        "- Mark estimates with [ESTIMATE] and confirmed data with [CONFIRMED]\n" +
                        "- If data is unavailable, write 'N/A (no public data)'\n" +
                        "- Do NOT invent funding amounts, revenue figures, or market share numbers")
            .expectedOutput("Markdown report with:\n" +
                        "1. Competitor Profile Table (name, founded, funding, revenue, target segment)\n" +
                        "2. Market Size & Growth section with cited figures\n" +
                        "3. Recent Developments timeline (dated events)\n" +
                        "4. Pricing Comparison Table\n" +
                        "5. Data Availability Notes")
            .agent(marketResearcher)
            .outputFormat(OutputFormat.MARKDOWN)
            .maxExecutionTime(120000)
            .build();

        Task dataAnalysisTask = Task.builder()
            .description("Analyze the market research data from the prior task to produce structured comparisons.\n\n" +
                        "REQUIRED DELIVERABLES:\n" +
                        "1. Feature Comparison Matrix: Compare 5+ competitors across 8+ capabilities\n" +
                        "   - Use a consistent scoring rubric (1-5 scale) with clear criteria for each level\n" +
                        "   - Explain scoring methodology in a footnote\n" +
                        "2. Market Positioning Map: Categorize competitors by:\n" +
                        "   - Horizontal axis: Breadth of offering (narrow specialist vs. full platform)\n" +
                        "   - Vertical axis: Target market (developer/SMB vs. enterprise)\n" +
                        "3. Pricing Analysis: Normalize pricing across competitors to a common unit ($/1M tokens or $/seat/month)\n" +
                        "4. SWOT Summary: For the top 3 competitors, provide 2 items per quadrant\n" +
                        "5. Market Gaps: Identify 3+ underserved segments or capability gaps\n\n" +
                        "DATA RULES:\n" +
                        "- Base ALL analysis on data from the prior Market Research task\n" +
                        "- Do NOT introduce new data points not present in the research\n" +
                        "- Clearly mark any inferences vs. direct data")
            .expectedOutput("Analytical report with:\n" +
                        "1. Feature Comparison Matrix (table, 5+ companies, 8+ features, scored 1-5)\n" +
                        "2. Market Positioning description\n" +
                        "3. Normalized Pricing Table\n" +
                        "4. SWOT Summaries for top 3\n" +
                        "5. Market Gaps (3+ identified)")
            .agent(dataAnalyst)
            .dependsOn(marketResearchTask)
            .outputFormat(OutputFormat.MARKDOWN)
            .maxExecutionTime(180000)
            .build();

        Task strategyTask = Task.builder()
            .description("Develop strategic recommendations based on the market research and data analysis.\n\n" +
                        "REQUIRED DELIVERABLES:\n" +
                        "1. Strategic Positioning: Recommend where to position (which market gaps to target)\n" +
                        "   - Reference specific gaps identified in the Data Analysis\n" +
                        "2. Competitive Differentiation: 3 specific differentiators to pursue\n" +
                        "   - For each: what it is, why it matters (cite competitor weakness), how to build it\n" +
                        "3. Go-to-Market Strategy: Target segment, pricing approach, channel strategy\n" +
                        "   - Reference pricing data from the analysis\n" +
                        "4. Prioritized Roadmap: 3-5 strategic initiatives ranked by impact and feasibility\n" +
                        "   - Each with: description, estimated timeline (Q1-Q4), required investment (Low/Med/High)\n" +
                        "5. Risk Assessment: 3 strategic risks with mitigation strategies\n\n" +
                        "RULES:\n" +
                        "- Every recommendation must reference a specific finding from prior tasks\n" +
                        "- Avoid generic frameworks (Porter's Five Forces, etc.) unless populated with actual data\n" +
                        "- Prioritize actionability over comprehensiveness")
            .expectedOutput("Strategic plan with:\n" +
                        "1. Positioning Recommendation (with data references)\n" +
                        "2. Differentiation Strategy (3 differentiators with evidence)\n" +
                        "3. Go-to-Market Plan (segment, pricing, channels)\n" +
                        "4. Prioritized Roadmap Table (initiatives, timeline, investment)\n" +
                        "5. Risk Matrix (risks, likelihood, impact, mitigation)")
            .agent(strategist)
            .dependsOn(dataAnalysisTask)
            .outputFormat(OutputFormat.MARKDOWN)
            .maxExecutionTime(180000)
            .build();

        Task reportTask = Task.builder()
            .description("Create the final executive report by synthesizing ALL prior task outputs.\n\n" +
                        "REQUIRED STRUCTURE:\n" +
                        "1. Executive Summary (max 300 words):\n" +
                        "   - 5 key takeaways, each backed by a specific data point\n" +
                        "   - Overall strategic recommendation in one sentence\n" +
                        "2. Market Landscape (from Market Research task):\n" +
                        "   - Market size, growth, key players\n" +
                        "3. Competitive Analysis (from Data Analysis task):\n" +
                        "   - Feature comparison highlights\n" +
                        "   - Key competitive gaps identified\n" +
                        "4. Strategic Recommendations (from Strategy task):\n" +
                        "   - Prioritized initiatives with timelines\n" +
                        "5. Risk Assessment:\n" +
                        "   - Top 3 risks with mitigation plans\n" +
                        "6. Appendix: Full comparison tables from the analysis\n\n" +
                        "RULES:\n" +
                        "- Use ONLY information from prior task outputs\n" +
                        "- Cross-reference sections (e.g., 'As identified in the Competitive Analysis...')\n" +
                        "- Write for a C-level audience: lead with insights, minimize methodology\n" +
                        "- Include page/section references for every key claim")
            .expectedOutput("Professional executive report in markdown with:\n" +
                        "Executive Summary (5 takeaways), Market Landscape, Competitive Analysis, " +
                        "Strategic Recommendations, Risk Assessment, Appendix with data tables")
            .agent(reportWriter)
            .dependsOn(strategyTask)
            .outputFormat(OutputFormat.MARKDOWN)
            .outputFile("competitive_analysis_report.md")
            .maxExecutionTime(240000)
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