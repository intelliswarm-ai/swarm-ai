package ai.intelliswarm.swarmai.examples.selfimproving;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.OutputFormat;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.ToolHealthChecker;
import ai.intelliswarm.swarmai.tool.common.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Self-Improving Workflow — Fully Dynamic, with Enhanced Skill Architecture
 *
 * Given ANY user query, this workflow:
 * 1. PLANS: An LLM planner analyzes the query, available tools (with routing metadata),
 *    and determines what agents, tasks, and approach are needed
 * 2. HEALTH CHECK: Verifies tools are operational before assignment
 * 3. EXECUTES: The self-improving loop runs with dynamically defined tasks
 * 4. GAP ANALYSIS: Before generating skills, evaluates whether generation is warranted
 * 5. SKILL GENERATION: Creates PROMPT, CODE, HYBRID, or COMPOSITE skills as appropriate
 * 6. IMPROVES: Re-executes with expanded toolkit, converges automatically
 *
 * Enhancements over previous version:
 * - Tool catalog includes routing rules (triggerWhen/avoidWhen), categories, and tags
 *   so the planner makes better tool selections
 * - SkillGapAnalyzer prevents unnecessary skill generation (quality over quantity)
 * - Skills can be pure-prompt (domain expertise), code (data pipelines),
 *   hybrid (reasoning + data), or composite (multi-capability routers)
 * - Tool health checks filter out tools with missing requirements
 *
 * Usage: java -jar app.jar self-improving "any query here"
 */
