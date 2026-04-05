package ai.intelliswarm.swarmai.selfimproving.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A structured improvement proposal that carries both the generic improvement
 * (what ships in the framework) and the specific origin (why we believe it).
 *
 * Every proposal must answer:
 * 1. IS IT GENERIC? (no domain-specific terms in the condition)
 * 2. IS IT EVIDENCED? (>= 3 observations from >= 2 workflow types)
 * 3. IS IT SAFE? (test suite passes with this change)
 * 4. IS IT BENEFICIAL? (measurable improvement: tokens, quality, or failures)
 * 5. IS IT NON-REGRESSING? (no existing workflow gets worse)
 */
public record ImprovementProposal(
        String proposalId,
        ImprovementTier tier,
        GenericRule rule,
        Improvement improvement,
        Origin origin,
        ProposalStatus status,
        Instant createdAt,
        Instant lastUpdatedAt
) {

    public record Improvement(
            String targetFile,
            String key,
            Object currentValue,
            Object proposedValue,
            String expectedImpact,
            Map<String, Object> metadata
    ) {}

    public record Origin(
            String workflowType,
            String observation,
            long tokenSavings,
            double qualityDelta,
            int occurrenceCount,
            List<String> swarmIds
    ) {}

    public enum ProposalStatus {
        PENDING,
        VALIDATED,
        SHIPPED,
        REJECTED,
        STALE
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isReadyToShip() {
        return status == ProposalStatus.VALIDATED
                && rule.isReadyToShip()
                && improvement != null;
    }

    public boolean isStale(Instant cutoff) {
        return lastUpdatedAt.isBefore(cutoff);
    }

    public static class Builder {
        private String proposalId = java.util.UUID.randomUUID().toString();
        private ImprovementTier tier;
        private GenericRule rule;
        private Improvement improvement;
        private Origin origin;
        private ProposalStatus status = ProposalStatus.PENDING;
        private Instant createdAt = Instant.now();

        public Builder tier(ImprovementTier tier) { this.tier = tier; return this; }
        public Builder rule(GenericRule rule) { this.tier = rule.suggestedTier(); this.rule = rule; return this; }
        public Builder improvement(Improvement improvement) { this.improvement = improvement; return this; }
        public Builder origin(Origin origin) { this.origin = origin; return this; }
        public Builder status(ProposalStatus status) { this.status = status; return this; }

        public ImprovementProposal build() {
            if (tier == null && rule != null) tier = rule.suggestedTier();
            return new ImprovementProposal(proposalId, tier, rule, improvement, origin, status, createdAt, createdAt);
        }
    }
}
