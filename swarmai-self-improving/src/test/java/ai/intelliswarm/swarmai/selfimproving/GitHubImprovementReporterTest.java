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
