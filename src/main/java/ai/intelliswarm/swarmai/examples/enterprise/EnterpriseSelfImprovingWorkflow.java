package ai.intelliswarm.swarmai.examples.enterprise;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.budget.*;
import ai.intelliswarm.swarmai.governance.*;
import ai.intelliswarm.swarmai.memory.InMemoryMemory;
import ai.intelliswarm.swarmai.memory.Memory;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.OutputFormat;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import ai.intelliswarm.swarmai.tenant.*;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.ToolHealthChecker;
import ai.intelliswarm.swarmai.tool.common.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Enterprise Self-Improving Workflow
 *
 * The same dynamic, self-improving workflow as the standard self-improving example,
 * but wrapped with enterprise governance:
 *
 *   - MULTI-TENANCY:   Isolated per team with resource quotas
 *   - BUDGET TRACKING:  Real-time token/cost monitoring with configurable limits
 *   - GOVERNANCE GATES: Human-in-the-loop approval after the analysis phase
 *   - MEMORY:           Cross-run learning via persistent memory
 *   - SELF-IMPROVING:   LLM plans agents, generates skills at runtime
 *
 * Usage:
 *   docker compose -f docker-compose.run.yml run --rm --service-ports enterprise-governed \
 *     "Compare the top 5 AI coding assistants for enterprise Java development"
 *
 *   docker compose -f docker-compose.run.yml run --rm --service-ports enterprise-governed \
 *     "Analyze the competitive landscape of cloud providers AWS vs Azure vs GCP"
 */
