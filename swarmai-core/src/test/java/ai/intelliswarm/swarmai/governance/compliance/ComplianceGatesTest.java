package ai.intelliswarm.swarmai.governance.compliance;

import ai.intelliswarm.swarmai.agent.hook.HookAction;
import ai.intelliswarm.swarmai.agent.hook.HookResult;
import ai.intelliswarm.swarmai.agent.hook.LifecycleEvent;
import ai.intelliswarm.swarmai.agent.llm.CitationLocation;
import ai.intelliswarm.swarmai.agent.llm.InlineCitation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ComplianceGatesTest {

    // ---------- CitationRequiredGate ----------

    @Test
    void strictGateBlocksUnsupportedNumericClaim() {
        CitationRequiredGate gate = CitationRequiredGate.strict();
        var output = ComplianceGate.Output.of(
                "Revenue grew 30% in FY25.",
                List.of()); // no citations
        var findings = gate.evaluate(output);
        assertThat(findings).isNotEmpty();
        assertThat(findings).anySatisfy(f ->
                assertThat(f.severity()).isEqualTo(ComplianceFinding.Severity.BLOCKER));
    }

    @Test
    void strictGateAcceptsClaimWithMatchingCitation() {
        CitationRequiredGate gate = CitationRequiredGate.strict();
        InlineCitation citation = new InlineCitation(
                "10K-2025", "ACME 10-K",
                new CitationLocation.PageLocation(42, 42),
                "Revenue grew 30% year-over-year.");
        var output = ComplianceGate.Output.of(
                "Revenue grew 30% in FY25.",
                List.of(citation));
        var findings = gate.evaluate(output);
        assertThat(findings).isEmpty();
    }

    @Test
    void strictGateFlagsClaimWhenCitationHasDifferentNumber() {
        CitationRequiredGate gate = CitationRequiredGate.strict();
        // Citation talks about 25% but output claims 30%
        InlineCitation citation = new InlineCitation(
                "10K-2025", "ACME 10-K",
                new CitationLocation.PageLocation(42, 42),
                "Revenue grew 25% year-over-year.");
        var output = ComplianceGate.Output.of(
                "Revenue grew 30% in FY25.",
                List.of(citation));
        var findings = gate.evaluate(output);
        assertThat(findings).anySatisfy(f ->
                assertThat(f.code()).isEqualTo("UNANCHORED_NUMERIC_CLAIM"));
    }

    @Test
    void lenientGateAcceptsCitedDocumentEvenIfNumbersDontMatch() {
        CitationRequiredGate gate = CitationRequiredGate.lenient();
        InlineCitation citation = new InlineCitation(
                "10K", "ACME", new CitationLocation.PageLocation(1, 1), "Different content");
        var output = ComplianceGate.Output.of(
                "Revenue was $1,200 million.",
                List.of(citation));
        var findings = gate.evaluate(output);
        // No per-sentence requirement, just count check
        assertThat(findings).isEmpty();
    }

    @Test
    void gateIgnoresNonNumericText() {
        CitationRequiredGate gate = CitationRequiredGate.strict();
        var output = ComplianceGate.Output.of(
                "The company has a strong management team and competitive moat.",
                List.of());
        assertThat(gate.evaluate(output)).isEmpty();
    }

    @Test
    void gateFlagsDollarAmounts() {
        CitationRequiredGate gate = CitationRequiredGate.strict();
        var output = ComplianceGate.Output.of(
                "EBITDA was $450 million.",
                List.of());
        assertThat(gate.evaluate(output)).isNotEmpty();
    }

    @Test
    void gateFlagsMultiples() {
        CitationRequiredGate gate = CitationRequiredGate.strict();
        var output = ComplianceGate.Output.of(
                "Trades at 12.5x EV/EBITDA.",
                List.of());
        assertThat(gate.evaluate(output)).isNotEmpty();
    }

    // ---------- ComplianceEvaluator ----------

    @Test
    void evaluatorAggregatesFindingsAcrossGates() {
        ComplianceEvaluator eval = new ComplianceEvaluator(List.of(
                CitationRequiredGate.strict(),
                new TestGate("test", List.of(ComplianceFinding.warn("TEST", "warn"),
                        ComplianceFinding.info("TEST", "info")))));
        var report = eval.evaluate(
                ComplianceGate.Output.of("Revenue was $100 million.", List.of()));
        assertThat(report.findings()).hasSizeGreaterThanOrEqualTo(3); // citation blocker + warn + info
        assertThat(report.pass()).isFalse();
    }

    @Test
    void evaluatorStopOnBlockerSkipsLaterGates() {
        TestGate later = new TestGate("later",
                List.of(ComplianceFinding.warn("LATER", "ran later")));
        ComplianceEvaluator eval = new ComplianceEvaluator(
                List.of(CitationRequiredGate.strict(), later),
                /* stopOnBlocker */ true);
        var report = eval.evaluate(
                ComplianceGate.Output.of("Revenue was $100 million.", List.of()));
        assertThat(report.findings()).noneMatch(f -> f.code().equals("LATER"));
    }

    @Test
    void cleanReportPassesAndCountsZero() {
        var report = ComplianceReport.clean();
        assertThat(report.pass()).isTrue();
        assertThat(report.blockerCount()).isZero();
    }

    // ---------- UniversalDisclaimer ----------

    @Test
    void disclaimerPrependsToUserPrompt() {
        UniversalDisclaimer hook = UniversalDisclaimer.financialServices();
        var event = new LifecycleEvent.UserPromptSubmit(
                "c1", "a1", Instant.now(), "Build a DCF for ACME.", Map.of());
        HookResult result = hook.onUserPromptSubmit(event);
        assertThat(result.action()).isEqualTo(HookAction.MODIFY);
        String rewritten = (String) result.modifiedPayload();
        assertThat(rewritten).contains("draft work product").contains("Build a DCF for ACME.");
    }

    @Test
    void disclaimerSupportsCustomText() {
        UniversalDisclaimer hook = UniversalDisclaimer.withText("CUSTOM HEADER");
        var event = new LifecycleEvent.UserPromptSubmit(
                "c1", "a1", Instant.now(), "Question?", Map.of());
        HookResult result = hook.onUserPromptSubmit(event);
        assertThat(((String) result.modifiedPayload())).startsWith("CUSTOM HEADER");
    }

    // ---------- DelegationDepthPolicy ----------

    @Test
    void policyAllowsFirstLevelDelegation() {
        DelegationDepthPolicy policy = new DelegationDepthPolicy(1);
        HookResult r = policy.onSubagentStart(new LifecycleEvent.SubagentStart(
                "corr1", "parent", Instant.now(), "child1", "general", "prompt", Map.of()));
        assertThat(r.action()).isEqualTo(HookAction.PROCEED);
        assertThat(policy.currentDepth("corr1")).isEqualTo(1);
    }

    @Test
    void policyBlocksSecondLevelDelegationWhenMaxIsOne() {
        DelegationDepthPolicy policy = new DelegationDepthPolicy(1);
        policy.onSubagentStart(start("corr1", "parent", "child1"));
        HookResult r = policy.onSubagentStart(start("corr1", "child1", "grandchild"));
        assertThat(r.action()).isEqualTo(HookAction.BLOCK);
        assertThat(r.message()).contains("Delegation depth").contains("exceeds policy max 1");
    }

    @Test
    void policyAllowsDeeperWhenMaxIsTwo() {
        DelegationDepthPolicy policy = new DelegationDepthPolicy(2);
        assertThat(policy.onSubagentStart(start("c", "p", "a")).action()).isEqualTo(HookAction.PROCEED);
        assertThat(policy.onSubagentStart(start("c", "a", "b")).action()).isEqualTo(HookAction.PROCEED);
        assertThat(policy.onSubagentStart(start("c", "b", "d")).action()).isEqualTo(HookAction.BLOCK);
    }

    @Test
    void policySubagentStopDecrementsDepth() {
        DelegationDepthPolicy policy = new DelegationDepthPolicy(1);
        policy.onSubagentStart(start("c1", "p", "child"));
        assertThat(policy.currentDepth("c1")).isEqualTo(1);
        policy.onSubagentStop(stop("c1", "child"));
        assertThat(policy.currentDepth("c1")).isZero();
        // After stop, another delegation is allowed again
        assertThat(policy.onSubagentStart(start("c1", "p", "newChild")).action())
                .isEqualTo(HookAction.PROCEED);
    }

    @Test
    void policyCorrelationsAreIndependent() {
        DelegationDepthPolicy policy = new DelegationDepthPolicy(1);
        policy.onSubagentStart(start("a", "p1", "c1"));
        // Other workflow's tree is independent
        assertThat(policy.onSubagentStart(start("b", "p2", "c2")).action())
                .isEqualTo(HookAction.PROCEED);
    }

    // ---------- SingleWriterInvariantEnforcer ----------

    @Test
    void firstWriterClaimsTheSlot() {
        SingleWriterInvariantEnforcer e = new SingleWriterInvariantEnforcer();
        HookResult r = e.onFileChanged(write("c1", "writer1", "/tmp/x.txt"));
        assertThat(r.action()).isEqualTo(HookAction.PROCEED);
        assertThat(e.currentWriter("c1")).isEqualTo("writer1");
    }

    @Test
    void sameWriterCanWriteRepeatedly() {
        SingleWriterInvariantEnforcer e = new SingleWriterInvariantEnforcer();
        e.onFileChanged(write("c1", "writer1", "/tmp/x.txt"));
        HookResult r = e.onFileChanged(write("c1", "writer1", "/tmp/y.txt"));
        assertThat(r.action()).isEqualTo(HookAction.PROCEED);
    }

    @Test
    void secondWriterIsBlocked() {
        SingleWriterInvariantEnforcer e = new SingleWriterInvariantEnforcer();
        e.onFileChanged(write("c1", "writer1", "/tmp/x.txt"));
        HookResult r = e.onFileChanged(write("c1", "writer2", "/tmp/y.txt"));
        assertThat(r.action()).isEqualTo(HookAction.BLOCK);
        assertThat(r.message()).contains("writer2").contains("writer1");
    }

    @Test
    void yieldWriterReleasesSlotForHandoff() {
        SingleWriterInvariantEnforcer e = new SingleWriterInvariantEnforcer();
        e.onFileChanged(write("c1", "writer1", "/tmp/x.txt"));
        String previous = e.yieldWriter("c1", "writer1");
        assertThat(previous).isEqualTo("writer1");
        // Now a different writer can claim the slot
        HookResult r = e.onFileChanged(write("c1", "writer2", "/tmp/x.txt"));
        assertThat(r.action()).isEqualTo(HookAction.PROCEED);
    }

    @Test
    void yieldByNonHolderDoesNotRelease() {
        SingleWriterInvariantEnforcer e = new SingleWriterInvariantEnforcer();
        e.onFileChanged(write("c1", "writer1", "/tmp/x.txt"));
        // writer2 doesn't hold the slot, can't release it
        String previous = e.yieldWriter("c1", "writer2");
        assertThat(previous).isEqualTo("writer1"); // returned info, but not released
        // writer1 still owns it
        assertThat(e.onFileChanged(write("c1", "writer3", "/tmp/y.txt")).action())
                .isEqualTo(HookAction.BLOCK);
    }

    // ---------- helpers ----------

    private static LifecycleEvent.SubagentStart start(String corr, String parent, String child) {
        return new LifecycleEvent.SubagentStart(corr, parent, Instant.now(),
                child, "general", "prompt", Map.of());
    }

    private static LifecycleEvent.SubagentStop stop(String corr, String child) {
        return new LifecycleEvent.SubagentStop(corr, child, Instant.now(),
                child, "ok", true, Map.of());
    }

    private static LifecycleEvent.FileChanged write(String corr, String agent, String path) {
        return new LifecycleEvent.FileChanged(corr, agent, Instant.now(), path,
                LifecycleEvent.FileChanged.ChangeKind.MODIFIED, Map.of());
    }

    private static final class TestGate implements ComplianceGate {
        private final String name;
        private final List<ComplianceFinding> findings;

        TestGate(String name, List<ComplianceFinding> findings) {
            this.name = name;
            this.findings = findings;
        }

        @Override public String name() { return name; }
        @Override public List<ComplianceFinding> evaluate(Output output) { return findings; }
    }
}
