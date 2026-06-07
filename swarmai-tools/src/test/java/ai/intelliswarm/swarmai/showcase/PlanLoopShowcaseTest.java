package ai.intelliswarm.swarmai.showcase;

import ai.intelliswarm.swarmai.agent.subagent.InProcessSubagentSpawner;
import ai.intelliswarm.swarmai.agent.subagent.Subagent;
import ai.intelliswarm.swarmai.agent.subagent.SubagentResult;
import ai.intelliswarm.swarmai.agent.subagent.SubagentSpec;
import ai.intelliswarm.swarmai.agent.subagent.SubagentSpawner;
import ai.intelliswarm.swarmai.state.plan.BanditApprovalPolicy;
import ai.intelliswarm.swarmai.state.plan.BanditApprovalTrainer;
import ai.intelliswarm.swarmai.state.plan.DriftBudgetApprovalPolicy;
import ai.intelliswarm.swarmai.state.plan.EvolvingPlan;
import ai.intelliswarm.swarmai.state.plan.InMemoryPlanChannelAccessor;
import ai.intelliswarm.swarmai.state.plan.LlmReplanner;
import ai.intelliswarm.swarmai.state.plan.PlanApprovalPolicy;
import ai.intelliswarm.swarmai.state.plan.PlanChannelAccessor;
import ai.intelliswarm.swarmai.state.plan.PlanContextRenderer;
import ai.intelliswarm.swarmai.state.plan.PlanEntry;
import ai.intelliswarm.swarmai.state.plan.PlanOperator;
import ai.intelliswarm.swarmai.state.plan.ReplanContext;
import ai.intelliswarm.swarmai.state.plan.ReplanTriggerPolicy;
import ai.intelliswarm.swarmai.state.plan.ReplannerLoop;
import ai.intelliswarm.swarmai.state.plan.Risk;
import ai.intelliswarm.swarmai.state.plan.RiskTieredApprovalPolicy;
import ai.intelliswarm.swarmai.tool.safety.MutationGuard;
import ai.intelliswarm.swarmai.tool.safety.MutationPlan;
import ai.intelliswarm.swarmai.tool.safety.PlanAwareMutationGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end showcase of the evolving plan loop. Runs as a single JUnit test
 * with heavy {@code System.out} narration so the output reads like
 * documentation — what's happening, what each component decided, and how the
 * bandit's understanding of the workflow shifts as outcomes accumulate.
 *
 * <p>Scenario: a code-deployment workflow with four mutating tool actions.
 * The first action seeds operator approval; the next two auto-approve via
 * the risk-tier + drift-budget + bandit policy. A HIGH-risk action routes
 * back to a human. Before deciding, the operator spawns an investigation
 * {@link Subagent}. The operator overrides the human approval after seeing
 * production trouble, the {@link ReplannerLoop} fires, and the
 * {@link LlmReplanner} proposes a rollback amendment.
 *
 * <p>To see the narration, run with surefire's {@code useFile=false}:
 * <pre>
 *   ./mvnw -pl swarmai-tools test -Dtest=PlanLoopShowcaseTest -Dsurefire.useFile=false
 * </pre>
 */
@DisplayName("End-to-end Plan Loop Showcase")
class PlanLoopShowcaseTest {

    private ExecutorService subagentExecutor;

