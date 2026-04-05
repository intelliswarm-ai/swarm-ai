package ai.intelliswarm.swarmai.selfimproving.phase;

import ai.intelliswarm.swarmai.budget.BudgetTracker;
import ai.intelliswarm.swarmai.selfimproving.aggregator.ImprovementAggregator;
import ai.intelliswarm.swarmai.selfimproving.classifier.ImprovementClassifier;
import ai.intelliswarm.swarmai.selfimproving.collector.ImprovementCollector;
import ai.intelliswarm.swarmai.selfimproving.config.SelfImprovementConfig;
import ai.intelliswarm.swarmai.selfimproving.extractor.PatternExtractor;
import ai.intelliswarm.swarmai.selfimproving.model.*;
import ai.intelliswarm.swarmai.selfimproving.model.SpecificObservation.ObservationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * The main orchestrator for the 10% self-improvement budget.
 *
 * Called automatically after Phase 1 (workflow execution) completes.
 * Runs a priority-ordered pipeline that processes observations from the
 * execution trace and produces framework-level improvements.
 *
 * Priority pipeline (highest ROI first, stop when budget exhausted):
 *   1. Fix what broke         (30% of 10%) — prevents repeat failures
 *   2. Optimize expensive     (25% of 10%) — reduces future cost
 *   3. Promote what worked    (20% of 10%) — makes good patterns reusable
 *   4. Detect structural gaps (15% of 10%) — finds framework limitations
 *   5. Explore                (10% of 10%) — speculative improvement
 *
 * CRITICAL CONSTRAINT: Every improvement must be GENERIC — it must help
 * a stranger running a completely different workflow on a fresh install.
 * Domain-specific improvements are discarded.
 */
public class ImprovementPhase {

    private static final Logger log = LoggerFactory.getLogger(ImprovementPhase.class);

    private final SelfImprovementConfig config;
    private final ImprovementCollector collector;
    private final PatternExtractor extractor;
    private final ImprovementClassifier classifier;
    private final ImprovementAggregator aggregator;

    public ImprovementPhase(SelfImprovementConfig config,
                            ImprovementCollector collector,
                            PatternExtractor extractor,
                            ImprovementClassifier classifier,
                            ImprovementAggregator aggregator) {
        this.config = config;
        this.collector = collector;
        this.extractor = extractor;
        this.classifier = classifier;
        this.aggregator = aggregator;
    }

    /**
     * Execute the self-improvement phase using the reserved 10% budget.
     *
     * @param trace     the execution trace from Phase 1
     * @param tracker   budget tracker (to respect the 10% reserve)
     * @param swarmId   workflow identifier
     * @return result summarizing what was improved
     */
    public ImprovementResult execute(ExecutionTrace trace, BudgetTracker tracker, String swarmId) {
        log.info("[{}] Starting self-improvement phase ({}% reserve)",
                swarmId, config.getReservePercent() * 100);

        long budgetTokens = computeImprovementBudget(trace);
        long tokensUsed = 0;
        ImprovementResult.Builder result = ImprovementResult.builder().swarmId(swarmId);

        // Step 1: Collect observations from execution trace
        List<SpecificObservation> observations = collector.collect(trace);
        extractor.recordObservations(observations);
        result.totalObservations(observations.size());

        // Group observations by type for priority processing
        Map<ObservationType, List<SpecificObservation>> byType = observations.stream()
                .collect(Collectors.groupingBy(SpecificObservation::type));

        // Priority 1: Fix failures (30% of improvement budget)
        long p1Budget = (long) (budgetTokens * config.getPriority1FixFailuresPercent());
        tokensUsed += processFailures(byType.getOrDefault(ObservationType.FAILURE, List.of()),
                p1Budget, result);
        if (tokensUsed >= budgetTokens) return result.build();

        // Priority 2: Optimize expensive tasks (25%)
        long p2Budget = (long) (budgetTokens * config.getPriority2OptimizePercent());
        tokensUsed += processExpensiveTasks(byType.getOrDefault(ObservationType.EXPENSIVE_TASK, List.of()),
                p2Budget, result);
        if (tokensUsed >= budgetTokens) return result.build();

        // Priority 3: Promote successful patterns (20%)
        long p3Budget = (long) (budgetTokens * config.getPriority3PromotePercent());
        tokensUsed += processSuccessfulPatterns(observations, p3Budget, result);
        if (tokensUsed >= budgetTokens) return result.build();

        // Priority 4: Detect structural gaps (15%)
        long p4Budget = (long) (budgetTokens * config.getPriority4DetectGapsPercent());
        tokensUsed += processAntiPatterns(byType.getOrDefault(ObservationType.ANTI_PATTERN, List.of()),
                p4Budget, result);
        if (tokensUsed >= budgetTokens) return result.build();

        // Priority 5: Explore convergence and tool patterns (10%)
        long p5Budget = (long) (budgetTokens * config.getPriority5ExplorePercent());
        tokensUsed += processConvergencePatterns(
                byType.getOrDefault(ObservationType.CONVERGENCE_PATTERN, List.of()),
                p5Budget, result);
        processToolPatterns(
                byType.getOrDefault(ObservationType.TOOL_SELECTION, List.of()),
                p5Budget - tokensUsed, result);

        result.tokensUsed(tokensUsed);
        ImprovementResult finalResult = result.build();

        log.info("[{}] Self-improvement phase complete: {} observations, {} proposals, {} shipped (Tier 1), tokens used: {}/{}",
                swarmId, finalResult.totalObservations(), finalResult.totalProposals(),
                finalResult.tier1Shipped(), tokensUsed, budgetTokens);

        return finalResult;
    }

