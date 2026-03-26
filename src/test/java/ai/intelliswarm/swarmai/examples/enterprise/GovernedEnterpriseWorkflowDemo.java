package ai.intelliswarm.swarmai.examples.enterprise;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.base.BaseSwarmTest;
import ai.intelliswarm.swarmai.base.MockChatClientFactory;
import ai.intelliswarm.swarmai.budget.*;
import ai.intelliswarm.swarmai.governance.*;
import ai.intelliswarm.swarmai.memory.InMemoryMemory;
import ai.intelliswarm.swarmai.memory.Memory;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.OutputFormat;
import ai.intelliswarm.swarmai.tenant.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runnable demo of SwarmAI's enterprise features.
 *
 * Run with:
 *   mvnw.cmd test -Dtest="GovernedEnterpriseWorkflowDemo" -f pom.xml
 *
 * Demonstrates:
 *   1. Multi-tenancy — tenant isolation with resource quotas
 *   2. Budget tracking — real-time token/cost monitoring per workflow
 *   3. Governance gates — human-in-the-loop approval checkpoints
 */
@DisplayName("Enterprise Features Demo")
class GovernedEnterpriseWorkflowDemo extends BaseSwarmTest {

    @Test
    @DisplayName("Full governed enterprise workflow with tenant + budget + governance")
    void demo_fullGovernedWorkflow() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("  SWARMAI ENTERPRISE FEATURES DEMO");
        System.out.println("=".repeat(80));

        // =====================================================================
        // FEATURE 1: MULTI-TENANCY
        // =====================================================================

        System.out.println("\n--- FEATURE 1: Multi-Tenancy ---\n");

        String tenantId = "acme-research";

        // Define per-tenant quotas
        TenantResourceQuota quota = TenantResourceQuota.builder(tenantId)
                .maxConcurrentWorkflows(3)
                .maxSkills(50)
                .maxTokenBudget(500_000)
                .build();

        TenantQuotaEnforcer quotaEnforcer = new InMemoryTenantQuotaEnforcer(
                Map.of(tenantId, quota),
                TenantResourceQuota.builder("default").build()
        );

        System.out.println("  Tenant:           " + tenantId);
        System.out.println("  Max workflows:    " + quota.maxConcurrentWorkflows());
        System.out.println("  Max token budget: " + quota.maxTokenBudget());
        System.out.println("  Active workflows: " + quotaEnforcer.getActiveWorkflowCount(tenantId));

        // Tenant-scoped memory
        Memory memory = new InMemoryMemory();

        // =====================================================================
        // FEATURE 2: BUDGET TRACKING
        // =====================================================================

        System.out.println("\n--- FEATURE 2: Budget Tracking ---\n");

        BudgetPolicy budgetPolicy = BudgetPolicy.builder()
                .maxTotalTokens(100_000)
                .maxCostUsd(2.00)
                .modelName("gpt-4o-mini")
                .onExceeded(BudgetPolicy.BudgetAction.WARN)
                .warningThresholdPercent(80.0)
                .build();

        BudgetTracker budgetTracker = new InMemoryBudgetTracker(budgetPolicy);

        System.out.println("  Max tokens: " + budgetPolicy.maxTotalTokens());
        System.out.println("  Max cost:   $" + budgetPolicy.maxCostUsd());
        System.out.println("  On exceed:  " + budgetPolicy.onExceeded());

        // =====================================================================
        // FEATURE 3: GOVERNANCE GATES
        // =====================================================================

        System.out.println("\n--- FEATURE 3: Governance Gates ---\n");

        ApprovalGateHandler gateHandler = new InMemoryApprovalGateHandler(mockEventPublisher);
        WorkflowGovernanceEngine governance = new WorkflowGovernanceEngine(
                gateHandler, mockEventPublisher);

