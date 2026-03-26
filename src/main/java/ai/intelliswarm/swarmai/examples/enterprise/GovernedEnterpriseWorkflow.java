package ai.intelliswarm.swarmai.examples.enterprise;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.budget.BudgetExceededException;
import ai.intelliswarm.swarmai.budget.BudgetPolicy;
import ai.intelliswarm.swarmai.budget.BudgetSnapshot;
import ai.intelliswarm.swarmai.budget.BudgetTracker;
import ai.intelliswarm.swarmai.budget.InMemoryBudgetTracker;
import ai.intelliswarm.swarmai.governance.*;
import ai.intelliswarm.swarmai.memory.InMemoryMemory;
import ai.intelliswarm.swarmai.memory.Memory;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.OutputFormat;
import ai.intelliswarm.swarmai.tenant.InMemoryTenantQuotaEnforcer;
import ai.intelliswarm.swarmai.tenant.TenantContext;
import ai.intelliswarm.swarmai.tenant.TenantQuotaEnforcer;
import ai.intelliswarm.swarmai.tenant.TenantQuotaExceededException;
import ai.intelliswarm.swarmai.tenant.TenantResourceQuota;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Governed Enterprise Workflow — showcases SwarmAI's enterprise features.
 *
 * This example demonstrates three enterprise capabilities that differentiate
 * SwarmAI from personal AI assistants:
 *
 *   1. MULTI-TENANCY — Each team runs in an isolated tenant context with
 *      resource quotas (max concurrent workflows, max tokens). Memory and
 *      knowledge are automatically scoped per tenant.
 *
 *   2. BUDGET TRACKING — Every LLM call's token usage is tracked in real-time.
 *      A cost budget (USD) and token budget can be set per workflow, with
 *      WARN or HARD_STOP enforcement.
 *
 *   3. GOVERNANCE GATES — Human-in-the-loop approval checkpoints pause
 *      the workflow at configured points. An external system (REST API,
 *      Studio UI) approves or rejects, and the workflow resumes.
 *
 * Workflow:
 *   ┌─────────────────────────────────────────────────────────────────┐
 *   │  TENANT: "acme-research"        BUDGET: 500K tokens / $5.00    │
 *   │                                                                 │
 *   │   [Research Analyst]                                            │
 *   │        │                                                        │
 *   │        ▼                                                        │
 *   │   ── APPROVAL GATE ──  (pauses for human review)               │
 *   │        │                                                        │
 *   │        ▼                                                        │
 *   │   [Report Writer]                                               │
 *   │        │                                                        │
 *   │        ▼                                                        │
 *   │   Budget Snapshot logged (tokens used, estimated cost)          │
 *   └─────────────────────────────────────────────────────────────────┘
 *
 * Usage:
 *   // From Spring context:
 *   workflow.run("AI agents in enterprise", "acme-research");
 *
 *   // Or via command line:
 *   docker compose run --rm enterprise-governed "AI agents" acme-research
 */
