package ai.intelliswarm.swarmai.selfimproving;

import ai.intelliswarm.swarmai.selfimproving.model.*;
import ai.intelliswarm.swarmai.selfimproving.model.GenericRule.RuleCategory;
import ai.intelliswarm.swarmai.selfimproving.model.GenericRule.ValidationResult;
import ai.intelliswarm.swarmai.selfimproving.reporter.GitHubImprovementReporter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class GitHubImprovementReporterTest {

    private GitHubImprovementReporter reporter;

    @BeforeEach
    void setUp() {
        // No token — so actual API calls won't be made, but we can test PR body generation
        reporter = new GitHubImprovementReporter(
                "intelliswarm-ai", "swarm-ai", null, "main"
        );
    }

    @Test
    void shouldSkipWhenNoToken() {
        ImprovementProposal proposal = buildProposal(ImprovementTier.TIER_1_AUTOMATIC);
        Optional<String> prUrl = reporter.report(proposal);
        assertTrue(prUrl.isEmpty(), "Should skip PR creation when no token configured");
    }

    @Test
    void shouldBuildPrBodyWithAllSections() {
        ImprovementProposal proposal = buildProposal(ImprovementTier.TIER_1_AUTOMATIC);
        String body = reporter.buildPrBody(proposal);

        assertNotNull(body);
        assertTrue(body.contains("Self-Improvement: Framework Intelligence Update"));
        assertTrue(body.contains("10%"));
        assertTrue(body.contains("What changes"));
        assertTrue(body.contains("Generic rule"));
        assertTrue(body.contains("Evidence"));
        assertTrue(body.contains("Cross-validation"));
        assertTrue(body.contains("Who benefits on upgrade"));
        assertTrue(body.contains("Tier"));
    }

    @Test
    void shouldIncludeAutoMergeNoteForTier1() {
        ImprovementProposal proposal = buildProposal(ImprovementTier.TIER_1_AUTOMATIC);
        String body = reporter.buildPrBody(proposal);
        assertTrue(body.contains("auto-merged"));
    }

    @Test
    void shouldIncludeConditionInPrBody() {
        ImprovementProposal proposal = buildProposal(ImprovementTier.TIER_2_REVIEW);
        String body = reporter.buildPrBody(proposal);
        assertTrue(body.contains("max_depth"));
    }

    @Test
    void shouldIncludeEvidenceInPrBody() {
        ImprovementProposal proposal = buildProposal(ImprovementTier.TIER_2_REVIEW);
        String body = reporter.buildPrBody(proposal);
        assertTrue(body.contains("SELF_IMPROVING"));
        assertTrue(body.contains("15"));  // occurrences
    }

    @Test
    void shouldIncludeCrossValidationInPrBody() {
        ImprovementProposal proposal = buildProposal(ImprovementTier.TIER_1_AUTOMATIC);
        String body = reporter.buildPrBody(proposal);
        assertTrue(body.contains("PASSED"));
        assertTrue(body.contains("Matched 4/5"));
    }

    @Test
    void shouldIncludeTokenSavingsInPrBody() {
        ImprovementProposal proposal = buildProposal(ImprovementTier.TIER_1_AUTOMATIC);
        String body = reporter.buildPrBody(proposal);
        assertTrue(body.contains("33,000"));
    }

    @Test
    void shouldMergeExistingRulesInsteadOfOverwriting() {
        Map<String, Object> existing = new LinkedHashMap<>();
        existing.put("rule_id", "existing-rule");
        existing.put("condition", "already-learned");

        Map<String, Object> incoming = new LinkedHashMap<>();
        incoming.put("rule_id", "new-rule");
        incoming.put("condition", "fresh-pattern");

        List<Map<String, Object>> merged = invokeMergeRules(List.of(existing), incoming);

        assertEquals(2, merged.size());
        assertTrue(merged.stream().anyMatch(rule -> "existing-rule".equals(rule.get("rule_id"))));
        assertTrue(merged.stream().anyMatch(rule -> "new-rule".equals(rule.get("rule_id"))));
    }

    @Test
    void shouldReplaceRuleWhenRuleIdAlreadyExists() {
        Map<String, Object> existing = new LinkedHashMap<>();
        existing.put("rule_id", "rule-1");
        existing.put("condition", "old-condition");

        Map<String, Object> incoming = new LinkedHashMap<>();
        incoming.put("rule_id", "rule-1");
        incoming.put("condition", "new-condition");

        List<Map<String, Object>> merged = invokeMergeRules(List.of(existing), incoming);

        assertEquals(1, merged.size());
        assertEquals("new-condition", merged.get(0).get("condition"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> invokeMergeRules(List<Map<String, Object>> existing,
                                                        Map<String, Object> incoming) {
        try {
            var method = GitHubImprovementReporter.class.getDeclaredMethod("mergeRules", List.class, Map.class);
            method.setAccessible(true);
            return (List<Map<String, Object>>) method.invoke(reporter, existing, incoming);
        } catch (Exception e) {
            fail("Failed to invoke mergeRules: " + e.getMessage());
            return List.of();
        }
    }

    private ImprovementProposal buildProposal(ImprovementTier tier) {
        WorkflowShape shape = new WorkflowShape(3, 2, true, false, false,
                Set.of("WEB"), "SELF_IMPROVING", 2, 1.0, true, false);
        SpecificObservation obs = new SpecificObservation("obs-1",
                SpecificObservation.ObservationType.CONVERGENCE_PATTERN,
                shape, "Converged at iteration 2", Map.of("converged_at", 2), 0.7, Instant.now());

        GenericRule rule = new GenericRule("rule-1", RuleCategory.CONVERGENCE_DEFAULT,
                Map.of("max_depth", "<=3", "has_skill_gen", false),
                "Reduce maxIterations for shallow workflows", 3, 0.90,
                List.of(obs, obs, obs),
                ValidationResult.passed(5, 4, List.of("Matched 4/5")),
                Instant.now(), Instant.now());

        return ImprovementProposal.builder()
                .tier(tier)
                .rule(rule)
                .improvement(new ImprovementProposal.Improvement(
                        "intelligence/convergence-defaults.json", "maxIterations", 5, 3,
                        "35% token reduction for shallow workflows", Map.of()))
                .origin(new ImprovementProposal.Origin(
                        "SELF_IMPROVING", "converged at iteration 2 across 15 runs",
                        33000, 0.04, 15, List.of("s1", "s2")))
                .status(ImprovementProposal.ProposalStatus.VALIDATED)
                .build();
    }
}