        // Gate auto-approves after 1 second (for demo purposes)
        ApprovalGate reviewGate = ApprovalGate.builder()
                .name("Research Quality Gate")
                .description("Senior analyst reviews research before report writing")
                .trigger(GateTrigger.AFTER_TASK)
                .timeout(Duration.ofSeconds(2))
                .policy(new ApprovalPolicy(1, List.of(), true)) // auto-approve on timeout
                .build();

        System.out.println("  Gate:         " + reviewGate.name());
        System.out.println("  Trigger:      " + reviewGate.trigger());
        System.out.println("  Timeout:      " + reviewGate.timeout().toSeconds() + "s");
        System.out.println("  Auto-approve: " + reviewGate.policy().autoApproveOnTimeout());

        // =====================================================================
        // AGENTS — Mock LLM responses to keep the demo self-contained
        // =====================================================================

        System.out.println("\n--- Agents ---\n");

        String researchResponse = """
                # Research Brief: AI Agents in Enterprise Software

                ## 1. MARKET OVERVIEW
                The enterprise AI agent market is valued at $5.1B in 2025 [CONFIRMED],
                growing at 34% CAGR. Key segments: customer service automation (38%),
                code generation (25%), data analysis (22%), document processing (15%).

                ## 2. KEY PLAYERS
                | Company | Focus | Market Share |
                |---------|-------|-------------|
                | Microsoft (Copilot) | Productivity | 28% [ESTIMATE] |
                | Google (Gemini) | Search + Enterprise | 22% [ESTIMATE] |
                | Anthropic (Claude) | Safety-focused AI | 15% [ESTIMATE] |
                | OpenAI (ChatGPT Enterprise) | General-purpose | 20% [ESTIMATE] |

                ## 3. ADOPTION TRENDS
                - 67% of Fortune 500 companies have piloted AI agents [CONFIRMED]
                - Primary driver: 40% reduction in repetitive task time [ESTIMATE]
                - Primary barrier: data privacy and compliance concerns

                ## 4. RISK FACTORS
                - Hallucination risk in decision-critical workflows
                - Regulatory uncertainty (EU AI Act implications)
                - Vendor lock-in with proprietary models
                - Skills gap in AI agent deployment and governance
                - Cost unpredictability with token-based pricing

                ## 5. DATA GAPS
                - Market share data is estimated (no public source)
                - ROI metrics are self-reported by vendors
                - Data completeness: MEDIUM
                """;

        String reportResponse = """
                # Executive Report: AI Agents in Enterprise Software

                ## EXECUTIVE SUMMARY
                AI agents represent a $5.1B market opportunity growing at 34% CAGR.
                **Recommendation: INVEST** — establish an internal AI agent platform
                within 6 months to capture early-mover advantage in automation.
                Confidence: MEDIUM (limited ROI data available).

                ## MARKET LANDSCAPE
                The market is consolidating around four major players, with Microsoft
                leading at an estimated 28% share through Copilot integration.
                Enterprise adoption has reached critical mass: 67% of Fortune 500
                companies have completed AI agent pilots.

                ## STRATEGIC IMPLICATIONS
                1. First-mover advantage is narrowing — delay increases catch-up cost
                2. Governance tooling (budget control, approval gates) is now table stakes
                3. Multi-model strategy reduces vendor lock-in risk

                ## RECOMMENDATIONS
                1. **Immediate (30 days):** Launch pilot with governed workflow platform
                2. **Short-term (90 days):** Establish AI governance committee
                3. **Medium-term (180 days):** Deploy production AI agent workflows
                   with budget controls and tenant isolation

                ## APPENDIX
                - Market size: Industry reports (CONFIRMED)
                - Market share: Analyst estimates (ESTIMATE)
                - Adoption rates: Fortune 500 survey (CONFIRMED)
                """;

        ChatClient researchClient = MockChatClientFactory.withResponse(researchResponse);
        ChatClient writerClient = MockChatClientFactory.withResponse(reportResponse);