    private long processFailures(List<SpecificObservation> failures, long budget,
                                 ImprovementResult.Builder result) {
        if (failures.isEmpty()) return 0;

        GenericRule rule = extractor.extract(failures);
        if (rule != null) {
            ImprovementProposal proposal = classifier.classify(rule);
            aggregator.submit(proposal);
            result.addProposal(proposal);
        }

        // Estimate token cost of analysis
        return Math.min(budget, failures.size() * 200L);
    }

    private long processExpensiveTasks(List<SpecificObservation> expensive, long budget,
                                       ImprovementResult.Builder result) {
        if (expensive.isEmpty()) return 0;

        GenericRule rule = extractor.extract(expensive);
        if (rule != null) {
            ImprovementProposal proposal = classifier.classify(rule);
            aggregator.submit(proposal);
            result.addProposal(proposal);
        }

        return Math.min(budget, expensive.size() * 300L);
    }

    private long processSuccessfulPatterns(List<SpecificObservation> all, long budget,
                                           ImprovementResult.Builder result) {
        List<SpecificObservation> skills = all.stream()
                .filter(obs -> obs.type() == ObservationType.SUCCESSFUL_SKILL)
                .toList();

        if (skills.isEmpty()) return 0;

        // Each successful skill is its own potential promotion
        for (SpecificObservation skill : skills) {
            GenericRule rule = extractor.extract(List.of(skill));
            if (rule != null && rule.confidence() >= config.getTier2MinConfidence()) {
                ImprovementProposal proposal = classifier.classify(rule);
                aggregator.submit(proposal);
                result.addProposal(proposal);
            }
        }

        return Math.min(budget, skills.size() * 500L);
    }

    private long processAntiPatterns(List<SpecificObservation> antiPatterns, long budget,
                                     ImprovementResult.Builder result) {
        if (antiPatterns.isEmpty()) return 0;

        GenericRule rule = extractor.extract(antiPatterns);
        if (rule != null) {
            ImprovementProposal proposal = classifier.classify(rule);
            aggregator.submit(proposal);
            result.addProposal(proposal);
        }

        return Math.min(budget, antiPatterns.size() * 200L);
    }

    private long processConvergencePatterns(List<SpecificObservation> patterns, long budget,
                                            ImprovementResult.Builder result) {
        if (patterns.isEmpty()) return 0;

        GenericRule rule = extractor.extract(patterns);
        if (rule != null) {
            ImprovementProposal proposal = classifier.classify(rule);
            aggregator.submit(proposal);
            result.addProposal(proposal);
        }

        return Math.min(budget, patterns.size() * 200L);
    }

    private long processToolPatterns(List<SpecificObservation> toolObs, long budget,
                                     ImprovementResult.Builder result) {
        if (toolObs.isEmpty() || budget <= 0) return 0;

        GenericRule rule = extractor.extract(toolObs);
        if (rule != null) {
            ImprovementProposal proposal = classifier.classify(rule);
            aggregator.submit(proposal);
            result.addProposal(proposal);
        }

        return Math.min(budget, toolObs.size() * 150L);
    }

    private long computeImprovementBudget(ExecutionTrace trace) {
        long totalTokens = trace.totalTokens();
        long reserveTokens = (long) (totalTokens / (1.0 - config.getReservePercent()) * config.getReservePercent());
        return Math.max(reserveTokens, 1000); // minimum 1000 tokens for meaningful analysis
    }

    /**
     * Summary of what the improvement phase produced.
     */
    public record ImprovementResult(
            String swarmId,
            int totalObservations,
            int totalProposals,
            int tier1Shipped,
            int tier2Pending,
            int tier3Proposals,
            long tokensUsed,
            List<ImprovementProposal> proposals
    ) {
        public static Builder builder() { return new Builder(); }

        public static class Builder {
            private String swarmId;
            private int totalObservations;
            private long tokensUsed;
            private final List<ImprovementProposal> proposals = new ArrayList<>();

            public Builder swarmId(String id) { this.swarmId = id; return this; }
            public Builder totalObservations(int count) { this.totalObservations = count; return this; }
            public Builder tokensUsed(long tokens) { this.tokensUsed = tokens; return this; }
            public Builder addProposal(ImprovementProposal p) { proposals.add(p); return this; }

            public ImprovementResult build() {
                int t1 = (int) proposals.stream().filter(p -> p.tier() == ImprovementTier.TIER_1_AUTOMATIC).count();
                int t2 = (int) proposals.stream().filter(p -> p.tier() == ImprovementTier.TIER_2_REVIEW).count();
                int t3 = (int) proposals.stream().filter(p -> p.tier() == ImprovementTier.TIER_3_PROPOSAL).count();
                return new ImprovementResult(swarmId, totalObservations, proposals.size(),
                        t1, t2, t3, tokensUsed, List.copyOf(proposals));
            }
        }
    }
}