@Component
public class SelfImprovingWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(SelfImprovingWorkflow.class);

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;

    // All available tools — injected by Spring
    private final List<BaseTool> allTools;

    public SelfImprovingWorkflow(
            ChatClient.Builder chatClientBuilder,
            ApplicationEventPublisher eventPublisher,
            CalculatorTool calculatorTool,
            WebSearchTool webSearchTool,
            FileWriteTool fileWriteTool,
            FileReadTool fileReadTool,
            ShellCommandTool shellCommandTool,
            HttpRequestTool httpRequestTool,
            WebScrapeTool webScrapeTool,
            JSONTransformTool jsonTransformTool) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;

        // Collect all tools that agents can use
        this.allTools = List.of(
            calculatorTool, webSearchTool, shellCommandTool, httpRequestTool,
            webScrapeTool, jsonTransformTool, fileReadTool, fileWriteTool
        );
    }

    public void run(String... args) throws Exception {
        String query = args.length > 0 ? String.join(" ", args) : "Analyze AAPL stock performance";

        logger.info("\n" + "=".repeat(80));
        logger.info("SELF-IMPROVING WORKFLOW (Enhanced Skill Architecture)");
        logger.info("=".repeat(80));
        logger.info("Query: {}", query);
        logger.info("Process: SELF_IMPROVING (plan -> execute -> gap-analyze -> generate skills -> re-execute)");
        logger.info("Skill types: PROMPT | CODE | HYBRID | COMPOSITE");
        logger.info("Available tools: {}", allTools.stream().map(BaseTool::getFunctionName).collect(Collectors.joining(", ")));

        // Run health checks on tools before starting
        List<BaseTool> healthyTools = ToolHealthChecker.filterOperational(allTools);
        if (healthyTools.size() < allTools.size()) {
            logger.warn("Tool health check: {}/{} tools operational", healthyTools.size(), allTools.size());
            Map<String, ToolHealthChecker.HealthCheckResult> results = ToolHealthChecker.checkAll(allTools);
            results.forEach((name, result) -> {
                if (!result.healthy()) {
                    logger.warn("  {} UNHEALTHY: {}", name, result.issues());
                }
            });
        }

        logger.info("Healthy tools: {}", healthyTools.stream().map(BaseTool::getFunctionName).collect(Collectors.joining(", ")));
        logger.info("=".repeat(80));

        runSelfImproving(query, healthyTools);
    }

    private void runSelfImproving(String query, List<BaseTool> tools) {
        ChatClient chatClient = chatClientBuilder.build();

        // =====================================================================
        // PHASE 1: PLANNING — LLM determines what agents, tasks, and tools
        //          are needed. Uses enriched tool catalog with routing metadata.
        // =====================================================================

        String toolCatalog = buildEnrichedToolCatalog(tools);
        WorkflowPlan plan = generatePlan(chatClient, query, toolCatalog);

        logger.info("Plan generated:");
        logger.info("  Analyst role: {}", plan.analystRole);
        logger.info("  Analyst goal: {}", plan.analystGoal);
        logger.info("  Analysis task: {}", truncate(plan.analysisTaskDescription, 100));
        logger.info("  Report task: {}", truncate(plan.reportTaskDescription, 100));
        logger.info("  Quality criteria: {}", truncate(plan.qualityCriteria, 100));
        logger.info("  Recommended tools: {}", plan.recommendedTools);

        // =====================================================================
        // PHASE 2: BUILD — Create agents and tasks from the plan
        // =====================================================================

        List<BaseTool> analystTools = selectTools(plan.recommendedTools, tools);
        logger.info("Selected {} tools for analyst: {}",
            analystTools.size(),
            analystTools.stream().map(BaseTool::getFunctionName).collect(Collectors.joining(", ")));

        Agent analyst = Agent.builder()
            .role(plan.analystRole)
            .goal(plan.analystGoal)
            .backstory(plan.analystBackstory)
            .chatClient(chatClient)
            .tools(analystTools)
            .verbose(true)
            .maxRpm(15)
            .temperature(0.2)
            .modelName("gpt-4o-mini")
            .build();

        Agent writer = Agent.builder()
            .role("Senior Report Writer")
            .goal("Write a comprehensive report based on the findings. " +
                  "Your ENTIRE response must BE the report in markdown. " +
                  "Include all data, tables, and calculations from the analysis.")
            .backstory("You create clear, data-backed reports. Every claim references a specific number. " +
                      "You never summarize — you write the complete report as your response.")
            .chatClient(chatClient)
            .verbose(true)
            .maxRpm(10)
            .temperature(0.3)
            .modelName("gpt-4o-mini")
            .build();

        Agent reviewer = Agent.builder()
            .role("Quality Assurance Director")
            .goal("Review the output and identify quality issues. " +
                  "Respond with VERDICT, QUALITY_ISSUES, and CAPABILITY_GAPS sections as instructed.\n\n" +
                  "IMPORTANT RULES FOR CAPABILITY_GAPS:\n" +
                  "- Only flag a gap if it can be solved with a tool that composes the EXISTING tools " +
                  "(http_request, web_scrape, calculator, json_transform, file_read, file_write, shell_command)\n" +
                  "- Do NOT request capabilities that need external services we don't have " +
                  "(sentiment analysis APIs, social media APIs, real-time data feeds, proprietary databases)\n" +
                  "- If the agents didn't use tools effectively (used fake URLs, didn't try enough sources), " +
                  "that is a QUALITY_ISSUE, not a CAPABILITY_GAP\n" +
                  "- If tool calls returned errors, that is a QUALITY_ISSUE (agent should try different URLs)\n" +
                  "- A capability gap means: 'there is no existing tool that can do X' — NOT 'the agent " +
                  "didn't use the existing tools well enough'\n" +
                  "- Maximum 1 capability gap per review. Prefer QUALITY_ISSUES over CAPABILITY_GAPS.")
            .backstory("You are a strict QA director. You almost never flag capability gaps because " +
                      "the existing tools (http_request, web_scrape, calculator, json_transform) can " +
                      "handle most data gathering tasks when used with REAL URLs. Your main focus is " +
                      "quality: did the agents use tools with real URLs? Did they extract useful data? " +
                      "Did they cite sources? If the report is based on LLM knowledge instead of tool " +
                      "data, that is a quality problem — the agent should be told to actually call the " +
                      "APIs listed in its task description.")
            .chatClient(chatClient)
            .verbose(true)
            .maxRpm(10)
            .temperature(0.1)
            .modelName("gpt-4o-mini")
            .build();

        Task analysisTask = Task.builder()
            .description(plan.analysisTaskDescription)
            .expectedOutput(plan.analysisExpectedOutput)
            .agent(analyst)
            .outputFormat(OutputFormat.MARKDOWN)
            .maxExecutionTime(300000)
            .build();

        Task reportTask = Task.builder()
            .description(plan.reportTaskDescription)
            .expectedOutput("Complete markdown report with findings and recommendations")
            .agent(writer)
            .dependsOn(analysisTask)
            .outputFormat(OutputFormat.MARKDOWN)
            .outputFile("output/self_improving_report.md")
            .maxExecutionTime(180000)
            .build();

        // =====================================================================
        // PHASE 3: EXECUTE — Self-improving loop with gap analysis
        // =====================================================================

        Swarm swarm = Swarm.builder()
            .id("self-improving-analysis")
            .agent(analyst)
            .agent(writer)
            .managerAgent(reviewer)
            .task(analysisTask)
            .task(reportTask)
            .process(ProcessType.SELF_IMPROVING)
            .config("maxIterations", 3)
            .config("qualityCriteria", plan.qualityCriteria)
            .verbose(true)
            .maxRpm(20)
            .language("en")
            .eventPublisher(eventPublisher)
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
        logger.info("SELF-IMPROVING WORKFLOW COMPLETE");
        logger.info("=".repeat(80));
        logger.info("Query: {}", query);
        logger.info("Duration: {} seconds", duration);
        logger.info("Tasks completed: {}", result.getTaskOutputs().size());
        logger.info("Skills generated: {}", result.getMetadata().getOrDefault("skillsGenerated", 0));
        logger.info("Skills reused: {}", result.getMetadata().getOrDefault("skillsReused", 0));
        logger.info("Total iterations: {}", result.getMetadata().getOrDefault("totalIterations", 0));
        logger.info("Stop reason: {}", result.getMetadata().getOrDefault("stopReason", "unknown"));

        @SuppressWarnings("unchecked")
        Map<String, Object> registryStats = (Map<String, Object>) result.getMetadata().getOrDefault("registryStats", Map.of());
        if (!registryStats.isEmpty()) {
            logger.info("Skill Registry: {}", registryStats);
        }

        logger.info("\n{}", result.getTokenUsageSummary("gpt-4o-mini"));
        logger.info("\nFinal Report:\n{}", result.getFinalOutput());
        logger.info("=".repeat(80));
    }

    // =====================================================================
    // ENRICHED TOOL CATALOG — includes routing metadata for better planning
    // =====================================================================

    /**
     * Build an enriched tool catalog that includes routing rules, categories, tags,
     * and a section of known-good API endpoints the LLM can use.
     */
    private String buildEnrichedToolCatalog(List<BaseTool> tools) {
        StringBuilder catalog = new StringBuilder();
        catalog.append("Tools are organized by category. Each tool includes routing hints.\n\n");

        // Group tools by category
        Map<String, List<BaseTool>> byCategory = tools.stream()
            .collect(Collectors.groupingBy(BaseTool::getCategory));

        for (Map.Entry<String, List<BaseTool>> entry : byCategory.entrySet()) {
            catalog.append("## ").append(entry.getKey().toUpperCase()).append(" TOOLS\n");
            for (BaseTool tool : entry.getValue()) {
                catalog.append("  - **").append(tool.getFunctionName()).append("**: ")
                    .append(tool.getDescription()).append("\n");
                if (tool.getTriggerWhen() != null) {
                    catalog.append("    USE WHEN: ").append(tool.getTriggerWhen()).append("\n");
                }
                if (tool.getAvoidWhen() != null) {
                    catalog.append("    AVOID WHEN: ").append(tool.getAvoidWhen()).append("\n");
                }
                if (!tool.getTags().isEmpty()) {
                    catalog.append("    Tags: ").append(String.join(", ", tool.getTags())).append("\n");
                }
            }
            catalog.append("\n");
        }

        // Add known-good API endpoints the LLM should use instead of hallucinating
        catalog.append("## KNOWN-GOOD API ENDPOINTS (use these with http_request)\n");
        catalog.append("CRITICAL: NEVER invent API domains. ONLY use real URLs from this list:\n\n");
        catalog.append("  - Wikipedia summary: GET https://en.wikipedia.org/api/rest_v1/page/summary/{topic}\n");
        catalog.append("  - Wikipedia search: GET https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch={query}&format=json\n");
        catalog.append("  - GitHub repos: GET https://api.github.com/search/repositories?q={query}&sort=stars\n");
        catalog.append("  - GitHub topics: GET https://api.github.com/search/topics?q={query}\n");
        catalog.append("  - Hacker News: GET https://hn.algolia.com/api/v1/search?query={query}&tags=story\n");
        catalog.append("  - DuckDuckGo instant: GET https://api.duckduckgo.com/?q={query}&format=json&no_html=1\n");
        catalog.append("  - JSONPlaceholder (test): GET https://jsonplaceholder.typicode.com/posts\n");
        catalog.append("\n");
        catalog.append("  For web_scrape, use REAL news/tech sites (NOT example.com):\n");
        catalog.append("  - https://en.wikipedia.org/wiki/{topic}\n");
        catalog.append("  - https://news.ycombinator.com\n");
        catalog.append("\n");

        return catalog.toString();
    }

    // =====================================================================
    // PLANNING — LLM generates the workflow definition
    // =====================================================================

    private WorkflowPlan generatePlan(ChatClient chatClient, String query, String toolCatalog) {
        logger.info("Planning workflow for query: {}", truncate(query, 80));

        Agent planner = Agent.builder()
            .role("Workflow Planner")
            .goal("Analyze the user query and design the optimal workflow. " +
                  "Respond ONLY in the exact structured format requested.")
            .backstory("You are an expert at breaking down complex requests into executable tasks. " +
                      "You know which tools are best suited for different types of work. " +
                      "You pay attention to tool routing hints (USE WHEN / AVOID WHEN) to make " +
                      "optimal tool selections. You design clear, actionable task descriptions " +
                      "that tell agents exactly what to do.")
            .chatClient(chatClient)
            .temperature(0.1)
            .verbose(false)
            .modelName("gpt-4o-mini")
            .build();

        String prompt = String.format(
            "Design a workflow to handle this user request:\n\n" +
            "USER QUERY: %s\n\n" +
            "AVAILABLE TOOLS (with routing hints — use these to select the right tools):\n%s\n" +
            "TOOL SELECTION RULES:\n" +
            "- Read each tool's USE WHEN and AVOID WHEN hints carefully\n" +
            "- Only recommend tools that match the query's domain\n" +
            "- Prefer fewer, well-chosen tools over many tools\n" +
            "- The agent can always reason without tools — don't force tool usage for pure analysis\n\n" +
            "URL RULES (CRITICAL):\n" +
            "- NEVER invent API domain names (e.g., api.cloudmarketshare.com does NOT exist)\n" +
            "- ONLY use URLs from the KNOWN-GOOD API ENDPOINTS list in the tool catalog\n" +
            "- In the ANALYSIS_TASK description, include SPECIFIC real URLs the agent should fetch\n" +
            "- If a topic doesn't have a known API, use Wikipedia + GitHub + HN Algolia endpoints\n\n" +
            "Respond in EXACTLY this format (no extra text):\n\n" +
            "ANALYST_ROLE: [role name for the primary analyst, e.g., 'Network Security Analyst' or 'Financial Data Analyst']\n" +
            "ANALYST_GOAL: [1-2 sentence goal describing what the analyst should accomplish for this specific query]\n" +
            "ANALYST_BACKSTORY: [1-2 sentence backstory establishing the analyst's expertise relevant to this query]\n" +
            "RECOMMENDED_TOOLS: [comma-separated list of tool names from the catalog above. " +
            "ONLY include tools whose USE WHEN hints match this query's needs.]\n" +
            "ANALYSIS_TASK: [Detailed task description telling the analyst exactly what to do. " +
            "Be specific about what data to gather, what commands to run, what to analyze. " +
            "Reference specific tools by name. Do NOT include generic filler — every sentence should be actionable.]\n" +
            "ANALYSIS_EXPECTED_OUTPUT: [1 sentence describing what the analysis output should contain]\n" +
            "REPORT_TASK: [Task description for the report writer. Specify what sections the report needs, " +
            "what format to use, and what the report should cover based on the query.]\n" +
            "QUALITY_CRITERIA: [3-5 numbered criteria the reviewer should use to evaluate the output, " +
            "specific to this query — not generic criteria]\n",
            query, toolCatalog
        );

        Task planTask = Task.builder()
            .description(prompt)
            .expectedOutput("Structured workflow plan")
            .agent(planner)
            .maxExecutionTime(30000)
            .build();

        try {
            TaskOutput output = planTask.execute(Collections.emptyList());
            return parsePlan(output.getRawOutput(), query);
        } catch (Exception e) {
            logger.warn("Planning failed, using fallback: {}", e.getMessage());
            return createFallbackPlan(query);
        }
    }

    private WorkflowPlan parsePlan(String response, String query) {
        WorkflowPlan plan = new WorkflowPlan();

        plan.analystRole = extractField(response, "ANALYST_ROLE:", "Senior Analyst");
        plan.analystGoal = extractField(response, "ANALYST_GOAL:",
            "Analyze: '" + query + "'. Use available tools to gather real data.");
        plan.analystBackstory = extractField(response, "ANALYST_BACKSTORY:",
            "You are an experienced analyst. You use tools for data and never fabricate results.");
        plan.recommendedTools = extractField(response, "RECOMMENDED_TOOLS:",
            "web_search,calculator,shell_command");
        plan.analysisTaskDescription = extractField(response, "ANALYSIS_TASK:",
            "Analyze: \"" + query + "\". Use your tools to gather data. Report findings with evidence.");
        plan.analysisExpectedOutput = extractField(response, "ANALYSIS_EXPECTED_OUTPUT:",
            "Analysis with real data, metrics, and findings");
        plan.reportTaskDescription = extractField(response, "REPORT_TASK:",
            "Write a comprehensive report based on the analyst's findings. " +
            "Your ENTIRE response must BE the full report in markdown. Include all data and recommendations.");
        plan.qualityCriteria = extractField(response, "QUALITY_CRITERIA:",
            "1. Does the report contain specific data from tool output?\n" +
            "2. Are findings backed by evidence?\n" +
            "3. Are recommendations actionable?");

        return plan;
    }

    private WorkflowPlan createFallbackPlan(String query) {
        // Convert query to URL-safe topic for API calls
        String topic = query.replaceAll("[^a-zA-Z0-9 ]", "").trim().replace(" ", "_");
        String urlTopic = query.replaceAll("[^a-zA-Z0-9 ]", "").trim().replace(" ", "+");

        WorkflowPlan plan = new WorkflowPlan();
        plan.analystRole = "Senior Research Analyst";
        plan.analystGoal = "Analyze: '" + query + "'. Gather real data using ONLY the known-good API endpoints.";
        plan.analystBackstory = "You are a resourceful analyst who uses real APIs. You NEVER invent domain names. " +
            "When one tool fails, you try a different REAL URL.";
        plan.recommendedTools = "http_request,web_scrape,calculator,json_transform";
        plan.analysisTaskDescription = "Analyze: \"" + query + "\"\n\n" +
            "STEP-BY-STEP DATA GATHERING (use these EXACT URLs — call ALL of them):\n\n" +
            "STEP 1 - Wikipedia context:\n" +
            "  http_request GET https://en.wikipedia.org/api/rest_v1/page/summary/" + topic + "\n\n" +
            "STEP 2 - Wikipedia search for related articles:\n" +
            "  http_request GET https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=" + urlTopic + "&format=json\n\n" +
            "STEP 3 - GitHub repositories (look at stars, descriptions, recent activity):\n" +
            "  http_request GET https://api.github.com/search/repositories?q=" + urlTopic + "&sort=stars&per_page=10\n\n" +
            "STEP 4 - Hacker News discussions (look at story titles, points, comments):\n" +
            "  http_request GET https://hn.algolia.com/api/v1/search?query=" + urlTopic + "&tags=story&hitsPerPage=10\n\n" +
            "STEP 5 - DuckDuckGo instant answers:\n" +
            "  http_request GET https://api.duckduckgo.com/?q=" + urlTopic + "&format=json&no_html=1\n\n" +
            "STEP 6 - Scrape Wikipedia article for detailed content:\n" +
            "  web_scrape https://en.wikipedia.org/wiki/" + topic + "\n\n" +
            "AFTER GATHERING DATA:\n" +
            "- Use json_transform to extract the most relevant fields from each JSON response\n" +
            "- Build a comparison table from the data you gathered\n" +
            "- Clearly cite which source each fact came from: [Wikipedia], [GitHub], [HN], [DDG]\n" +
            "- If a URL returned an error, report it and move on — do NOT retry or invent new URLs\n" +
            "- Do NOT fabricate data. If you couldn't find specific info, say so.";
        plan.analysisExpectedOutput = "Analysis with data from multiple real API sources";
        plan.reportTaskDescription = "Write a comprehensive markdown report based on the analyst's findings.\n" +
            "Include all data retrieved from real APIs. For any gaps, state which URLs were tried.";
        plan.qualityCriteria = "1. Were real API URLs used (not invented domains)?\n" +
            "2. Does the report contain actual data from API responses?\n" +
            "3. Are sources clearly cited?";
        return plan;
    }

    /**
     * Select tools from the catalog based on the planner's recommendation.
     * Uses category-aware selection: if a recommended tool isn't found by name,
     * try matching by category or tags.
     */
    private List<BaseTool> selectTools(String recommendedToolNames, List<BaseTool> available) {
        Set<String> recommended = Arrays.stream(recommendedToolNames.split("[,;\\s]+"))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toSet());

        List<BaseTool> selected = available.stream()
            .filter(t -> recommended.contains(t.getFunctionName()))
            .collect(Collectors.toList());

        // Always ensure calculator is available
        boolean hasCalculator = selected.stream()
            .anyMatch(t -> t.getFunctionName().equals("calculator"));
        if (!hasCalculator) {
            available.stream()
                .filter(t -> t.getFunctionName().equals("calculator"))
                .findFirst()
                .ifPresent(selected::add);
        }

        // If planner recommended nothing useful, give all tools
        if (selected.size() < 2) {
            return new ArrayList<>(available);
        }

        return selected;
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private String extractField(String text, String fieldName, String fallback) {
        int idx = text.indexOf(fieldName);
        if (idx == -1) return fallback;

        int start = idx + fieldName.length();

        String[] markers = {"ANALYST_ROLE:", "ANALYST_GOAL:", "ANALYST_BACKSTORY:",
            "RECOMMENDED_TOOLS:", "ANALYSIS_TASK:", "ANALYSIS_EXPECTED_OUTPUT:",
            "REPORT_TASK:", "QUALITY_CRITERIA:"};

        int end = text.length();
        for (String marker : markers) {
            if (marker.equals(fieldName)) continue;
            int markerIdx = text.indexOf(marker, start);
            if (markerIdx > 0 && markerIdx < end) {
                end = markerIdx;
            }
        }

        String value = text.substring(start, end).trim();
        return value.isEmpty() ? fallback : value;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    private static class WorkflowPlan {
        String analystRole;
        String analystGoal;
        String analystBackstory;
        String recommendedTools;
        String analysisTaskDescription;
        String analysisExpectedOutput;
        String reportTaskDescription;
        String qualityCriteria;
    }
}
