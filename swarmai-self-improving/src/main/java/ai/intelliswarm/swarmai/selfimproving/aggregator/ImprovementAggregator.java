package ai.intelliswarm.swarmai.selfimproving.aggregator;

import ai.intelliswarm.swarmai.selfimproving.config.SelfImprovementConfig;
import ai.intelliswarm.swarmai.selfimproving.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Aggregates improvement proposals across workflow runs and produces
 * framework-level intelligence artifacts for the next release.
 *
 * Also maintains the Community Investment Ledger — a public-facing record
 * of what the collective 10% investment has produced, suitable for
 * display on the IntelliSwarm.ai website.
 */
public class ImprovementAggregator {

    private static final Logger log = LoggerFactory.getLogger(ImprovementAggregator.class);

    private final SelfImprovementConfig config;
    private final ObjectMapper objectMapper;

    // Pending proposals by tier
    private final List<ImprovementProposal> pendingProposals = new CopyOnWriteArrayList<>();

    // Community investment tracking
    private final CommunityInvestmentLedger ledger = new CommunityInvestmentLedger();

    public ImprovementAggregator(SelfImprovementConfig config) {
        this.config = config;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Submit a proposal from the ImprovementPhase.
     */
    public void submit(ImprovementProposal proposal) {
        pendingProposals.add(proposal);
        ledger.recordProposal(proposal);

        // Tier 1 proposals can be applied immediately
        if (proposal.tier() == ImprovementTier.TIER_1_AUTOMATIC && proposal.isReadyToShip()) {
            applyTier1(proposal);
        }

        log.debug("Submitted proposal: tier={}, category={}, status={}",
                proposal.tier(), proposal.rule().category(), proposal.status());
    }

    /**
     * Aggregate all pending proposals and produce release intelligence.
     * Called at release time or on demand.
     */
    public ReleaseIntelligence aggregateForRelease() {
        log.info("Aggregating {} proposals for release", pendingProposals.size());

        Map<ImprovementTier, List<ImprovementProposal>> byTier = pendingProposals.stream()
                .collect(Collectors.groupingBy(ImprovementProposal::tier));

        List<ImprovementProposal> tier1 = byTier.getOrDefault(ImprovementTier.TIER_1_AUTOMATIC, List.of())
                .stream().filter(ImprovementProposal::isReadyToShip).toList();
        List<ImprovementProposal> tier2 = byTier.getOrDefault(ImprovementTier.TIER_2_REVIEW, List.of());
        List<ImprovementProposal> tier3 = byTier.getOrDefault(ImprovementTier.TIER_3_PROPOSAL, List.of());

        // Mark stale proposals
        Instant cutoff = Instant.now().minus(java.time.Duration.ofDays(config.getStaleProposalDays()));
        long staleCount = pendingProposals.stream().filter(p -> p.isStale(cutoff)).count();

        ReleaseIntelligence intelligence = new ReleaseIntelligence(
                tier1, tier2, tier3,
                ledger.getSnapshot(),
                Instant.now()
        );

        log.info("Release intelligence: {} Tier 1 (auto), {} Tier 2 (review), {} Tier 3 (proposal), {} stale",
                tier1.size(), tier2.size(), tier3.size(), staleCount);

        return intelligence;
    }

    /**
     * Write intelligence artifacts to the resource directory.
     */
    public void writeIntelligenceArtifacts(Path outputDir) throws IOException {
        Files.createDirectories(outputDir);

        ReleaseIntelligence intelligence = aggregateForRelease();

        // Write convergence defaults from Tier 1 proposals
        List<Map<String, Object>> convergenceRules = intelligence.tier1Automatic().stream()
                .filter(p -> p.rule().category() == GenericRule.RuleCategory.CONVERGENCE_DEFAULT)
                .map(p -> Map.of(
                        "condition", (Object) p.rule().condition(),
                        "recommended_value", p.rule().recommendedValue() != null ? p.rule().recommendedValue() : "",
                        "confidence", p.rule().confidence()
                ))
                .toList();
        writeJson(outputDir.resolve("convergence-defaults.json"), convergenceRules);

        // Write tool routing hints
        List<Map<String, Object>> toolRouting = intelligence.tier1Automatic().stream()
                .filter(p -> p.rule().category() == GenericRule.RuleCategory.TOOL_ROUTING)
                .map(p -> Map.of(
                        "condition", (Object) p.rule().condition(),
                        "recommendation", p.rule().recommendation(),
                        "confidence", p.rule().confidence()
                ))
                .toList();
        writeJson(outputDir.resolve("tool-routing.json"), toolRouting);

        // Write anti-patterns
        List<Map<String, Object>> antiPatterns = pendingProposals.stream()
                .filter(p -> p.rule().category() == GenericRule.RuleCategory.ANTI_PATTERN)
                .filter(p -> p.rule().confidence() >= config.getTier2MinConfidence())
                .map(p -> Map.of(
                        "pattern", (Object) p.rule().ruleId(),
                        "description", p.rule().recommendation(),
                        "condition", p.rule().condition(),
                        "severity", p.rule().confidence() >= 0.9 ? "ERROR" : "WARNING",
                        "confidence", p.rule().confidence()
                ))
                .toList();
        writeJson(outputDir.resolve("anti-patterns.json"), antiPatterns);

        // Write community investment ledger (for website)
        writeJson(outputDir.resolve("community-investment-ledger.json"), ledger.getSnapshot());

        log.info("Wrote intelligence artifacts to {}", outputDir);
    }

    /**
     * Get the community investment ledger for display on the website.
     */
    public CommunityInvestmentLedger.Snapshot getCommunityInvestment() {
        return ledger.getSnapshot();
    }

    private void applyTier1(ImprovementProposal proposal) {
        ledger.recordShipped(proposal);
        log.info("Auto-applied Tier 1 improvement: {}", proposal.rule().recommendation());
    }

    private void writeJson(Path path, Object data) throws IOException {
        objectMapper.writeValue(path.toFile(), data);
    }

    /**
     * Release intelligence package — everything needed for the next version.
     */
    public record ReleaseIntelligence(
            List<ImprovementProposal> tier1Automatic,
            List<ImprovementProposal> tier2Review,
            List<ImprovementProposal> tier3Proposals,
            CommunityInvestmentLedger.Snapshot communityInvestment,
            Instant generatedAt
    ) {}

    /**
     * Tracks the aggregate impact of the community's 10% investment.
     * This data is designed to be displayed publicly on the IntelliSwarm.ai website
     * to show the value of collective improvement.
     */
    public static class CommunityInvestmentLedger {

        private final AtomicLong totalWorkflowRuns = new AtomicLong();
        private final AtomicLong totalTokensInvested = new AtomicLong();
        private final AtomicLong totalProposalsGenerated = new AtomicLong();
        private final AtomicLong totalImprovementsShipped = new AtomicLong();
        private final AtomicLong totalSkillsGraduated = new AtomicLong();
        private final AtomicLong totalAntiPatternsDiscovered = new AtomicLong();
        private final AtomicLong estimatedTokensSavedByImprovements = new AtomicLong();
        private final Map<String, AtomicLong> improvementsByCategory = new ConcurrentHashMap<>();
        private final List<MilestoneEntry> milestones = new CopyOnWriteArrayList<>();

        public void recordProposal(ImprovementProposal proposal) {
            totalProposalsGenerated.incrementAndGet();
            improvementsByCategory
                    .computeIfAbsent(proposal.rule().category().name(), k -> new AtomicLong())
                    .incrementAndGet();

            if (proposal.rule().category() == GenericRule.RuleCategory.ANTI_PATTERN) {
                totalAntiPatternsDiscovered.incrementAndGet();
            }
        }

        public void recordShipped(ImprovementProposal proposal) {
            totalImprovementsShipped.incrementAndGet();

            if (proposal.rule().category() == GenericRule.RuleCategory.SKILL_PROMOTION) {
                totalSkillsGraduated.incrementAndGet();
            }

            if (proposal.origin() != null) {
                estimatedTokensSavedByImprovements.addAndGet(proposal.origin().tokenSavings());
            }

            // Record milestone at significant thresholds
            long shipped = totalImprovementsShipped.get();
            if (shipped == 10 || shipped == 50 || shipped == 100 || shipped == 500 || shipped % 1000 == 0) {
                milestones.add(new MilestoneEntry(
                        Instant.now(),
                        "%d improvements shipped".formatted(shipped),
                        getSnapshot()
                ));
            }
        }

        public void recordWorkflowRun(long tokensInvested) {
            totalWorkflowRuns.incrementAndGet();
            totalTokensInvested.addAndGet(tokensInvested);
        }

        public Snapshot getSnapshot() {
            Map<String, Long> byCategory = new LinkedHashMap<>();
            improvementsByCategory.forEach((k, v) -> byCategory.put(k, v.get()));

            return new Snapshot(
                    totalWorkflowRuns.get(),
                    totalTokensInvested.get(),
                    totalProposalsGenerated.get(),
                    totalImprovementsShipped.get(),
                    totalSkillsGraduated.get(),
                    totalAntiPatternsDiscovered.get(),
                    estimatedTokensSavedByImprovements.get(),
                    computeROI(),
                    byCategory,
                    List.copyOf(milestones),
                    Instant.now()
            );
        }

        private double computeROI() {
            long invested = totalTokensInvested.get();
            long saved = estimatedTokensSavedByImprovements.get();
            return invested > 0 ? (double) saved / invested : 0.0;
        }

        /**
         * Public-facing snapshot of community investment impact.
         * Suitable for display on intelliswarm.ai website.
         */
        public record Snapshot(
                long totalWorkflowRuns,
                long totalTokensInvested,
                long totalProposalsGenerated,
                long totalImprovementsShipped,
                long totalSkillsGraduated,
                long totalAntiPatternsDiscovered,
                long estimatedTokensSaved,
                double roi,
                Map<String, Long> improvementsByCategory,
                List<MilestoneEntry> milestones,
                Instant snapshotTime
        ) {
            /**
             * Human-readable summary for the website.
             */
            public String toWebsiteSummary() {
                return """
                        Community Investment Report
                        ===========================
                        Workflow runs contributing: %,d
                        Tokens invested in improvement: %,d
                        Improvements shipped: %,d
                        Skills graduated to built-in: %,d
                        Anti-patterns discovered: %,d
                        Estimated tokens saved: %,d
                        Return on investment: %.1fx
                        """.formatted(
                        totalWorkflowRuns, totalTokensInvested,
                        totalImprovementsShipped, totalSkillsGraduated,
                        totalAntiPatternsDiscovered, estimatedTokensSaved, roi
                );
            }
        }

        public record MilestoneEntry(
                Instant timestamp,
                String description,
                Snapshot snapshotAtMilestone
        ) {}
    }
}