@Component
public class EnterpriseSelfImprovingWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(EnterpriseSelfImprovingWorkflow.class);

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;
    private final List<BaseTool> allTools;

    public EnterpriseSelfImprovingWorkflow(
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
        this.allTools = List.of(
            calculatorTool, webSearchTool, shellCommandTool, httpRequestTool,
            webScrapeTool, jsonTransformTool, fileReadTool, fileWriteTool
        );
    }

    public void run(String... args) throws Exception {
        // Parse args: last arg can be a number for max iterations (0 = auto-stop on convergence)
        int maxIterations = 0; // 0 means auto — framework decides via convergence detection
        String tenantId = "enterprise-team";
        List<String> queryParts = new ArrayList<>();

        for (String arg : args) {
            try {
                maxIterations = Integer.parseInt(arg);
            } catch (NumberFormatException e) {
                queryParts.add(arg);
            }
        }

        String query = queryParts.isEmpty()
                ? "Compare the top 5 AI coding assistants for enterprise Java development"
                : String.join(" ", queryParts);

        logger.info("\n" + "=".repeat(80));
        logger.info("ENTERPRISE SELF-IMPROVING WORKFLOW");
        logger.info("=".repeat(80));
        logger.info("Query:      {}", query);
        logger.info("Tenant:     {}", tenantId);
        logger.info("Process:    SELF_IMPROVING + Enterprise Governance");
        logger.info("Iterations: {}", maxIterations == 0 ? "auto (convergence-based)" : maxIterations + " max");
        logger.info("Tools:      {}", allTools.stream().map(BaseTool::getFunctionName).collect(Collectors.joining(", ")));
        logger.info("=".repeat(80));

        // =====================================================================
        // ENTERPRISE LAYER 1: Multi-Tenancy
        // =====================================================================

        TenantResourceQuota quota = TenantResourceQuota.builder(tenantId)
                .maxConcurrentWorkflows(5)
                .maxSkills(50)
                .maxTokenBudget(2_000_000)
                .build();

        TenantQuotaEnforcer quotaEnforcer = new InMemoryTenantQuotaEnforcer(
                Map.of(tenantId, quota),
                TenantResourceQuota.builder("default").build()
        );

        Memory memory = new InMemoryMemory();

        logger.info("\n--- Enterprise: Tenant ---");
        logger.info("  Tenant:          {}", tenantId);
        logger.info("  Max workflows:   {}", quota.maxConcurrentWorkflows());
        logger.info("  Max skills:      {}", quota.maxSkills());
        logger.info("  Max tokens:      {}", quota.maxTokenBudget());

        // =====================================================================
        // ENTERPRISE LAYER 2: Budget Tracking
        // =====================================================================

        BudgetPolicy budgetPolicy = BudgetPolicy.builder()
                .maxTotalTokens(1_000_000)
                .maxCostUsd(5.00)
                .modelName("gpt-4o-mini")
                .onExceeded(BudgetPolicy.BudgetAction.WARN)
                .warningThresholdPercent(80.0)
                .build();

        BudgetTracker budgetTracker = new InMemoryBudgetTracker(budgetPolicy);

        logger.info("\n--- Enterprise: Budget ---");
        logger.info("  Max tokens:  {}", budgetPolicy.maxTotalTokens());
        logger.info("  Max cost:    ${}", budgetPolicy.maxCostUsd());
        logger.info("  On exceeded: {}", budgetPolicy.onExceeded());

        // =====================================================================
        // ENTERPRISE LAYER 3: Governance Gates
        // =====================================================================

        ApprovalGateHandler gateHandler = new InMemoryApprovalGateHandler(eventPublisher);
        WorkflowGovernanceEngine governance = new WorkflowGovernanceEngine(gateHandler, eventPublisher);

        // Auto-approve after 3 seconds for demo — in production, a human approves via REST/UI
        ApprovalGate analysisGate = ApprovalGate.builder()
                .name("Analysis Quality Gate")
                .description("Review research findings before report generation")
                .trigger(GateTrigger.AFTER_TASK)
                .timeout(Duration.ofSeconds(3))
                .policy(new ApprovalPolicy(1, List.of(), true))
                .build();

        logger.info("\n--- Enterprise: Governance ---");
        logger.info("  Gate:         {}", analysisGate.name());
        logger.info("  Trigger:      {}", analysisGate.trigger());
        logger.info("  Auto-approve: {} (after {}s timeout)", analysisGate.policy().autoApproveOnTimeout(),
                analysisGate.timeout().toSeconds());

        // =====================================================================
        // PHASE 1: PLANNING — LLM determines agents, tasks, tools
        // =====================================================================

        ChatClient chatClient = chatClientBuilder.build();
        String toolCatalog = buildToolCatalog();
        WorkflowPlan plan = generatePlan(chatClient, query, toolCatalog);

        logger.info("\n--- LLM-Generated Plan ---");
        logger.info("  Analyst:  {}", plan.analystRole);
        logger.info("  Goal:     {}", truncate(plan.analystGoal, 100));
        logger.info("  Tools:    {}", plan.recommendedTools);

        // =====================================================================
        // PHASE 2: BUILD — Create agents and tasks from the plan
        // =====================================================================

        List<BaseTool> analystTools = selectTools(plan.recommendedTools);

        Agent analyst = Agent.builder()
                .role(plan.analystRole)
                .goal(plan.analystGoal)
                .backstory(plan.analystBackstory)
                .chatClient(chatClient)
                .tools(analystTools)
                .memory(memory)
                .verbose(true)
                .maxRpm(15)
                .temperature(0.2)
                .modelName("gpt-4o-mini")
                .build();

        Agent writer = Agent.builder()
                .role("Senior Report Writer")
                .goal("Write a comprehensive report based on the findings. " +
                      "Your ENTIRE response must BE the report in markdown.")
                .backstory("You create clear, data-backed reports. Every claim references specific data.")
                .chatClient(chatClient)
                .memory(memory)
                .verbose(true)
                .maxRpm(10)
                .temperature(0.3)
                .modelName("gpt-4o-mini")
                .build();

        Agent reviewer = Agent.builder()
                .role("Quality Assurance Director")
                .goal("Review the output and identify quality issues and capability gaps. " +
                      "Respond with VERDICT, QUALITY_ISSUES, and CAPABILITY_GAPS sections. " +
                      "IMPORTANT: If the analysts only used web_search and got no results, that is a QUALITY_ISSUE " +
                      "(they should have tried http_request or web_scrape with specific URLs), NOT a CAPABILITY_GAP. " +
                      "Only report CAPABILITY_GAPS for genuinely missing tools — not for tools that exist but weren't tried.")
                .backstory("You evaluate reports rigorously. You know that web_search, http_request, web_scrape, " +
                           "and shell_command are all available. If the output says 'DATA NOT AVAILABLE' but the " +
                           "analysts didn't try fetching specific URLs with http_request or scraping real pages, " +
                           "that's a quality failure — they didn't use the tools they have.")
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
                .outputFile("output/enterprise_self_improving_report.md")
                .maxExecutionTime(180000)
                .build();

        // =====================================================================
        // PHASE 3: EXECUTE — Self-improving loop WITH enterprise governance
        // =====================================================================

        logger.info("\n" + "-".repeat(60));
        logger.info("  EXECUTING ENTERPRISE GOVERNED SELF-IMPROVING WORKFLOW");
        logger.info("-".repeat(60));

        Swarm swarm = Swarm.builder()
                .id("enterprise-self-improving-" + System.currentTimeMillis())
                .agent(analyst)
                .agent(writer)
                .managerAgent(reviewer)
                .task(analysisTask)
                .task(reportTask)
                .process(ProcessType.SELF_IMPROVING)
                .config("maxIterations", maxIterations)
                .config("qualityCriteria", plan.qualityCriteria)
                .verbose(true)
                .maxRpm(20)
                .language("en")
                .eventPublisher(eventPublisher)
                .memory(memory)
                // Enterprise features
                .tenantId(tenantId)
                .tenantQuotaEnforcer(quotaEnforcer)
                .budgetTracker(budgetTracker)
                .budgetPolicy(budgetPolicy)
                .governance(governance)
                .approvalGate(analysisGate)
                .build();

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("query", query);

        long startTime = System.currentTimeMillis();

        SwarmOutput result;
        try {
            result = swarm.kickoff(inputs);
        } catch (BudgetExceededException e) {
            logger.error("BUDGET EXCEEDED: {}", e.getMessage());
            throw e;
        } catch (TenantQuotaExceededException e) {
            logger.error("TENANT QUOTA EXCEEDED: {}", e.getMessage());
            throw e;
        }

        long duration = (System.currentTimeMillis() - startTime) / 1000;

        // =====================================================================
        // RESULTS — Full enterprise telemetry
        // =====================================================================

        logger.info("\n" + "=".repeat(80));
        logger.info("ENTERPRISE SELF-IMPROVING WORKFLOW — RESULTS");
        logger.info("=".repeat(80));

        // Core metrics
        logger.info("\n--- Workflow ---");
        logger.info("  Query:            {}", query);
        logger.info("  Duration:         {} seconds", duration);
        logger.info("  Tasks completed:  {}", result.getTaskOutputs().size());
        logger.info("  Iterations:       {}", result.getMetadata().getOrDefault("totalIterations", 0));
        logger.info("  Skills generated: {}", result.getMetadata().getOrDefault("skillsGenerated", 0));
        logger.info("  Skills reused:    {}", result.getMetadata().getOrDefault("skillsReused", 0));
        logger.info("  Skills promoted:  {}", result.getMetadata().getOrDefault("skillsPromoted", 0));
        logger.info("  Stop reason:      {}", result.getMetadata().getOrDefault("stopReason", "unknown"));

        // Tenant
        logger.info("\n--- Tenant ---");
        logger.info("  Tenant ID:        {}", tenantId);
        logger.info("  Active workflows: {} (released)", quotaEnforcer.getActiveWorkflowCount(tenantId));
        logger.info("  Memory entries:   {}", memory.size());

        // Budget
        logger.info("\n--- Budget ---");
        BudgetSnapshot snapshot = budgetTracker.getSnapshot(swarm.getId());
        if (snapshot != null) {
            logger.info("  Tokens used:      {} / {} ({}%)",
                    snapshot.totalTokensUsed(), budgetPolicy.maxTotalTokens(),
                    String.format("%.1f", snapshot.tokenUtilizationPercent()));
            logger.info("  Prompt tokens:    {}", snapshot.promptTokensUsed());
            logger.info("  Completion tokens:{}", snapshot.completionTokensUsed());
            logger.info("  Estimated cost:   ${} / ${}",
                    String.format("%.4f", snapshot.estimatedCostUsd()), budgetPolicy.maxCostUsd());
            logger.info("  Budget exceeded:  {}", snapshot.isExceeded());
        }

        // Governance
        logger.info("\n--- Governance ---");
        logger.info("  Pending approvals: {}", gateHandler.getPendingRequests().size());
        logger.info("  Gate:              {} (passed)", analysisGate.name());

        // Skill registry
        @SuppressWarnings("unchecked")
        Map<String, Object> registryStats = (Map<String, Object>) result.getMetadata()
                .getOrDefault("registryStats", Map.of());
        if (!registryStats.isEmpty()) {
            logger.info("\n--- Skill Registry ---");
            logger.info("  {}", registryStats);
        }

        // Token usage
        logger.info("\n{}", result.getTokenUsageSummary("gpt-4o-mini"));

        // Final output
        logger.info("\n--- Final Report ---\n{}", result.getFinalOutput());
        logger.info("=".repeat(80));
    }

    // =====================================================================
    // Planning — same as SelfImprovingWorkflow
    // =====================================================================

    /**
     * Build an enriched tool catalog with routing rules, categories, and tags.
     */
    private String buildToolCatalog() {
        StringBuilder catalog = new StringBuilder();
        catalog.append("Tools organized by category with routing hints:\n\n");

        Map<String, List<BaseTool>> byCategory = allTools.stream()
            .collect(Collectors.groupingBy(BaseTool::getCategory));

        for (Map.Entry<String, List<BaseTool>> entry : byCategory.entrySet()) {
            catalog.append("## ").append(entry.getKey().toUpperCase()).append("\n");
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
        return catalog.toString();
    }

    private WorkflowPlan generatePlan(ChatClient chatClient, String query, String toolCatalog) {
        logger.info("Planning workflow for: {}", truncate(query, 80));

        Agent planner = Agent.builder()
                .role("Workflow Planner")
                .goal("Analyze the user query and design the optimal workflow. " +
                      "Respond ONLY in the exact structured format requested.")
                .backstory("You break down complex requests into executable tasks and select the right tools.")
                .chatClient(chatClient)
                .temperature(0.1)
                .verbose(false)
                .modelName("gpt-4o-mini")
                .build();

        String prompt = String.format(
                "Design a workflow for:\n\nUSER QUERY: %s\n\nAVAILABLE TOOLS:\n%s\n" +
                "IMPORTANT TOOL STRATEGY RULES:\n" +
                "- web_search may return empty results if API keys are not configured\n" +
                "- When web_search fails, the analyst MUST fall back to:\n" +
                "  1. http_request to fetch data from SPECIFIC known URLs (e.g., company websites, public APIs)\n" +
                "  2. web_scrape to extract data from SPECIFIC real web pages\n" +
                "  3. shell_command to run curl commands to known public APIs\n" +
                "- NEVER just call web_search repeatedly with the same query\n" +
                "- Include SPECIFIC URLs and API endpoints in the task description\n" +
                "- The analyst should try at least 3 different tool strategies per topic\n\n" +
                "Respond in EXACTLY this format:\n\n" +
                "ANALYST_ROLE: [role]\n" +
                "ANALYST_GOAL: [goal]\n" +
                "ANALYST_BACKSTORY: [backstory]\n" +
                "RECOMMENDED_TOOLS: [comma-separated tool names — MUST include http_request and web_scrape]\n" +
                "ANALYSIS_TASK: [detailed task description with SPECIFIC URLs to fetch data from]\n" +
                "ANALYSIS_EXPECTED_OUTPUT: [expected output]\n" +
                "REPORT_TASK: [report task description]\n" +
                "QUALITY_CRITERIA: [3-5 numbered criteria]\n",
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
                "Analyze: '" + query + "'. Use tools for real data.");
        plan.analystBackstory = extractField(response, "ANALYST_BACKSTORY:",
                "Experienced analyst who uses tools and never fabricates data.");
        plan.recommendedTools = extractField(response, "RECOMMENDED_TOOLS:",
                "web_search,calculator,shell_command");
        plan.analysisTaskDescription = extractField(response, "ANALYSIS_TASK:",
                "Analyze: \"" + query + "\". Use tools to gather data. Report findings with evidence.");
        plan.analysisExpectedOutput = extractField(response, "ANALYSIS_EXPECTED_OUTPUT:",
                "Analysis with real data and findings");
        plan.reportTaskDescription = extractField(response, "REPORT_TASK:",
                "Write a comprehensive markdown report with all findings and recommendations.");
        plan.qualityCriteria = extractField(response, "QUALITY_CRITERIA:",
                "1. Contains real data from tools?\n2. Findings backed by evidence?\n3. Recommendations actionable?");
        return plan;
    }

    private WorkflowPlan createFallbackPlan(String query) {
        WorkflowPlan plan = new WorkflowPlan();
        plan.analystRole = "Senior Research Analyst";
        plan.analystGoal = "Analyze: '" + query + "'. Use MULTIPLE tool strategies to gather real data.";
        plan.analystBackstory = "Resourceful analyst who never gives up. When one tool fails, you try another approach. " +
                "You know specific URLs for public data sources and fetch them directly.";
        plan.recommendedTools = "web_search,http_request,web_scrape,calculator,shell_command,json_transform";
        plan.analysisTaskDescription = "Analyze: \"" + query + "\"\n\n" +
                "TOOL STRATEGY (try in order):\n" +
                "1. Try web_search first for general information\n" +
                "2. If web_search returns empty, use http_request to fetch from SPECIFIC URLs:\n" +
                "   - Wikipedia API: http_request GET https://en.wikipedia.org/api/rest_v1/page/summary/{topic}\n" +
                "   - GitHub trending: http_request GET https://api.github.com/search/repositories?q={topic}\n" +
                "   - Hacker News: http_request GET https://hn.algolia.com/api/v1/search?query={topic}\n" +
                "3. If http_request fails, use web_scrape on actual web pages\n" +
                "4. Use shell_command with 'curl' for APIs that need custom headers\n" +
                "5. Use calculator for any numerical analysis\n\n" +
                "CRITICAL RULES:\n" +
                "- Do NOT call the same tool with the same query twice\n" +
                "- Do NOT just report 'DATA NOT AVAILABLE' — try a different tool\n" +
                "- Use REAL URLs, not placeholders\n" +
                "- If all tools fail for a specific metric, explain WHY and suggest where the data could be found";
        plan.analysisExpectedOutput = "Analysis with data from multiple tool strategies and specific findings";
        plan.reportTaskDescription = "Write a comprehensive markdown report based on the analyst's findings.\n" +
                "Include all data retrieved from tools. For any gaps, cite the specific URLs tried and " +
                "explain what alternative sources could fill the gap.";
        plan.qualityCriteria = "1. Were MULTIPLE tool strategies tried (not just web_search)?\n" +
                "2. Were specific URLs fetched via http_request or web_scrape?\n" +
                "3. Does the report contain actual data from tool output?\n" +
                "4. Are recommendations actionable and specific?";
        return plan;
    }

    private List<BaseTool> selectTools(String recommendedToolNames) {
        Set<String> recommended = Arrays.stream(recommendedToolNames.split("[,;\\s]+"))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());

        List<BaseTool> selected = allTools.stream()
                .filter(t -> recommended.contains(t.getFunctionName()))
                .collect(Collectors.toList());

        boolean hasCalculator = selected.stream().anyMatch(t -> t.getFunctionName().equals("calculator"));
        if (!hasCalculator) {
            allTools.stream().filter(t -> t.getFunctionName().equals("calculator"))
                    .findFirst().ifPresent(selected::add);
        }

        if (selected.size() < 2) return new ArrayList<>(allTools);
        return selected;
    }

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
            if (markerIdx > 0 && markerIdx < end) end = markerIdx;
        }
        String value = text.substring(start, end).trim();
        return value.isEmpty() ? fallback : value;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    private static class WorkflowPlan {
        String analystRole, analystGoal, analystBackstory, recommendedTools;
        String analysisTaskDescription, analysisExpectedOutput, reportTaskDescription, qualityCriteria;
    }
}
