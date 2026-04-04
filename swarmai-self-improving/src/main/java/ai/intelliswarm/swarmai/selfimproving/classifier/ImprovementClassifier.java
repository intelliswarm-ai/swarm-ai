package ai.intelliswarm.swarmai.selfimproving.classifier;

import ai.intelliswarm.swarmai.selfimproving.config.SelfImprovementConfig;
import ai.intelliswarm.swarmai.selfimproving.model.*;
import ai.intelliswarm.swarmai.selfimproving.model.GenericRule.RuleCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Routes a GenericRule to the appropriate ImprovementTier based on
 * confidence, change type, and safety characteristics.
 *
 * Tier 1 (Automatic): Data-only changes validated by test suite
 * Tier 2 (Review):    Safe changes that need human eyeball
 * Tier 3 (Proposal):  Structural changes requiring human design
 */
public class ImprovementClassifier {

    private static final Logger log = LoggerFactory.getLogger(ImprovementClassifier.class);

    private final SelfImprovementConfig config;

    public ImprovementClassifier(SelfImprovementConfig config) {
        this.config = config;
    }

    /**
     * Classify a rule into an improvement proposal with the appropriate tier.
     */
    public ImprovementProposal classify(GenericRule rule) {
        ImprovementTier tier = determineTier(rule);

        ImprovementProposal.Improvement improvement = buildImprovement(rule);
        ImprovementProposal.Origin origin = buildOrigin(rule);

        ImprovementProposal proposal = ImprovementProposal.builder()
                .tier(tier)
                .rule(rule)
                .improvement(improvement)
                .origin(origin)
                .status(tier == ImprovementTier.TIER_1_AUTOMATIC
                        ? ImprovementProposal.ProposalStatus.VALIDATED
                        : ImprovementProposal.ProposalStatus.PENDING)
                .build();

        log.info("Classified improvement: tier={}, category={}, confidence={:.2f}, target={}",
                tier, rule.category(), rule.confidence(),
                improvement != null ? improvement.targetFile() : "none");

        return proposal;
    }

    private ImprovementTier determineTier(GenericRule rule) {
        // Tier 1: High confidence + data-only + cross-validated
        if (rule.confidence() >= config.getTier1MinConfidence()
                && isDataOnlyCategory(rule.category())
                && rule.crossValidation() != null
                && rule.crossValidation().passed()) {
            return ImprovementTier.TIER_1_AUTOMATIC;
        }

        // Tier 2: Moderate confidence + safe change type
        if (rule.confidence() >= config.getTier2MinConfidence()
                && isSafeChangeCategory(rule.category())
                && rule.crossValidation() != null
                && rule.crossValidation().passed()) {
            return ImprovementTier.TIER_2_REVIEW;
        }

        // Tier 3: Everything else
        return ImprovementTier.TIER_3_PROPOSAL;
    }

    private boolean isDataOnlyCategory(RuleCategory category) {
        return category == RuleCategory.CONVERGENCE_DEFAULT
                || category == RuleCategory.POLICY_WEIGHT
                || category == RuleCategory.TOOL_ROUTING
                || category == RuleCategory.PROCESS_SELECTION;
    }

    private boolean isSafeChangeCategory(RuleCategory category) {
        return isDataOnlyCategory(category)
                || category == RuleCategory.ANTI_PATTERN
                || category == RuleCategory.SKILL_PROMOTION
                || category == RuleCategory.PROMPT_OPTIMIZATION;
    }

    private ImprovementProposal.Improvement buildImprovement(GenericRule rule) {
        String targetFile = mapTargetFile(rule.category());
        if (targetFile == null) return null;

        return new ImprovementProposal.Improvement(
                targetFile,
                rule.ruleId(),
                null, // current value resolved at application time
                rule.recommendedValue(),
                rule.recommendation(),
                Map.of(
                        "condition", rule.condition(),
                        "confidence", rule.confidence(),
                        "supporting_observations", rule.supportingObservations().size()
                )
        );
    }

    private String mapTargetFile(RuleCategory category) {
        return switch (category) {
            case CONVERGENCE_DEFAULT -> "intelligence/convergence-defaults.json";
            case POLICY_WEIGHT -> "intelligence/policy-weights.json";
            case TOOL_ROUTING -> "intelligence/tool-routing.json";
            case PROCESS_SELECTION -> "intelligence/process-heuristics.json";
            case ANTI_PATTERN -> "intelligence/anti-patterns.json";
            case SKILL_PROMOTION -> "intelligence/skills/";
            case PROMPT_OPTIMIZATION -> "intelligence/prompt-templates/";
        };
    }

    private ImprovementProposal.Origin buildOrigin(GenericRule rule) {
        if (rule.supportingObservations().isEmpty()) return null;

        var first = rule.supportingObservations().get(0);
        long tokenSavings = rule.supportingObservations().stream()
                .map(obs -> obs.evidence().getOrDefault("tokens_spent", 0))
                .mapToLong(v -> v instanceof Number n ? n.longValue() : 0)
                .sum();

        return new ImprovementProposal.Origin(
                first.workflowShape().processType(),
                first.description(),
                tokenSavings,
                0.0,
                rule.supportingObservations().size(),
                rule.supportingObservations().stream()
                        .map(obs -> obs.observationId())
                        .toList()
        );
    }
}
