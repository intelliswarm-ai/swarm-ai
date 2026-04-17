package ai.intelliswarm.swarmai.selfimproving.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A domain-agnostic improvement rule extracted from specific observations.
 * Generic rules reference structural workflow features (task_depth, tool_categories)
 * never domain terms (research, analysis, compliance).
 *
 * A rule must pass the genericity test:
 * "Would this help a stranger with a completely different workflow on a fresh install?"
 */
public record GenericRule(
        String ruleId,
        RuleCategory category,
        Map<String, Object> condition,
        String recommendation,
        Object recommendedValue,
        double confidence,
        List<SpecificObservation> supportingObservations,
        ValidationResult crossValidation,
        Instant createdAt,
        Instant lastValidatedAt
) {

    public enum RuleCategory {
        CONVERGENCE_DEFAULT,
        POLICY_WEIGHT,
        TOOL_ROUTING,
        PROMPT_OPTIMIZATION,
        SKILL_PROMOTION,
        ANTI_PATTERN,
        PROCESS_SELECTION,
        TOKEN_OPTIMIZATION,
        AGENT_CONFIGURATION,
        CONTEXT_HANDOFF
    }

    public boolean isReadyToShip() {
        return confidence >= 0.7
                && crossValidation != null
                && crossValidation.passed()
                && supportingObservations.size() >= 3;
    }

    public boolean isHighConfidence() {
        return confidence >= 0.85;
    }

    public ImprovementTier suggestedTier() {
        if (isHighConfidence() && isDataOnly()) return ImprovementTier.TIER_1_AUTOMATIC;
        if (isReadyToShip()) return ImprovementTier.TIER_2_REVIEW;
        return ImprovementTier.TIER_3_PROPOSAL;
    }

    private boolean isDataOnly() {
        return category == RuleCategory.CONVERGENCE_DEFAULT
                || category == RuleCategory.POLICY_WEIGHT
                || category == RuleCategory.TOOL_ROUTING
                || category == RuleCategory.PROCESS_SELECTION
                || category == RuleCategory.TOKEN_OPTIMIZATION;
    }

    public record ValidationResult(
            boolean passed,
            int testedAgainst,
            int matchedPositive,
            int matchedNegative,
            double crossValidationScore,
            List<String> validationDetails
    ) {
        public static ValidationResult passed(int tested, int positive, List<String> details) {
            double score = tested > 0 ? (double) positive / tested : 0;
            return new ValidationResult(score >= 0.6, tested, positive, tested - positive, score, details);
        }

        public static ValidationResult failed(int tested, int positive, List<String> details) {
            double score = tested > 0 ? (double) positive / tested : 0;
            return new ValidationResult(false, tested, positive, tested - positive, score, details);
        }
    }
}
