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
import ai.intelliswarm.swarmai.tool.common.WebSearchTool;
import ai.intelliswarm.swarmai.tool.common.DataAnalysisTool;
import ai.intelliswarm.swarmai.tool.common.ReportGeneratorTool;
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
        logger.info("Starting Research Workflow with SwarmAI Framework");

        try {
            // Accept custom research query from CLI args, or use default
            String researchQuery;
            if (args.length > 0) {
                researchQuery = String.join(" ", args);
            } else {
                researchQuery = "AI/ML platform competitive landscape for enterprise customers";
            }
            runResearchWorkflow(researchQuery);
        } catch (Exception e) {
            logger.error("Error running research workflow", e);
            throw e;
        }
    }

    private void runResearchWorkflow(String researchQuery) {
        logger.info("Research query: {}", researchQuery);
        ChatClient chatClient = chatClientBuilder.build();

        // =====================================================================
        // AGENTS - Accuracy-focused goals with data grounding requirements
        // =====================================================================

        Agent projectManager = Agent.builder()
            .role("Senior Research Program Manager")
            .goal("Coordinate a rigorous research workflow that produces data-backed findings and " +
                  "strategic recommendations on the topic: '" + researchQuery + "'. " +
                  "Ensure each specialist delivers evidence-based findings with cited sources.")
            .backstory("You are a research program manager with 10+ years coordinating multi-disciplinary " +
                      "research teams. You demand evidence-backed analysis. You reject deliverables " +
                      "that contain unsubstantiated claims or generic advice. " +
                      "Every finding must tie back to a specific data point or credible source.")
            .chatClient(chatClient)
            .verbose(true)
            .allowDelegation(true)
            .maxRpm(10)
            .temperature(0.2)
            .modelName("gpt-4o-mini")
            .build();

        Agent marketResearcher = Agent.builder()
            .role("Senior Market Intelligence Analyst")
            .goal("Provide comprehensive market intelligence on the research topic using your expert knowledge. " +
                  "For every claim, state whether it is [CONFIRMED] public knowledge or [ESTIMATE/OPINION]. " +
                  "Name real companies, real products, and real market data you know about. " +
                  "If you don't know something, say 'UNKNOWN' rather than inventing data.")
            .backstory("You are a market intelligence analyst with 8 years of experience covering " +
                      "technology markets. You draw on your deep knowledge of the industry landscape. " +
                      "You name real companies, cite approximate dates for events you know about, " +
                      "and clearly distinguish between facts you're confident about and estimates. " +
                      "You NEVER invent company names like 'Company A' — you use real names or say you don't know.")
            .chatClient(chatClient)
            .tool(webSearchTool)
            .verbose(true)
            .maxRpm(15)
            .temperature(0.3)
            .modelName("gpt-4o-mini")
            .build();

        Agent dataAnalyst = Agent.builder()
            .role("Senior Research Analyst")
            .goal("Analyze the market research findings and organize them into structured comparisons. " +
                  "Create comparison frameworks using ONLY the real companies and data from the prior research. " +
                  "State confidence level (HIGH/MEDIUM/LOW) for each finding. " +
                  "Do NOT invent data points or use placeholder names like 'Company A'.")
            .backstory("You are a research analyst with 6 years of experience in technology and market analysis. " +
                      "You build analytical frameworks using real data. When creating comparison tables, " +
                      "you use real company names from the prior research. You explain your scoring methodology. " +
                      "When data is missing, you write 'N/A' rather than guessing.")
            .chatClient(chatClient)
            .tool(dataAnalysisTool)
            .verbose(true)
            .maxRpm(12)
            .temperature(0.1)
            .modelName("gpt-4o-mini")
            .build();

        Agent strategist = Agent.builder()
            .role("Senior Strategy Consultant")
            .goal("Develop 3-5 specific, prioritized strategic recommendations based on the research findings. " +
                  "Each recommendation must reference specific data from prior analyses and include " +
                  "estimated effort, timeline, and expected impact.")
            .backstory("You are a strategy consultant with 12 years at top-tier firms. " +
                      "You are known for actionable recommendations: each one includes a specific " +
                      "'what', 'why' (tied to data), 'how' (implementation steps), and 'when' (timeline). " +
                      "You prioritize recommendations by expected ROI and feasibility.")
            .chatClient(chatClient)
            .verbose(true)
            .maxRpm(8)
            .temperature(0.4)
            .modelName("gpt-4o-mini")
            .build();

        Agent reportWriter = Agent.builder()
            .role("Senior Executive Communications Specialist")
            .goal("Synthesize all prior analyses into a structured executive report. Use ONLY data " +
                  "from prior task outputs. Include an executive summary with 5 key takeaways, each " +
                  "backed by a specific data point from the analysis.")
            .backstory("You are an executive communications specialist with 7 years creating " +
                      "board-level strategy documents. Every claim in the executive summary must " +
                      "cross-reference a finding from the detailed analysis sections. " +
                      "You write concisely and lead with insights, not methodology.")
            .chatClient(chatClient)
            .tool(reportGeneratorTool)
            .verbose(true)
            .maxRpm(10)
            .temperature(0.4)
            .modelName("gpt-4o-mini")
            .build();

        // =====================================================================
        // TASKS - Numbered requirements with quality rubrics
        // =====================================================================

        Task marketResearchTask = Task.builder()
            .description(String.format(
                        "Conduct comprehensive market research on the following topic:\n" +
                        "\"%s\"\n\n" +
                        "REQUIRED DELIVERABLES (address each numbered item):\n" +
                        "1. Key Players & Stakeholders (5-7 companies/organizations): For each, provide:\n" +
                        "   - Name, description, and relevance to the research topic\n" +
                        "   - Key products/services/initiatives related to the topic\n" +
                        "   - Funding, market position, or scale indicators (cite source)\n" +
                        "   - Recent activity related to the topic (with dates)\n" +
                        "2. Market Context: Market size, growth rate, and key trends (cite sources)\n" +
                        "3. Recent Developments: 5-7 significant recent events related to the topic (with dates)\n" +
                        "4. Technology/Product Landscape: Key technologies, products, or approaches\n" +
                        "5. Regulatory/Industry Context: Relevant regulations, standards, or industry shifts\n\n" +
                        "DATA RULES:\n" +
                        "- Cite the source for every data point\n" +
                        "- Mark estimates with [ESTIMATE] and confirmed data with [CONFIRMED]\n" +
                        "- If data is unavailable, write 'N/A (no public data)'\n" +
                        "- Do NOT invent data. If you don't have information, say so explicitly\n" +
                        "- If web search returns no results about the topic, state clearly: " +
                        "'No public information was found about [topic]. This may be a pre-announcement, " +
                        "internal codename, or misspelling.' Then provide what you DO know about the " +
                        "broader domain (e.g., enterprise automation) while being explicit about the distinction.\n" +
                        "- NEVER use placeholder names like 'Company A' or 'Company B'. Use real company names only.",
                        researchQuery))
            .expectedOutput("Markdown report with:\n" +
                        "1. Key Players Table (name, description, relevance, recent activity)\n" +
                        "2. Market Context section with cited figures\n" +
                        "3. Recent Developments timeline (dated events)\n" +
                        "4. Technology/Product Landscape overview\n" +
                        "5. Data Availability Notes")
            .agent(marketResearcher)
            .outputFormat(OutputFormat.MARKDOWN)
            .maxExecutionTime(120000)
            .build();

        Task dataAnalysisTask = Task.builder()
            .description(String.format(
                        "Analyze the market research data from the prior task on the topic:\n" +
                        "\"%s\"\n\n" +
                        "REQUIRED DELIVERABLES:\n" +
                        "1. Comparison Matrix: Compare the key players identified across 6+ relevant dimensions\n" +
                        "   - Use a consistent scoring rubric (1-5 scale) with clear criteria\n" +
                        "   - Explain scoring methodology\n" +
                        "2. Impact Assessment: Assess the potential impact on the topic across dimensions:\n" +
                        "   - Short-term (0-6 months) vs. long-term (1-3 years)\n" +
                        "   - Which stakeholders are most affected and how\n" +
                        "3. Trend Analysis: Identify 3-5 key trends and their trajectory\n" +
                        "4. SWOT Analysis: For the top 3 players/approaches, provide 2 items per quadrant\n" +
                        "5. Opportunity Gaps: Identify 3+ underserved areas or unaddressed challenges\n\n" +
                        "DATA RULES:\n" +
                        "- Base ALL analysis on data from the prior Market Research task\n" +
                        "- Do NOT introduce new data points not present in the research\n" +
                        "- Clearly mark inferences vs. direct data",
                        researchQuery))
            .expectedOutput("Analytical report with:\n" +
                        "1. Comparison Matrix (table with scoring)\n" +
                        "2. Impact Assessment (short-term vs. long-term)\n" +
                        "3. Trend Analysis (3-5 trends with trajectory)\n" +
                        "4. SWOT Summaries for top 3\n" +
                        "5. Opportunity Gaps (3+ identified)")
            .agent(dataAnalyst)
            .dependsOn(marketResearchTask)
            .outputFormat(OutputFormat.MARKDOWN)
            .maxExecutionTime(180000)
            .build();

        Task strategyTask = Task.builder()
            .description(String.format(
                        "Develop strategic recommendations based on the research findings about:\n" +
                        "\"%s\"\n\n" +
                        "REQUIRED DELIVERABLES:\n" +
                        "1. Key Conclusions: 3-5 evidence-based conclusions from the research\n" +
                        "   - Each must reference specific findings from prior tasks\n" +
                        "2. Strategic Implications: How these findings affect key stakeholders\n" +
                        "   - For each implication: what it means, who it affects, timeline\n" +
                        "3. Recommended Actions: 3-5 specific, prioritized recommendations\n" +
                        "   - Each with: what to do, why (data reference), how, when (timeline)\n" +
                        "4. Prioritized Roadmap: Rank actions by impact and feasibility\n" +
                        "   - Each with: description, timeline (Q1-Q4), effort (Low/Med/High)\n" +
                        "5. Risk Assessment: 3 risks with mitigation strategies\n\n" +
                        "RULES:\n" +
                        "- Every recommendation must reference a specific finding from prior tasks\n" +
                        "- Be specific to the research topic, avoid generic business advice\n" +
                        "- Prioritize actionability over comprehensiveness",
                        researchQuery))
            .expectedOutput("Strategic recommendations with:\n" +
                        "1. Key Conclusions (3-5 with evidence)\n" +
                        "2. Strategic Implications (stakeholder impact)\n" +
                        "3. Recommended Actions (3-5 prioritized)\n" +
                        "4. Prioritized Roadmap Table\n" +
                        "5. Risk Matrix")
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
            .outputFile("output/competitive_analysis_report.md")
            .maxExecutionTime(240000)
            .build();

        // CREATE SWARM WITH HIERARCHICAL PROCESS
        Swarm researchSwarm = Swarm.builder()
            .id("research-swarm")
            .agent(marketResearcher)
            .agent(dataAnalyst)
            .agent(strategist)
            .agent(reportWriter)
            .managerAgent(projectManager)
            .task(marketResearchTask)
            .task(dataAnalysisTask)
            .task(strategyTask)
            .task(reportTask)
            .process(ProcessType.HIERARCHICAL)
            .verbose(true)
            .maxRpm(20)
            .language("en")
            .eventPublisher(eventPublisher)
            .config("analysisType", "research")
            .config("query", researchQuery)
            .build();

        // EXECUTE WORKFLOW
        logger.info("Executing Research Workflow");
        logger.info("Query: {}", researchQuery);
        logger.info("Team: Research Manager + 4 Specialized Agents");
        logger.info("Process: Hierarchical coordination");

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("researchQuery", researchQuery);
        inputs.put("timeframe", "Current state with forward-looking analysis");

        long startTime = System.currentTimeMillis();
        SwarmOutput result = researchSwarm.kickoff(inputs);
        long endTime = System.currentTimeMillis();

        // DISPLAY RESULTS
        logger.info("\n" + "=".repeat(80));
        logger.info("RESEARCH WORKFLOW COMPLETED");
        logger.info("=".repeat(80));
        logger.info("Query: {}", researchQuery);
        logger.info("Duration: {} seconds", (endTime - startTime) / 1000);
        logger.info("Tasks completed: {}", result.getTaskOutputs().size());
        logger.info("\n{}", result.getTokenUsageSummary("gpt-4o-mini"));
        logger.info("Final Report:\n{}", result.getFinalOutput());
        logger.info("=".repeat(80));
    }

    private String truncateOutput(String output, int maxLength) {
        if (output == null || output.length() <= maxLength) {
            return output;
        }
        return output.substring(0, maxLength) + "\n... [truncated - see full report for complete analysis]";
    }
}