        Agent researcher = Agent.builder()
                .role("Senior Research Analyst")
                .goal("Research AI agents in enterprise software")
                .backstory("Expert research analyst at a top consulting firm")
                .chatClient(researchClient)
                .memory(memory)
                .verbose(true)
                .temperature(0.2)
                .build();

        Agent writer = Agent.builder()
                .role("Executive Report Writer")
                .goal("Write an executive report from research findings")
                .backstory("Senior report writer at a Big 4 consulting firm")
                .chatClient(writerClient)
                .memory(memory)
                .verbose(true)
                .temperature(0.3)
                .build();

        System.out.println("  Researcher:   " + researcher.getRole());
        System.out.println("  Writer:       " + writer.getRole());

        // =====================================================================
        // TASKS
        // =====================================================================

        Task researchTask = Task.builder()
                .id("research")
                .description("Research 'AI agents in enterprise software' and produce a brief")
                .expectedOutput("Structured research brief with market data")
                .agent(researcher)
                .outputFormat(OutputFormat.MARKDOWN)
                .build();

        Task reportTask = Task.builder()
                .id("report")
                .description("Write an executive report from the research brief")
                .expectedOutput("Polished executive report with recommendations")
                .agent(writer)
                .outputFormat(OutputFormat.MARKDOWN)
                .dependsOn(researchTask)
                .build();

        // =====================================================================
        // SWARM — All enterprise features wired together
        // =====================================================================

        System.out.println("\n--- Building Governed Swarm ---\n");

        Swarm swarm = Swarm.builder()
                .id("enterprise-demo")
                .agent(researcher)
                .agent(writer)
                .task(researchTask)
                .task(reportTask)
                .process(ProcessType.SEQUENTIAL)
                .verbose(true)
                .eventPublisher(mockEventPublisher)
                .memory(memory)
                // Enterprise features
                .tenantId(tenantId)
                .tenantQuotaEnforcer(quotaEnforcer)
                .budgetTracker(budgetTracker)
                .budgetPolicy(budgetPolicy)
                .governance(governance)
                .approvalGate(reviewGate)
                .build();

        System.out.println("  Swarm ID:     " + swarm.getId());
        System.out.println("  Process:      " + swarm.getProcessType());
        System.out.println("  Tenant:       " + swarm.getTenantId());
        System.out.println("  Budget:       " + budgetPolicy.maxTotalTokens() + " tokens / $" + budgetPolicy.maxCostUsd());
        System.out.println("  Gates:        " + reviewGate.name());

        // =====================================================================
        // EXECUTE
        // =====================================================================

        System.out.println("\n" + "-".repeat(60));
        System.out.println("  EXECUTING GOVERNED WORKFLOW...");
        System.out.println("-".repeat(60));

        long start = System.currentTimeMillis();
        SwarmOutput result = swarm.kickoff(Map.of("topic", "AI agents in enterprise"));
        long duration = System.currentTimeMillis() - start;

        // =====================================================================
        // RESULTS
        // =====================================================================

        System.out.println("\n" + "=".repeat(80));
        System.out.println("  RESULTS");
        System.out.println("=".repeat(80));

        // Tenant
        System.out.println("\n  [TENANT]");
        System.out.println("  Tenant ID:          " + tenantId);
        System.out.println("  Active workflows:   " + quotaEnforcer.getActiveWorkflowCount(tenantId)
                + " (released after completion)");
        System.out.println("  Memory entries:     " + memory.size());

        // Budget
        System.out.println("\n  [BUDGET]");
        BudgetSnapshot snapshot = budgetTracker.getSnapshot(swarm.getId());
        if (snapshot != null) {
            System.out.printf("  Tokens used:        %,d / %,d (%.1f%%)%n",
                    snapshot.totalTokensUsed(), budgetPolicy.maxTotalTokens(),
                    snapshot.tokenUtilizationPercent());
            System.out.printf("  Prompt tokens:      %,d%n", snapshot.promptTokensUsed());
            System.out.printf("  Completion tokens:  %,d%n", snapshot.completionTokensUsed());
            System.out.printf("  Estimated cost:     $%.4f / $%.2f (%.1f%%)%n",
                    snapshot.estimatedCostUsd(), budgetPolicy.maxCostUsd(),
                    snapshot.costUtilizationPercent());
            System.out.println("  Budget exceeded:    " + snapshot.isExceeded());
        } else {
            System.out.println("  (Budget tracked at Swarm level — see token summary below)");
        }

