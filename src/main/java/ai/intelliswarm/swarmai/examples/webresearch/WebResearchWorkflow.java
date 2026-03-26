package ai.intelliswarm.swarmai.examples.webresearch;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.OutputFormat;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.tool.common.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Web Research & Report Workflow
 *
 * Demonstrates HIERARCHICAL process with web-focused tools.
 * A research director coordinates specialists who scrape web sources,
 * structure data into tables, cross-reference facts, and produce a report.
 *
 * Tools showcased: WebScrapeTool, HttpRequestTool, JSONTransformTool,
 *                  CSVAnalysisTool, FileWriteTool, FileReadTool
 *
 * Usage: docker compose -f docker-compose.run.yml run --rm web-research "AI agent frameworks 2026"
 */
@Component
public class WebResearchWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(WebResearchWorkflow.class);

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;
    private final WebScrapeTool webScrapeTool;
    private final HttpRequestTool httpRequestTool;
    private final JSONTransformTool jsonTransformTool;
    private final FileWriteTool fileWriteTool;
    private final FileReadTool fileReadTool;

    public WebResearchWorkflow(
            ChatClient.Builder chatClientBuilder,
            ApplicationEventPublisher eventPublisher,
            WebScrapeTool webScrapeTool,
            HttpRequestTool httpRequestTool,
            JSONTransformTool jsonTransformTool,
            FileWriteTool fileWriteTool,
            FileReadTool fileReadTool) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
        this.webScrapeTool = webScrapeTool;
        this.httpRequestTool = httpRequestTool;
        this.jsonTransformTool = jsonTransformTool;
        this.fileWriteTool = fileWriteTool;
        this.fileReadTool = fileReadTool;
    }

    public void run(String... args) throws Exception {
        logger.info("Starting Web Research Workflow");

        String query = args.length > 0 ? String.join(" ", args) : "AI agent frameworks comparison 2026";
        logger.info("Research query: {}", query);

        runWebResearch(query);
    }

    private void runWebResearch(String query) {
        ChatClient chatClient = chatClientBuilder.build();

        // =====================================================================
        // AGENTS
        // =====================================================================

        Agent researchDirector = Agent.builder()
            .role("Senior Research Program Director")
            .goal("Coordinate a rigorous web research workflow on the topic: '" + query + "'. " +
                  "Ensure each specialist delivers evidence-backed findings with cited URLs.")
            .backstory("You are a research director with 10+ years managing intelligence teams. " +
                      "You demand cited sources, reject unsubstantiated claims, and ensure the final " +
                      "deliverable is a data-backed report suitable for executive presentation.")
            .chatClient(chatClient)
            .verbose(true)
            .allowDelegation(true)
            .maxRpm(10)
            .temperature(0.2)
            .modelName("gpt-4o-mini")
            .build();

        Agent webResearcher = Agent.builder()
            .role("Senior Web Research Specialist")
            .goal("Gather primary source data on: '" + query + "'. " +
                  "Use web_scrape to fetch content from relevant web pages. " +
                  "Use http_request to access any public APIs that provide structured data. " +
                  "For each finding, include the source URL.")
            .backstory("You are a web research specialist with 8 years of experience in open-source " +
                      "intelligence (OSINT). You know how to find authoritative sources, extract key " +
                      "data points, and distinguish between primary sources and opinions. " +
                      "You ALWAYS include the URL for every piece of information.")
            .chatClient(chatClient)
            .tool(webScrapeTool)
            .tool(httpRequestTool)
            .verbose(true)
            .maxRpm(15)
            .temperature(0.3)
            .modelName("gpt-4o-mini")
            .build();

        Agent dataAnalyst = Agent.builder()
            .role("Senior Data Analyst")
            .goal("Organize raw research findings into structured comparison tables and matrices. " +
                  "Use json_transform to parse and restructure data. Create clear comparison tables " +
                  "with dimensions like: features, pricing, strengths, weaknesses, market position.")
            .backstory("You are a data analyst with 6 years of experience structuring qualitative " +
                      "research data. You create clear comparison matrices with scoring rubrics. " +
                      "You explain your methodology for any ratings you assign. " +
                      "You use real company/product names, NEVER placeholders like 'Company A'.")
            .chatClient(chatClient)
            .tool(jsonTransformTool)
            .verbose(true)
            .maxRpm(12)
            .temperature(0.1)
            .modelName("gpt-4o-mini")
            .build();

        Agent factChecker = Agent.builder()
            .role("Senior Fact Checker & Verification Specialist")
            .goal("Cross-reference key claims from the research. Use web_scrape to verify facts " +
                  "against additional sources. Mark each finding as [CONFIRMED], [PARTIALLY CONFIRMED], " +
                  "or [UNVERIFIED]. Flag any contradictions between sources.")
            .backstory("You are a fact-checker with 7 years at a major research firm. You verify " +
                      "claims by checking multiple independent sources. You never assume a claim is " +
                      "true just because one source says so. You document your verification process.")
            .chatClient(chatClient)
            .tool(webScrapeTool)
            .tool(httpRequestTool)
            .verbose(true)
            .maxRpm(12)
            .temperature(0.1)
            .modelName("gpt-4o-mini")
            .build();

        Agent reportWriter = Agent.builder()
            .role("Senior Executive Report Writer")
            .goal("Synthesize ALL prior research into a polished executive report. Your ENTIRE " +
                  "response must BE the complete report in markdown with executive summary, " +
                  "comparison tables, fact check results, and recommendations.")
            .backstory("You are a report writer who creates board-level strategy documents. " +
                      "Every claim in the executive summary cross-references a finding from the " +
                      "detailed analysis. You write concisely and lead with insights, not methodology. " +
                      "You ALWAYS write the complete report as your response — never a summary.")
            .chatClient(chatClient)
            .verbose(true)
            .maxRpm(10)
            .temperature(0.4)
            .modelName("gpt-4o-mini")
            .build();

        // =====================================================================
        // TASKS — Hierarchical chain
        // =====================================================================

        Task researchTask = Task.builder()
            .description(String.format(
                "Conduct comprehensive web research on: \"%s\"\n\n" +
                "USE YOUR TOOLS:\n" +
                "1. Use web_scrape to fetch content from 3-5 relevant web pages about this topic\n" +
                "2. Use http_request to access any relevant public APIs or data sources\n\n" +
                "Try scraping these types of sources:\n" +
                "- Wikipedia pages related to the topic\n" +
                "- Technology news sites (TechCrunch, Ars Technica, The Verge)\n" +
                "- Official product/project pages\n" +
                "- Developer documentation or GitHub pages\n\n" +
                "REQUIRED DELIVERABLES:\n" +
                "1. **Key Players** — 5-7 companies/projects with descriptions\n" +
                "2. **Recent Developments** — 5+ recent events with dates and sources\n" +
                "3. **Market Data** — Any quantitative data found (market size, growth, adoption)\n" +
                "4. **Source Registry** — Table of all URLs scraped with titles\n\n" +
                "RULES:\n" +
                "- Include the URL for EVERY finding\n" +
                "- Mark your confidence: [CONFIRMED] or [FROM SINGLE SOURCE]\n" +
                "- If a scrape fails, note it and try a different source",
                query))
            .expectedOutput("Research findings with URLs, organized by category")
            .agent(webResearcher)
            .outputFormat(OutputFormat.MARKDOWN)
            .maxExecutionTime(180000)
            .build();

        Task analysisTask = Task.builder()
            .description(
                "Organize the research findings into structured comparison tables.\n\n" +
                "REQUIRED DELIVERABLES:\n" +
                "1. **Comparison Matrix** — Table comparing key players across 5+ dimensions\n" +
                "   (use real names, not placeholders)\n" +
                "2. **Feature Comparison** — What each player offers\n" +
                "3. **Strengths & Weaknesses** — For each key player\n" +
                "4. **Market Positioning** — Leaders, challengers, niche players\n" +
                "5. **Scoring Methodology** — Explain any ratings you assign\n\n" +
                "RULES:\n" +
                "- Use ONLY data from the prior research task\n" +
                "- Include markdown tables with clear headers\n" +
                "- Score on a 1-5 scale with justification")
            .expectedOutput("Structured analysis with comparison tables and scoring")
            .agent(dataAnalyst)
            .dependsOn(researchTask)
            .outputFormat(OutputFormat.MARKDOWN)
            .maxExecutionTime(180000)
            .build();

        Task factCheckTask = Task.builder()
            .description(
                "Verify the key claims from the research findings.\n\n" +
                "USE YOUR TOOLS:\n" +
                "1. Pick the 5 most important claims from the research\n" +
                "2. Use web_scrape to check each against an independent source\n" +
                "3. For each claim, record: claim, original source, verification source, status\n\n" +
                "REQUIRED DELIVERABLES:\n" +
                "1. **Verification Table** — Each claim with status:\n" +
                "   [CONFIRMED] — Verified by 2+ independent sources\n" +
                "   [PARTIALLY CONFIRMED] — Some aspects verified\n" +
                "   [UNVERIFIED] — Could not independently verify\n" +
                "   [CONTRADICTED] — Found conflicting information\n" +
                "2. **Contradictions** — Any conflicting data between sources\n" +
                "3. **Confidence Assessment** — Overall reliability of the research\n\n" +
                "RULES: Verify against DIFFERENT sources than the original research")
            .expectedOutput("Verification table with status for each key claim")
            .agent(factChecker)
            .dependsOn(researchTask)
            .outputFormat(OutputFormat.MARKDOWN)
            .maxExecutionTime(180000)
            .build();

        Task reportTask = Task.builder()
            .description(
                "Create the final Web Research Report by synthesizing ALL prior outputs.\n\n" +
                "REQUIRED SECTIONS:\n" +
                "1. **Executive Summary** — 5 key takeaways, each backed by data\n" +
                "2. **Research Findings** — From the web research task\n" +
                "3. **Comparative Analysis** — Tables and matrices from the analysis task\n" +
                "4. **Fact Check Results** — Verification status from the fact check task\n" +
                "5. **Recommendations** — 3-5 actionable recommendations\n" +
                "6. **Sources** — Complete list of URLs consulted\n" +
                "7. **Data Quality Disclaimer** — Confidence assessment\n\n" +
                "CRITICAL: Your ENTIRE response must BE the full report in markdown.\n" +
                "Do NOT use any tools. Do NOT call file_write. Just write the report as your response.\n" +
                "RULES: Every executive summary point must reference specific data from the analysis.")
            .expectedOutput("Complete executive research report saved to file")
            .agent(reportWriter)
            .dependsOn(analysisTask)
            .dependsOn(factCheckTask)
            .outputFormat(OutputFormat.MARKDOWN)
            .outputFile("output/web_research_report.md")
            .maxExecutionTime(240000)
            .build();

        // =====================================================================
        // SWARM — HIERARCHICAL process
        // =====================================================================

        Swarm swarm = Swarm.builder()
            .id("web-research")
            .agent(webResearcher)
            .agent(dataAnalyst)
            .agent(factChecker)
            .agent(reportWriter)
            .managerAgent(researchDirector)
            .task(researchTask)
            .task(analysisTask)
            .task(factCheckTask)
            .task(reportTask)
            .process(ProcessType.HIERARCHICAL)
            .verbose(true)
            .maxRpm(20)
            .language("en")
            .eventPublisher(eventPublisher)
            .config("query", query)
            .build();

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("query", query);

        long startTime = System.currentTimeMillis();
        SwarmOutput result = swarm.kickoff(inputs);
        long duration = (System.currentTimeMillis() - startTime) / 1000;

        // =====================================================================
        // RESULTS
        // =====================================================================

        logger.info("\n" + "=".repeat(80));
        logger.info("WEB RESEARCH COMPLETE");
        logger.info("=".repeat(80));
        logger.info("Query: {}", query);
        logger.info("Duration: {} seconds", duration);
        logger.info("Tasks completed: {}", result.getTaskOutputs().size());
        logger.info("\n{}", result.getTokenUsageSummary("gpt-4o-mini"));
        logger.info("\nFinal Report:\n{}", result.getFinalOutput());
        logger.info("=".repeat(80));
    }
}