    @BeforeEach
    void setUp() {
        subagentExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "showcase-subagent");
            t.setDaemon(true);
            return t;
        });
    }

    @AfterEach
    void tearDown() {
        subagentExecutor.shutdownNow();
    }

    @Test
    @DisplayName("Deploy workflow: seed → auto-approvals → operator delegates investigation to subagent → override → replanner emits rollback")
    void deployWorkflowEvolves() {
        banner("SwarmAI Plan-Loop Showcase");
        line("A simulated deploy workflow that demonstrates:");
        line("  • PlanAwareMutationGuard with composed policies (RiskTier ∧ DriftBudget ∧ Bandit)");
        line("  • Auto-approval for safe actions; human routing for HIGH-risk");
        line("  • Operator delegating investigation to an InProcessSubagent");
        line("  • Operator override → ReplannerLoop fires → LlmReplanner proposes amendment");
        line("  • BanditApprovalTrainer learning from PLAN_POLICY transitions only");
        nl();

        // ---------- 1. Wire the loop ----------
        banner("Setup");

        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        PlanContextRenderer renderer = new PlanContextRenderer();
        PlanOperator operator = new PlanOperator(channel);

        // Decisions are made by the deterministic static policies; the bandit
        // is attached to the trainer in OBSERVATION MODE — it learns silently
        // from outcomes but doesn't yet vote on auto-approvals. A real
        // deployment promotes the bandit into the policy chain (via
        // PlanApprovalPolicy.all(tier, budget, bandit) or
        // PlanApprovalPolicy.any(static, bandit)) once it has accumulated
        // enough confidence per bucket.
        BanditApprovalPolicy bandit = new BanditApprovalPolicy(
                BanditApprovalPolicy.DEFAULT_BUCKET_KEY, /*warmup*/ 0);
        PlanApprovalPolicy policy = PlanApprovalPolicy.all(
                new RiskTieredApprovalPolicy(),       // LOW/MEDIUM auto once seeded
                new DriftBudgetApprovalPolicy(5));    // up to 5 PROPOSED between approvals

        BanditApprovalTrainer.attach(channel, bandit);

        // Human delegate: pretend an operator named "alice" who approves whatever
        // is routed to her — for the purpose of this demo, the *override* later
        // is where dissent comes in.
        MutationGuard humanDelegate = plan ->
                MutationGuard.Decision.approve("alice", "operator approved at gate");

        PlanAwareMutationGuard guard = new PlanAwareMutationGuard(humanDelegate, channel, policy);

        // LLM replanner with a mock that returns a rollback amendment when a
        // rejection triggers it. Spring AI's BeanOutputConverter parses the JSON
        // back into Amendments.
        ChatClient replannerLlm = stubChatClient("""
                {
                  "newEntries": [
                    {"summary": "rollback prod to last-good", "risk": "HIGH",
                     "rationale": "production is failing post-promote; rollback restores stability"}
                  ],
                  "supersessions": [],
                  "rationale": "react to prod rejection by proposing rollback"
                }
                """);
        LlmReplanner replanner = new LlmReplanner(replannerLlm);

        ReplannerLoop loop = ReplannerLoop.start(channel, replanner,
                ReplanTriggerPolicy.onRejection(),
                () -> ReplanContext.builder()
                        .goal("ship the change safely")
                        .addObservation("prod monitoring shows error rate spike post-promote")
                        .build());

        // Subagent infra — operator can spawn investigators on demand.
        ChatClient auditorLlm = stubChatClient(
                "RISK ASSESSMENT: post-promote error rate jumped from 0.2% to 4.3%. " +
                "Recent dependency upgrade is the likely cause. Recommend ROLLBACK.");
        SubagentSpawner spawner = new InProcessSubagentSpawner(auditorLlm, subagentExecutor);

        line("Channel: in-memory");
        line("Policy:  RiskTier(LOW,MEDIUM auto once seeded) ∧ DriftBudget(5)");
        line("Bandit:  attached as observer via the trainer — learning, not yet voting");
        line("Trainer: scoring PLAN_POLICY transitions only (humans don't feed the bandit)");
        line("Replanner: triggers on REJECTION, mock LLM returns canned rollback amendment");
        line("Subagent spawner: in-process, mock auditor returns risk assessment");
        nl();

        // ---------- 2. Step through the workflow ----------
        banner("Step 1 — tool proposes seed action: stage_build (MEDIUM)");
        MutationGuard.Decision d1 = guard.check(deployPlan("stage_build", MutationPlan.RiskLevel.MEDIUM,
                "Stage the build artifact"));
        line("guard verdict: " + describe(d1));
        guard.observe(d1.correlationId(), true, "build staged");
        renderPlan(channel, renderer);

        assertEquals("alice", d1.decidedBy(),
                "Without a seed approval, even MEDIUM should route to human");

        banner("Step 2 — tool proposes run_tests (MEDIUM) — should auto-approve now that seed is in place");
        MutationGuard.Decision d2 = guard.check(deployPlan("run_tests", MutationPlan.RiskLevel.MEDIUM,
                "Run the test suite against staged build"));
        line("guard verdict: " + describe(d2));
        guard.observe(d2.correlationId(), true, "all tests pass");
        renderPlan(channel, renderer);

        assertEquals(PlanAwareMutationGuard.DECIDED_BY_POLICY, d2.decidedBy(),
                "Should auto-approve via RiskTier (MEDIUM, seeded)");

        banner("Step 3 — tool proposes promote_canary (MEDIUM) — auto-approve");
        MutationGuard.Decision d3 = guard.check(deployPlan("promote_canary", MutationPlan.RiskLevel.MEDIUM,
                "Roll out to 5% of traffic"));
        line("guard verdict: " + describe(d3));
        guard.observe(d3.correlationId(), true, "canary stable for 10 minutes");
        renderPlan(channel, renderer);

        assertEquals(PlanAwareMutationGuard.DECIDED_BY_POLICY, d3.decidedBy());

        line("Bandit so far for tool:deploy:MEDIUM:");
        line("  observations=" + bandit.observationCount("tool:deploy:" + Risk.MEDIUM));
        line("  meanSuccessRate=" + fmt(bandit.meanSuccessRate("tool:deploy:" + Risk.MEDIUM)));
        nl();

        banner("Step 4 — tool proposes promote_prod (HIGH) — RiskTier vetoes auto-approve");
        MutationGuard.Decision d4 = guard.check(deployPlan("promote_prod", MutationPlan.RiskLevel.HIGH,
                "Promote canary to 100% of prod"));
        line("guard verdict: " + describe(d4));
        line("HIGH risk forced human routing — alice approves at the gate.");
        line("(execution hasn't started yet — alice wants a second opinion first)");
        renderPlan(channel, renderer);

        assertEquals("alice", d4.decidedBy(),
                "HIGH should always route to human even after seed");

        // ---------- 3. Operator wants a second opinion before letting prod execution proceed ----------
        banner("Step 5 — pre-execution audit; alice spawns an auditor SUBAGENT");
        line("alice: \"this is the prod cut. Let me get an audit before I let it execute.\"");
        nl();

        SubagentSpec auditSpec = SubagentSpec.builder()
                .type("auditor")
                .systemPrompt("You are a release-safety auditor. Look at the change set and recent canary metrics.")
                .userPrompt("Assess whether promote_prod should proceed. " +
                        "Consider: canary saw error rate 22x baseline; recent dep upgrade is in scope.")
                .timeout(Duration.ofSeconds(5))
                .build();

        Subagent auditor = spawner.spawn(auditSpec);
        SubagentResult audit = auditor.awaitResult(Duration.ofSeconds(5));
        line("auditor subagent status:  " + audit.status());
        line("auditor durationMs:       " + audit.metrics().get("durationMs"));
        line("auditor verdict:");
        line("  > " + audit.output());
        nl();

        assertTrue(audit.succeeded());
        assertTrue(audit.output().contains("ROLLBACK"));

        // ---------- 4. Override → replanner fires ----------
        banner("Step 6 — alice overrides her earlier approval based on auditor's recommendation");
        PlanEntry prodEntry = findLatestForTool(channel, "deploy", "promote_prod");
        line("Rejecting entry " + shortId(prodEntry.id()) + " (was APPROVED; alice cancels before execution)");
        operator.reject(prodEntry.id(), "alice", "auditor recommended rollback after risk assessment");

        line("ReplannerLoop fired on the REJECTED transition.");
        line("LlmReplanner saw the rejection + observation, returned canned amendment.");
        line("Loop appended the new PROPOSED entry from replanner:llm:");
        nl();
        renderPlan(channel, renderer);

        // The replanner's amendment lives in the channel
        List<PlanEntry> proposedNow = channel.get().withStatus(PlanEntry.Status.PROPOSED);
        assertFalse(proposedNow.isEmpty(), "Replanner should have produced at least one PROPOSED entry");
        PlanEntry rollback = proposedNow.stream()
                .filter(e -> LlmReplanner.PROPOSED_BY.equals(e.proposedBy()))
                .findFirst()
                .orElseThrow();
        line("Found replanner's amendment: " + shortId(rollback.id())
                + " — " + rollback.metadata().get("summary"));
        nl();

        // ---------- 5. Final state + bandit learnings ----------
        banner("Final plan state");
        renderPlan(channel, renderer);

        banner("Bandit learnings");
        printBandit(bandit, "tool:deploy:" + Risk.MEDIUM);
        printBandit(bandit, "tool:deploy:" + Risk.HIGH);
        nl();
        line("Note: only PLAN_POLICY transitions feed the bandit. The MEDIUM bucket");
        line("learned from the two auto-approved-then-EXECUTED actions. The HIGH bucket");
        line("has no observations — alice approved-then-rejected manually, and the");
        line("trainer skips human-decided transitions to keep the bandit's signal honest.");
        nl();
        line("Once the bandit has accumulated confidence per bucket, a future deployment");
        line("can promote it from observer to vetoer by composing it into the policy:");
        line("  PlanApprovalPolicy.all(tier, budget, bandit)        // conservative");
        line("  PlanApprovalPolicy.any(all(tier, budget), bandit)   // bandit can bypass");

        loop.close();
    }

    // ========================================
    // Helpers
    // ========================================

    /**
     * Minimal mock {@link ChatClient} that returns {@code response} from
     * {@code .prompt().system(...).user(...).call().content()}. Lives here
     * (not in {@code MockChatClientFactory}) because that factory is in
     * swarmai-core's test-only sources, not test-jar exposed.
     */
    private static ChatClient stubChatClient(String response) {
        ChatClient c = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(c.prompt().system(anyString()).user(anyString()).call().content())
                .thenReturn(response);
        when(c.prompt().user(anyString()).call().content()).thenReturn(response);
        return c;
    }

    private static MutationPlan deployPlan(String op, MutationPlan.RiskLevel risk, String summary) {
        return MutationPlan.builder()
                .toolName("deploy")
                .summary(summary)
                .riskLevel(risk)
                .op(op, "release-2026-05-07")
                .metadata("apply", true)
                .build();
    }

    private static PlanEntry findLatestForTool(PlanChannelAccessor channel,
                                                String toolName,
                                                String opType) {
        EvolvingPlan plan = channel.get();
        for (int i = plan.entries().size() - 1; i >= 0; i--) {
            PlanEntry e = plan.entries().get(i);
            if (e.payload() instanceof MutationPlan mp
                    && mp.toolName().equals(toolName)
                    && mp.ops().stream().anyMatch(o -> o.type().equals(opType))) {
                return e;
            }
        }
        throw new IllegalStateException("no entry found for " + toolName + "/" + opType);
    }

    private static String describe(MutationGuard.Decision d) {
        return (d.approved() ? "APPROVED" : "REJECTED")
                + " by " + d.decidedBy()
                + (d.reason().isEmpty() ? "" : " — " + d.reason())
                + (d.correlationId() != null ? " (id=" + shortId(d.correlationId()) + ")" : "");
    }

    private static String shortId(String id) {
        return id == null ? "(null)" : id.substring(0, Math.min(8, id.length()));
    }

    private static String fmt(double d) {
        return String.format("%.3f", d);
    }

    private static void renderPlan(PlanChannelAccessor channel, PlanContextRenderer renderer) {
        String rendered = renderer.render(channel.get());
        if (rendered.isBlank()) {
            line("(plan is empty)");
        } else {
            for (String l : rendered.split("\n")) line(l);
        }
        nl();
    }

    private static void printBandit(BanditApprovalPolicy bandit, String key) {
        long obs = bandit.observationCount(key);
        if (obs == 0) {
            line(key + ": no observations yet");
        } else {
            line(key + ": observations=" + obs + ", meanSuccessRate=" + fmt(bandit.meanSuccessRate(key)));
        }
    }

    private static void banner(String title) {
        nl();
        line("======================================================================");
        line("  " + title);
        line("======================================================================");
    }

    private static void line(String s) {
        System.out.println(s);
    }

    private static void nl() {
        System.out.println();
    }
}