        // Governance
        System.out.println("\n  [GOVERNANCE]");
        System.out.println("  Pending approvals:  " + gateHandler.getPendingRequests().size());
        System.out.println("  Gate passed:        " + reviewGate.name());

        // Workflow
        System.out.println("\n  [WORKFLOW]");
        System.out.println("  Duration:           " + duration + "ms");
        System.out.println("  Success:            " + result.isSuccessful());
        System.out.println("  Tasks completed:    " + result.getTaskOutputs().size());
        System.out.printf("  Success rate:       %.0f%%%n", result.getSuccessRate() * 100);

        // Per-task breakdown
        System.out.println("\n  [TASK BREAKDOWN]");
        for (var taskOutput : result.getTaskOutputs()) {
            System.out.printf("  %-20s %5d chars | %3d prompt + %3d completion tokens%n",
                    taskOutput.getTaskId(),
                    taskOutput.getRawOutput() != null ? taskOutput.getRawOutput().length() : 0,
                    taskOutput.getPromptTokens() != null ? taskOutput.getPromptTokens() : 0,
                    taskOutput.getCompletionTokens() != null ? taskOutput.getCompletionTokens() : 0);
        }

        // Token usage
        System.out.println("\n  [TOKEN USAGE]");
        System.out.printf("  Total prompt:       %,d%n", result.getTotalPromptTokens());
        System.out.printf("  Total completion:   %,d%n", result.getTotalCompletionTokens());
        System.out.printf("  Total tokens:       %,d%n", result.getTotalTokens());

        // Final output preview
        System.out.println("\n  [EXECUTIVE REPORT — First 500 chars]");
        String finalOutput = result.getFinalOutput();
        if (finalOutput != null && finalOutput.length() > 500) {
            System.out.println("  " + finalOutput.substring(0, 500).replace("\n", "\n  ") + "...");
        } else {
            System.out.println("  " + (finalOutput != null ? finalOutput.replace("\n", "\n  ") : "(empty)"));
        }

        System.out.println("\n" + "=".repeat(80));
        System.out.println("  DEMO COMPLETE — All 3 enterprise features working");
        System.out.println("=".repeat(80) + "\n");

