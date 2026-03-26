package ai.intelliswarm.swarmai.examples.selfimproving;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.OutputFormat;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.common.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Self-Improving Workflow — Fully Dynamic
 *
 * Given ANY user query, this workflow:
 * 1. PLANS: An LLM planner analyzes the query, available tools, and determines
 *    what agents, tasks, and approach are needed
 * 2. EXECUTES: The self-improving loop runs with dynamically defined tasks
 * 3. IMPROVES: The reviewer identifies capability gaps, new tools are generated
 *
 * No hardcoded task descriptions, tool suggestions, or domain-specific prompts.
 *
 * Usage: docker compose -f docker-compose.run.yml run --rm self-improving "any query here"
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
        logger.info("SELF-IMPROVING WORKFLOW");
        logger.info("=".repeat(80));
        logger.info("Query: {}", query);
        logger.info("Process: SELF_IMPROVING (plan → execute → review → generate skills → re-execute)");
        logger.info("Max iterations: 3");
        logger.info("Available tools: {}", allTools.stream().map(BaseTool::getFunctionName).collect(Collectors.joining(", ")));
        logger.info("=".repeat(80));

        runSelfImproving(query);
    }

    private void runSelfImproving(String query) {
        ChatClient chatClient = chatClientBuilder.build();

        // =====================================================================
        // PHASE 1: PLANNING — LLM determines what agents, tasks, and tools
        //          are needed for this specific query
        // =====================================================================

        String toolCatalog = buildToolCatalog();
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

        // Select tools recommended by the planner (plus always include calculator)
        List<BaseTool> analystTools = selectTools(plan.recommendedTools);
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
            .goal("Review the output and identify both quality issues and missing tool capabilities. " +
                  "Respond with VERDICT, QUALITY_ISSUES, and CAPABILITY_GAPS sections as instructed.")
            .backstory("You are a QA director who evaluates reports. You distinguish between " +
                      "quality problems (bad content) and capability gaps (the agents lack a tool). " +
                      "When you identify a capability gap, describe the tool that would help: " +
                      "what it takes as input, what it returns, and why it's needed. " +
                      "Be specific — say 'NO_TOOL: need a function that does X given Y'.")
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
        // PHASE 3: EXECUTE — Self-improving loop
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
        logger.info("Total iterations: {}", result.getMetadata().getOrDefault("totalIterations", 0));

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
    // PLANNING — LLM generates the workflow definition
    // =====================================================================

    /**
     * Build a catalog of all available tools with names and descriptions.
     */
    private String buildToolCatalog() {
        StringBuilder catalog = new StringBuilder();
        for (BaseTool tool : allTools) {
            catalog.append("  - ").append(tool.getFunctionName()).append(": ")
                .append(tool.getDescription()).append("\n");
        }
        return catalog.toString();
    }

    /**
     * Use an LLM to generate a workflow plan based on the query and available tools.
     */
    private WorkflowPlan generatePlan(ChatClient chatClient, String query, String toolCatalog) {
        logger.info("Planning workflow for query: {}", truncate(query, 80));

        Agent planner = Agent.builder()
            .role("Workflow Planner")
            .goal("Analyze the user query and design the optimal workflow. " +
                  "Respond ONLY in the exact structured format requested.")
            .backstory("You are an expert at breaking down complex requests into executable tasks. " +
                      "You know which tools are best suited for different types of work. " +
                      "You design clear, actionable task descriptions that tell agents exactly what to do.")
            .chatClient(chatClient)
            .temperature(0.1)
            .verbose(false)
            .modelName("gpt-4o-mini")
            .build();

        String prompt = String.format(
            "Design a workflow to handle this user request:\n\n" +
            "USER QUERY: %s\n\n" +
            "AVAILABLE TOOLS:\n%s\n" +
            "Respond in EXACTLY this format (no extra text):\n\n" +
            "ANALYST_ROLE: [role name for the primary analyst, e.g., 'Network Security Analyst' or 'Financial Data Analyst']\n" +
            "ANALYST_GOAL: [1-2 sentence goal describing what the analyst should accomplish for this specific query]\n" +
            "ANALYST_BACKSTORY: [1-2 sentence backstory establishing the analyst's expertise relevant to this query]\n" +
            "RECOMMENDED_TOOLS: [comma-separated list of tool names from the catalog above that the analyst should use]\n" +
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

    /**
     * Parse the planner's structured output into a WorkflowPlan.
     */
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

    /**
     * Fallback plan if LLM planning fails.
     */
    private WorkflowPlan createFallbackPlan(String query) {
        WorkflowPlan plan = new WorkflowPlan();
        plan.analystRole = "Senior Analyst";
        plan.analystGoal = "Analyze: '" + query + "'. Use all available tools to gather data.";
        plan.analystBackstory = "You are an experienced analyst. Use tools for data, never fabricate results.";
        plan.recommendedTools = "web_search,calculator,shell_command,http_request";
        plan.analysisTaskDescription = "Analyze: \"" + query +
            "\"\n\nUse your available tools to gather real data. Report your findings with evidence from tool output.\n" +
            "RULES:\n- Use ONLY data from tools. Do NOT fabricate.\n- Clearly mark any data gaps.";
        plan.analysisExpectedOutput = "Analysis with real tool output and findings";
        plan.reportTaskDescription = "Write a comprehensive report based on the analyst's findings.\n" +
            "Your ENTIRE response must BE the full report in markdown.\n" +
            "Include all data, tables, calculations, and actionable recommendations.";
        plan.qualityCriteria = "1. Does the report contain real data from tools?\n" +
            "2. Are findings backed by evidence?\n" +
            "3. Are recommendations actionable?";
        return plan;
    }

    /**
     * Select tools from the catalog based on the planner's recommendation.
     */
    private List<BaseTool> selectTools(String recommendedToolNames) {
        Set<String> recommended = Arrays.stream(recommendedToolNames.split("[,;\\s]+"))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toSet());

        List<BaseTool> selected = allTools.stream()
            .filter(t -> recommended.contains(t.getFunctionName()))
            .collect(Collectors.toList());

        // Always ensure calculator is available
        boolean hasCalculator = selected.stream()
            .anyMatch(t -> t.getFunctionName().equals("calculator"));
        if (!hasCalculator) {
            allTools.stream()
                .filter(t -> t.getFunctionName().equals("calculator"))
                .findFirst()
                .ifPresent(selected::add);
        }

        // If planner recommended nothing useful, give all tools
        if (selected.size() < 2) {
            return new ArrayList<>(allTools);
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

        // Find the end: next field marker or end of text
        // Look for the next known field marker
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

    /**
     * Internal plan structure populated by the LLM planner.
     */
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
