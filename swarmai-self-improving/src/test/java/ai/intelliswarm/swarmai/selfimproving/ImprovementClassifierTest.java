package ai.intelliswarm.swarmai.selfimproving;

import ai.intelliswarm.swarmai.selfimproving.classifier.ImprovementClassifier;
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

class ImprovementClassifierTest {

    private ImprovementClassifier classifier;

    @BeforeEach
    void setUp() {
        SelfImprovementConfig config = new SelfImprovementConfig();
        config.setTier1MinConfidence(0.85);
        config.setTier2MinConfidence(0.70);
        classifier = new ImprovementClassifier(config);
    }

    @Test
    void shouldClassifyHighConfidenceDataChangeAsTier1() {
        GenericRule rule = buildRule(RuleCategory.CONVERGENCE_DEFAULT, 0.90, true);
        ImprovementProposal proposal = classifier.classify(rule);

        assertEquals(ImprovementTier.TIER_1_AUTOMATIC, proposal.tier());
        assertEquals(ImprovementProposal.ProposalStatus.VALIDATED, proposal.status());
    }

    @Test
    void shouldClassifyModerateConfidenceAsTier2() {
        GenericRule rule = buildRule(RuleCategory.ANTI_PATTERN, 0.75, true);
        ImprovementProposal proposal = classifier.classify(rule);

        assertEquals(ImprovementTier.TIER_2_REVIEW, proposal.tier());
        assertEquals(ImprovementProposal.ProposalStatus.PENDING, proposal.status());
    }

    @Test
    void shouldClassifyLowConfidenceAsTier3() {
        GenericRule rule = buildRule(RuleCategory.CONVERGENCE_DEFAULT, 0.50, true);
        ImprovementProposal proposal = classifier.classify(rule);

        assertEquals(ImprovementTier.TIER_3_PROPOSAL, proposal.tier());
    }

    @Test
    void shouldClassifyUnvalidatedRuleAsLowerTier() {
        GenericRule rule = buildRule(RuleCategory.CONVERGENCE_DEFAULT, 0.90, false);
        ImprovementProposal proposal = classifier.classify(rule);

        // Unvalidated rules should not be Tier 1 automatic
        assertNotEquals(ImprovementTier.TIER_1_AUTOMATIC, proposal.tier());
    }

    @Test
    void shouldMapCorrectTargetFiles() {
        GenericRule convergence = buildRule(RuleCategory.CONVERGENCE_DEFAULT, 0.90, true);
        ImprovementProposal proposal = classifier.classify(convergence);
        assertEquals("intelligence/convergence-defaults.json", proposal.improvement().targetFile());

        GenericRule toolRouting = buildRule(RuleCategory.TOOL_ROUTING, 0.90, true);
        proposal = classifier.classify(toolRouting);
        assertEquals("intelligence/tool-routing.json", proposal.improvement().targetFile());

        GenericRule antiPattern = buildRule(RuleCategory.ANTI_PATTERN, 0.90, true);
        proposal = classifier.classify(antiPattern);
        assertEquals("intelligence/anti-patterns.json", proposal.improvement().targetFile());
    }

    private GenericRule buildRule(RuleCategory category, double confidence, boolean validated) {
        WorkflowShape shape = new WorkflowShape(3, 2, false, false, false,
                Set.of("WEB"), "SEQUENTIAL", 2, 1.0, true, false);

        SpecificObservation obs = new SpecificObservation(
                "obs-1", SpecificObservation.ObservationType.CONVERGENCE_PATTERN,
                shape, "Test observation", Map.of(), 0.5, Instant.now()
        );

        ValidationResult validation = validated
                ? ValidationResult.passed(5, 4, List.of("Matched 4/5"))
                : ValidationResult.failed(5, 1, List.of("Only matched 1/5"));

        return new GenericRule(
                "rule-1", category,
                Map.of("max_depth", "<=3"),
                "Test recommendation",
                3,
                confidence,
                List.of(obs, obs, obs),
                validation,
                Instant.now(), Instant.now()
        );
    }
}