        // Assertions
        assertNotNull(result);
        assertTrue(result.isSuccessful());
        assertEquals(2, result.getTaskOutputs().size());
        assertEquals(0, quotaEnforcer.getActiveWorkflowCount(tenantId)); // quota released
        assertTrue(memory.size() > 0); // agents saved to memory
    }

    @Test
    @DisplayName("Tenant quota enforcement blocks excess workflows")
    void demo_tenantQuotaBlocks() {
        System.out.println("\n--- Demo: Tenant Quota Enforcement ---\n");

        String tenantId = "small-team";

        // Quota: only 1 concurrent workflow allowed
        TenantResourceQuota quota = TenantResourceQuota.builder(tenantId)
                .maxConcurrentWorkflows(1)
                .build();

        TenantQuotaEnforcer enforcer = new InMemoryTenantQuotaEnforcer(
                Map.of(tenantId, quota),
                TenantResourceQuota.builder("default").build()
        );

        // First workflow starts fine
        enforcer.recordWorkflowStart(tenantId);
        System.out.println("  Workflow 1 started — active: " + enforcer.getActiveWorkflowCount(tenantId));

        // Second workflow is blocked
        try {
            enforcer.checkWorkflowQuota(tenantId);
            fail("Should have thrown TenantQuotaExceededException");
        } catch (TenantQuotaExceededException e) {
            System.out.println("  Workflow 2 BLOCKED: " + e.getMessage());
            assertEquals(tenantId, e.getTenantId());
        }

        // After first completes, second can start
        enforcer.recordWorkflowEnd(tenantId);
        enforcer.checkWorkflowQuota(tenantId); // no exception
        System.out.println("  Workflow 1 ended — active: " + enforcer.getActiveWorkflowCount(tenantId));
        System.out.println("  Workflow 2 now allowed!");
    }

    @Test
    @DisplayName("Budget HARD_STOP halts workflow mid-execution")
    void demo_budgetHardStop() {
        System.out.println("\n--- Demo: Budget Hard Stop ---\n");

        // Very tight budget: 200 tokens max
        BudgetPolicy tightPolicy = BudgetPolicy.builder()
                .maxTotalTokens(200)
                .maxCostUsd(0.01)
                .modelName("gpt-4o-mini")
                .onExceeded(BudgetPolicy.BudgetAction.HARD_STOP)
                .build();

        BudgetTracker tracker = new InMemoryBudgetTracker(tightPolicy);
        tracker.setBudgetPolicy("demo-wf", tightPolicy);

        // First call: 150 tokens — within budget
        tracker.recordUsage("demo-wf", 100, 50, "gpt-4o-mini");
        BudgetSnapshot snap1 = tracker.getSnapshot("demo-wf");
        System.out.printf("  After call 1: %d tokens (%.1f%% of budget)%n",
                snap1.totalTokensUsed(), snap1.tokenUtilizationPercent());

        // Second call: 100 more tokens — exceeds the 200 token budget
        try {
            tracker.recordUsage("demo-wf", 60, 40, "gpt-4o-mini");
            fail("Should have thrown BudgetExceededException");
        } catch (BudgetExceededException e) {
            System.out.println("  HARD STOP: " + e.getMessage());
            System.out.printf("  Final: %d tokens used, $%.6f cost%n",
                    e.getSnapshot().totalTokensUsed(),
                    e.getSnapshot().estimatedCostUsd());
        }
    }

    @Test
    @DisplayName("Governance gate blocks then approves")
    void demo_governanceGateApproval() throws Exception {
        System.out.println("\n--- Demo: Governance Approval Gate ---\n");

        ApprovalGateHandler handler = new InMemoryApprovalGateHandler(null);
        WorkflowGovernanceEngine engine = new WorkflowGovernanceEngine(handler, null);

        ApprovalGate gate = ApprovalGate.builder()
                .name("Compliance Review")
                .trigger(GateTrigger.BEFORE_TASK)
                .timeout(Duration.ofSeconds(5))
                .policy(new ApprovalPolicy(1, List.of(), false)) // no auto-approve
                .build();

        GovernanceContext ctx = new GovernanceContext("swarm-1", "task-1", "acme", 1, Map.of());

        // Simulate: approval comes from another thread (e.g., REST API call)
        Thread approverThread = new Thread(() -> {
            try {
                Thread.sleep(500); // Simulate human taking 500ms to review
                List<ApprovalRequest> pending = handler.getPendingRequests();
                if (!pending.isEmpty()) {
                    String requestId = pending.get(0).getRequestId();
                    handler.approve(requestId, "jane.doe@acme.com", "Research looks solid");
                    System.out.println("  [Approver] Approved request: " + requestId);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        approverThread.start();

        System.out.println("  [Workflow] Requesting approval at gate: " + gate.name());
        long start = System.currentTimeMillis();

        // This blocks until approved (or timeout)
        engine.checkGate(gate, ctx);

        long waited = System.currentTimeMillis() - start;
        System.out.println("  [Workflow] Gate passed! Waited " + waited + "ms for approval");
        System.out.println("  [Workflow] Continuing execution...");

        approverThread.join(2000);

        assertTrue(waited >= 400, "Should have waited for the approver");
        assertTrue(waited < 3000, "Should not have timed out");
    }
}
