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
        String query = args.length > 0 ? String.join(" ", args) : "Compare the top 5 AI coding assistants for enterprise Java development";
        String tenantId = "enterprise-team";
        int maxIterations = 3;

        logger.info("\n" + "=".repeat(80));
        logger.info("ENTERPRISE SELF-IMPROVING WORKFLOW");
        logger.info("=".repeat(80));
        logger.info("Query:      {}", query);
        logger.info("Tenant:     {}", tenantId);
        logger.info("Process:    SELF_IMPROVING + Enterprise Governance");
        logger.info("Iterations: {} max", maxIterations);
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
                      "Respond with VERDICT, QUALITY_ISSUES, and CAPABILITY_GAPS sections.")
                .backstory("You evaluate reports and distinguish quality problems from missing tools. " +
                           "When a tool is missing, describe it precisely: input, output, and why it's needed.")
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

    private String buildToolCatalog() {
        StringBuilder catalog = new StringBuilder();
        for (BaseTool tool : allTools) {
            catalog.append("  - ").append(tool.getFunctionName()).append(": ")
                    .append(tool.getDescription()).append("\n");
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
                "Respond in EXACTLY this format:\n\n" +
                "ANALYST_ROLE: [role]\n" +
                "ANALYST_GOAL: [goal]\n" +
                "ANALYST_BACKSTORY: [backstory]\n" +
                "RECOMMENDED_TOOLS: [comma-separated tool names]\n" +
                "ANALYSIS_TASK: [detailed task description]\n" +
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
        plan.analystRole = "Senior Analyst";
        plan.analystGoal = "Analyze: '" + query + "'. Use all available tools.";
        plan.analystBackstory = "Experienced analyst. Uses tools for data, never fabricates.";
        plan.recommendedTools = "web_search,calculator,shell_command,http_request";
        plan.analysisTaskDescription = "Analyze: \"" + query + "\"\n\nUse tools to gather real data.\n" +
                "RULES: Use ONLY data from tools. Mark any data gaps.";
        plan.analysisExpectedOutput = "Analysis with real tool output and findings";
        plan.reportTaskDescription = "Write a comprehensive markdown report with all findings.";
        plan.qualityCriteria = "1. Real data from tools?\n2. Evidence-backed?\n3. Actionable?";
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