@Component
public class GovernedEnterpriseWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(GovernedEnterpriseWorkflow.class);

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;

    public GovernedEnterpriseWorkflow(
            ChatClient.Builder chatClientBuilder,
            ApplicationEventPublisher eventPublisher) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
    }

    public void run(String... args) throws Exception {
        String topic = args.length > 0 ? args[0] : "AI agents in enterprise software";
        String tenantId = args.length > 1 ? args[1] : "acme-research";

        logger.info("\n" + "=".repeat(80));
        logger.info("GOVERNED ENTERPRISE WORKFLOW");
        logger.info("=".repeat(80));
        logger.info("Topic:    {}", topic);
        logger.info("Tenant:   {}", tenantId);
        logger.info("Features: Multi-tenancy | Budget Tracking | Governance Gates");
        logger.info("=".repeat(80));

        // =====================================================================
        // FEATURE 1: MULTI-TENANCY — Isolate resources per team
        // =====================================================================

        // Define per-tenant quotas
        TenantResourceQuota acmeQuota = TenantResourceQuota.builder(tenantId)
                .maxConcurrentWorkflows(5)
                .maxSkills(50)
                .maxMemoryEntries(5000)
                .maxTokenBudget(1_000_000)
                .build();

        TenantQuotaEnforcer quotaEnforcer = new InMemoryTenantQuotaEnforcer(
                Map.of(tenantId, acmeQuota),
                TenantResourceQuota.builder("default").build() // default quota
        );

        // Tenant-scoped memory — each tenant's data is isolated
        Memory memory = new InMemoryMemory();

        logger.info("\n--- Tenant Context ---");
        logger.info("Tenant '{}' quota: {} concurrent workflows, {} max tokens",
                tenantId, acmeQuota.maxConcurrentWorkflows(), acmeQuota.maxTokenBudget());
        logger.info("Active workflows for tenant: {}", quotaEnforcer.getActiveWorkflowCount(tenantId));

        // =====================================================================
        // FEATURE 2: BUDGET TRACKING — Real-time cost control
        // =====================================================================

        // Budget policy: 500K tokens max, $5 cost cap, WARN on 80% usage
        BudgetPolicy budgetPolicy = BudgetPolicy.builder()
                .maxTotalTokens(500_000)
                .maxCostUsd(5.00)
                .modelName("gpt-4o-mini")
                .onExceeded(BudgetPolicy.BudgetAction.WARN) // WARN doesn't stop; HARD_STOP throws
                .warningThresholdPercent(80.0)
                .build();

        logger.info("\n--- Budget Policy ---");
        logger.info("Max tokens:  {}", budgetPolicy.maxTotalTokens());
        logger.info("Max cost:    ${}", budgetPolicy.maxCostUsd());
        logger.info("On exceeded: {}", budgetPolicy.onExceeded());

        BudgetTracker budgetTracker = new InMemoryBudgetTracker(budgetPolicy);

        // =====================================================================
        // FEATURE 3: GOVERNANCE — Human-in-the-loop approval gates
        // =====================================================================

        // Create the governance engine
        ApprovalGateHandler gateHandler = new InMemoryApprovalGateHandler(eventPublisher);
        WorkflowGovernanceEngine governance = new WorkflowGovernanceEngine(gateHandler, eventPublisher);

        // Define an approval gate between research and writing
        // In production, this would pause and wait for a human to approve via REST API.
        // For this example, we auto-approve after 2 seconds to keep it runnable.
        ApprovalGate researchReviewGate = ApprovalGate.builder()
                .name("Research Review Gate")
                .description("Requires human review of research findings before report writing begins")
                .trigger(GateTrigger.AFTER_TASK)
                .timeout(Duration.ofSeconds(5))
                .policy(new ApprovalPolicy(1, List.of(), true)) // auto-approve on timeout for demo
                .build();

        logger.info("\n--- Governance Gates ---");
        logger.info("Gate: '{}' (trigger: {}, timeout: {}s, auto-approve: {})",
                researchReviewGate.name(),
                researchReviewGate.trigger(),
                researchReviewGate.timeout().toSeconds(),
                researchReviewGate.policy().autoApproveOnTimeout());

        // =====================================================================
        // AGENTS
        // =====================================================================

        ChatClient chatClient = chatClientBuilder.build();

        Agent researcher = Agent.builder()
                .role("Senior Research Analyst")
                .goal("Produce a data-driven research brief on: " + topic +
                      ". Focus on market size, key players, adoption trends, and risks. " +
                      "Every claim must be grounded — if data is unavailable, state so explicitly.")
                .backstory("You are a research analyst at a top management consulting firm. " +
                           "Your briefs are used by partners to advise Fortune 500 clients. " +
                           "You never fabricate data — your reputation depends on accuracy.")
                .chatClient(chatClient)
                .memory(memory)
                .verbose(true)
                .temperature(0.2)
                .modelName("gpt-4o-mini")
                .build();

        Agent writer = Agent.builder()
                .role("Executive Report Writer")
                .goal("Transform the research brief into a polished executive report on: " + topic +
                      ". Structure it for C-suite readers: lead with the conclusion, " +
                      "support with data, close with actionable recommendations.")
                .backstory("You write executive reports for a Big 4 consulting firm. " +
                           "Your reports are known for being concise yet comprehensive. " +
                           "Partners trust you to make complex topics accessible to executives.")
                .chatClient(chatClient)
                .memory(memory)
                .verbose(true)
                .temperature(0.3)
                .modelName("gpt-4o-mini")
                .build();

        // =====================================================================
        // TASKS
        // =====================================================================

        Task researchTask = Task.builder()
                .id("research")
                .description(String.format(
                        "Research '%s' and produce a structured brief.\n\n" +
                        "REQUIRED SECTIONS:\n" +
                        "1. MARKET OVERVIEW — Size, growth rate, key segments\n" +
                        "2. KEY PLAYERS — Top 5 companies with market positioning\n" +
                        "3. ADOPTION TRENDS — Enterprise adoption drivers and barriers\n" +
                        "4. RISK FACTORS — Top 5 risks for enterprises adopting this\n" +
                        "5. DATA GAPS — What you couldn't find and why it matters\n\n" +
                        "Mark all data as [CONFIRMED] or [ESTIMATE].",
                        topic))
                .expectedOutput("A 3-5 paragraph research brief with all 5 sections")
                .agent(researcher)
                .outputFormat(OutputFormat.MARKDOWN)
                .build();

        Task reportTask = Task.builder()
                .id("report")
                .description(String.format(
                        "Write an executive report on '%s' using the research brief.\n\n" +
                        "STRUCTURE:\n" +
                        "1. EXECUTIVE SUMMARY (100 words max) — Key finding + recommendation\n" +
                        "2. MARKET LANDSCAPE — Data from the brief, formatted for executives\n" +
                        "3. STRATEGIC IMPLICATIONS — What this means for our organization\n" +
                        "4. RECOMMENDATIONS — 3 concrete, prioritized next steps\n" +
                        "5. APPENDIX — Data sources and confidence levels",
                        topic))
                .expectedOutput("A polished executive report with clear recommendations")
                .agent(writer)
                .outputFormat(OutputFormat.MARKDOWN)
                .outputFile("output/enterprise_report_" + topic.replaceAll("[^a-zA-Z0-9]", "_").toLowerCase() + ".md")
                .dependsOn(researchTask)
                .build();

        // =====================================================================
        // SWARM — With all enterprise features wired in
        // =====================================================================

        Swarm swarm = Swarm.builder()
                .id("enterprise-governed-" + System.currentTimeMillis())
                .agent(researcher)
                .agent(writer)
                .task(researchTask)
                .task(reportTask)
                .process(ProcessType.SEQUENTIAL)
                .verbose(true)
                .eventPublisher(eventPublisher)
                .memory(memory)
                // Enterprise features
                .tenantId(tenantId)                          // Multi-tenancy
                .tenantQuotaEnforcer(quotaEnforcer)          // Resource quotas
                .budgetTracker(budgetTracker)                // Cost tracking
                .budgetPolicy(budgetPolicy)                  // Budget limits
                .governance(governance)                      // Governance engine
                .approvalGate(researchReviewGate)            // Approval gate
                .build();

        // =====================================================================
        // EXECUTE
        // =====================================================================

        logger.info("\n" + "-".repeat(60));
        logger.info("Executing governed workflow...");
        logger.info("-".repeat(60));

        long startTime = System.currentTimeMillis();
        SwarmOutput result;

        try {
            Map<String, Object> inputs = new HashMap<>();
            inputs.put("topic", topic);

            result = swarm.kickoff(inputs);

        } catch (TenantQuotaExceededException e) {
            logger.error("TENANT QUOTA EXCEEDED: {} — {}", e.getTenantId(), e.getMessage());
            throw e;
        } catch (BudgetExceededException e) {
            logger.error("BUDGET EXCEEDED: {}", e.getMessage());
            logger.error("Budget snapshot: {} tokens, ${}",
                    e.getSnapshot().totalTokensUsed(),
                    String.format("%.4f", e.getSnapshot().estimatedCostUsd()));
            throw e;
        } catch (GovernanceException e) {
            logger.error("GOVERNANCE GATE REJECTED: gate={}, request={}, status={}",
                    e.getGateId(), e.getRequestId(), e.getStatus());
            throw e;
        }

        long duration = System.currentTimeMillis() - startTime;

        // =====================================================================
        // RESULTS — Show enterprise telemetry
        // =====================================================================

        logger.info("\n" + "=".repeat(80));
        logger.info("GOVERNED ENTERPRISE WORKFLOW — RESULTS");
        logger.info("=".repeat(80));

        // Tenant context
        logger.info("\n--- Tenant ---");
        logger.info("Tenant ID:        {}", tenantId);
        logger.info("Active workflows: {} (after completion)",
                quotaEnforcer.getActiveWorkflowCount(tenantId));
        logger.info("Memory entries:   {}", memory.size());

        // Budget summary
        BudgetSnapshot snapshot = budgetTracker.getSnapshot(swarm.getId());
        logger.info("\n--- Budget ---");
        if (snapshot != null) {
            logger.info("Tokens used:      {} / {} ({}%)",
                    snapshot.totalTokensUsed(), budgetPolicy.maxTotalTokens(),
                    String.format("%.1f", snapshot.tokenUtilizationPercent()));
            logger.info("  Prompt:         {}", snapshot.promptTokensUsed());
            logger.info("  Completion:     {}", snapshot.completionTokensUsed());
            logger.info("Estimated cost:   ${} / ${} ({}%)",
                    String.format("%.4f", snapshot.estimatedCostUsd()),
                    budgetPolicy.maxCostUsd(),
                    String.format("%.1f", snapshot.costUtilizationPercent()));
            logger.info("Budget exceeded:  {}", snapshot.isExceeded());
        } else {
            logger.info("(No budget data — tokens not reported by mock client)");
        }

        // Governance summary
        logger.info("\n--- Governance ---");
        List<ApprovalRequest> allRequests = gateHandler.getPendingRequests();
        logger.info("Pending approvals: {}", allRequests.size());
        logger.info("Gates passed:      {}", researchReviewGate.name());

        // Workflow results
        logger.info("\n--- Workflow ---");
        logger.info("Duration:         {} seconds", duration / 1000);
        logger.info("Success:          {}", result.isSuccessful());
        logger.info("Tasks completed:  {}", result.getTaskOutputs().size());
        logger.info("Success rate:     {}%%", (int) (result.getSuccessRate() * 100));

        // Per-task breakdown
        logger.info("\n--- Task Breakdown ---");
        for (var taskOutput : result.getTaskOutputs()) {
            logger.info("  [{}] {} chars | {} prompt + {} completion tokens",
                    taskOutput.getTaskId(),
                    taskOutput.getRawOutput() != null ? taskOutput.getRawOutput().length() : 0,
                    taskOutput.getPromptTokens() != null ? taskOutput.getPromptTokens() : 0,
                    taskOutput.getCompletionTokens() != null ? taskOutput.getCompletionTokens() : 0);
        }

        // Token usage summary
        logger.info("\n{}", result.getTokenUsageSummary("gpt-4o-mini"));

        // Final output
        logger.info("\n--- Executive Report ---\n{}", result.getFinalOutput());
        logger.info("=".repeat(80));
    }
}
