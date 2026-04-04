package ai.intelliswarm.swarmai.selfimproving;

import ai.intelliswarm.swarmai.selfimproving.aggregator.ImprovementAggregator;
import ai.intelliswarm.swarmai.selfimproving.aggregator.ImprovementAggregator.CommunityInvestmentLedger;
import ai.intelliswarm.swarmai.selfimproving.config.SelfImprovementConfig;
import ai.intelliswarm.swarmai.selfimproving.model.*;
import ai.intelliswarm.swarmai.selfimproving.model.GenericRule.RuleCategory;
import ai.intelliswarm.swarmai.selfimproving.model.GenericRule.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CommunityInvestmentLedgerTest {

    private ImprovementAggregator aggregator;

    @BeforeEach
    void setUp() {
        SelfImprovementConfig config = new SelfImprovementConfig();
        aggregator = new ImprovementAggregator(config);
    }

    @Test
    void shouldTrackProposalSubmissions() {
        ImprovementProposal proposal = buildProposal(ImprovementTier.TIER_2_REVIEW, RuleCategory.ANTI_PATTERN);
        aggregator.submit(proposal);

        CommunityInvestmentLedger.Snapshot snapshot = aggregator.getCommunityInvestment();
        assertEquals(1, snapshot.totalProposalsGenerated());
    }

    @Test
    void shouldTrackAntiPatternDiscoveries() {
        ImprovementProposal proposal = buildProposal(ImprovementTier.TIER_2_REVIEW, RuleCategory.ANTI_PATTERN);
        aggregator.submit(proposal);

        CommunityInvestmentLedger.Snapshot snapshot = aggregator.getCommunityInvestment();
        assertEquals(1, snapshot.totalAntiPatternsDiscovered());
    }

    @Test
    void shouldAutoShipTier1Proposals() {
        ImprovementProposal proposal = buildReadyTier1Proposal();
        aggregator.submit(proposal);

        CommunityInvestmentLedger.Snapshot snapshot = aggregator.getCommunityInvestment();
        assertEquals(1, snapshot.totalImprovementsShipped());
    }

    @Test
    void shouldNotAutoShipTier2Proposals() {
        ImprovementProposal proposal = buildProposal(ImprovementTier.TIER_2_REVIEW, RuleCategory.ANTI_PATTERN);
        aggregator.submit(proposal);

        CommunityInvestmentLedger.Snapshot snapshot = aggregator.getCommunityInvestment();
        assertEquals(0, snapshot.totalImprovementsShipped());
    }

    @Test
    void shouldProduceWebsiteSummary() {
        ImprovementProposal proposal = buildReadyTier1Proposal();
        aggregator.submit(proposal);

        String summary = aggregator.getCommunityInvestment().toWebsiteSummary();
        assertNotNull(summary);
        assertTrue(summary.contains("Community Investment Report"));
        assertTrue(summary.contains("Improvements shipped: 1"));
    }

    @Test
    void shouldTrackImprovementsByCategory() {
        aggregator.submit(buildProposal(ImprovementTier.TIER_2_REVIEW, RuleCategory.ANTI_PATTERN));
        aggregator.submit(buildProposal(ImprovementTier.TIER_2_REVIEW, RuleCategory.CONVERGENCE_DEFAULT));
        aggregator.submit(buildProposal(ImprovementTier.TIER_2_REVIEW, RuleCategory.ANTI_PATTERN));

        CommunityInvestmentLedger.Snapshot snapshot = aggregator.getCommunityInvestment();
        assertEquals(2, snapshot.improvementsByCategory().get("ANTI_PATTERN"));
        assertEquals(1, snapshot.improvementsByCategory().get("CONVERGENCE_DEFAULT"));
    }

    private ImprovementProposal buildProposal(ImprovementTier tier, RuleCategory category) {
        WorkflowShape shape = new WorkflowShape(3, 2, false, false, false,
                Set.of("WEB"), "SEQUENTIAL", 2, 1.0, true, false);
        SpecificObservation obs = new SpecificObservation("obs-1",
                SpecificObservation.ObservationType.ANTI_PATTERN,
                shape, "Test", Map.of(), 0.5, Instant.now());
        GenericRule rule = new GenericRule("rule-1", category,
                Map.of("max_depth", "<=3"), "Test", 3, 0.75,
                List.of(obs, obs, obs),
                ValidationResult.passed(5, 4, List.of()),
                Instant.now(), Instant.now());

        return ImprovementProposal.builder()
                .tier(tier)
                .rule(rule)
                .improvement(new ImprovementProposal.Improvement(
                        "intelligence/test.json", "key", null, 3, "test", Map.of()))
                .origin(new ImprovementProposal.Origin(
                        "SEQUENTIAL", "test", 5000, 0.05, 3, List.of("s1")))
                .status(ImprovementProposal.ProposalStatus.PENDING)
                .build();
    }

    private ImprovementProposal buildReadyTier1Proposal() {
        WorkflowShape shape = new WorkflowShape(3, 2, false, false, false,
                Set.of("WEB"), "SEQUENTIAL", 2, 1.0, true, false);
        SpecificObservation obs = new SpecificObservation("obs-1",
                SpecificObservation.ObservationType.CONVERGENCE_PATTERN,
                shape, "Test", Map.of(), 0.5, Instant.now());
        GenericRule rule = new GenericRule("rule-1", RuleCategory.CONVERGENCE_DEFAULT,
                Map.of("max_depth", "<=3"), "Reduce maxIterations", 3, 0.90,
                List.of(obs, obs, obs),
                ValidationResult.passed(5, 4, List.of()),
                Instant.now(), Instant.now());

        return ImprovementProposal.builder()
                .tier(ImprovementTier.TIER_1_AUTOMATIC)
                .rule(rule)
                .improvement(new ImprovementProposal.Improvement(
                        "intelligence/convergence-defaults.json", "maxIterations", 5, 3,
                        "35% token reduction", Map.of()))
                .origin(new ImprovementProposal.Origin(
                        "SELF_IMPROVING", "converged at 2", 33000, 0.04, 15, List.of("s1", "s2")))
                .status(ImprovementProposal.ProposalStatus.VALIDATED)
                .build();
    }
}
