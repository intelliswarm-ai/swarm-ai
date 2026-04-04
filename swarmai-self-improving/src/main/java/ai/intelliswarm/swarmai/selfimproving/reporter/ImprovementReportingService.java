package ai.intelliswarm.swarmai.selfimproving.reporter;

import ai.intelliswarm.swarmai.selfimproving.aggregator.ImprovementAggregator;
import ai.intelliswarm.swarmai.selfimproving.model.*;
import ai.intelliswarm.swarmai.selfimproving.phase.ImprovementPhase.ImprovementResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Orchestrates the full reporting pipeline:
 *
 *   ImprovementPhase output
 *     → Aggregate in ImprovementAggregator
 *     → Report to GitHub (PRs for Tier 1 + Tier 2)
 *     → Report telemetry to central endpoint (all tiers, anonymized)
 *     → Update Community Investment Ledger
 *
 * This is the bridge between individual workflow runs and the framework repository.
 * Enterprise deployments running independently all contribute back through this service.
 *
 * Two reporting channels:
 * 1. GitHub PRs — for deployments with repo access (open source contributors, CI/CD)
 * 2. Telemetry — for enterprise deployments (anonymized, no repo access needed)
 *
 * The central IntelliSwarm.ai endpoint aggregates telemetry from all deployments
 * and periodically creates GitHub PRs with the collective intelligence.
 */
public class ImprovementReportingService {

    private static final Logger log = LoggerFactory.getLogger(ImprovementReportingService.class);

    private final ImprovementAggregator aggregator;
    private final GitHubImprovementReporter githubReporter;
    private final TelemetryReporter telemetryReporter;

    // Buffer proposals for batch PR creation
    private final List<ImprovementProposal> pendingForGitHub = new CopyOnWriteArrayList<>();
    private static final int BATCH_THRESHOLD = 5;

    public ImprovementReportingService(ImprovementAggregator aggregator,
                                        GitHubImprovementReporter githubReporter,
                                        TelemetryReporter telemetryReporter) {
        this.aggregator = aggregator;
        this.githubReporter = githubReporter;
        this.telemetryReporter = telemetryReporter;
    }

    /**
     * Process and report an improvement result from a workflow run.
     * Called automatically after ImprovementPhase.execute() completes.
     */
    public ReportingOutcome reportImprovements(ImprovementResult result) {
        log.info("[{}] Reporting {} proposals ({} Tier 1, {} Tier 2, {} Tier 3)",
                result.swarmId(), result.totalProposals(),
                result.tier1Shipped(), result.tier2Pending(), result.tier3Proposals());

        ReportingOutcome.Builder outcome = ReportingOutcome.builder()
                .swarmId(result.swarmId());

        // Step 1: Always send anonymized telemetry (non-blocking)
        if (telemetryReporter != null) {
            telemetryReporter.report(result);
            outcome.telemetrySent(true);
        }

        // Step 2: Update the community investment ledger
        aggregator.getCommunityInvestment(); // triggers ledger snapshot

        // Step 3: Route proposals to GitHub
        if (githubReporter != null) {
            for (ImprovementProposal proposal : result.proposals()) {
                if (shouldReportToGitHub(proposal)) {
                    pendingForGitHub.add(proposal);
                }
            }

            // Batch PRs when threshold is reached
            if (pendingForGitHub.size() >= BATCH_THRESHOLD) {
                flushToGitHub(outcome);
            }
            // Or create individual PRs for high-confidence Tier 1
            else {
                for (ImprovementProposal p : result.proposals()) {
                    if (p.tier() == ImprovementTier.TIER_1_AUTOMATIC && p.isReadyToShip()) {
                        Optional<String> prUrl = githubReporter.report(p);
                        prUrl.ifPresent(outcome::addPrUrl);
                    }
                }
            }
        }

        ReportingOutcome finalOutcome = outcome.build();
        log.info("[{}] Reporting complete: telemetry={}, PRs created={}",
                result.swarmId(), finalOutcome.telemetrySent(), finalOutcome.prUrls().size());

        return finalOutcome;
    }

    /**
     * Flush all pending proposals to GitHub as a single batch PR.
     * Can be called manually or triggered when batch threshold is reached.
     */
    public Optional<String> flushToGitHub() {
        return flushToGitHub(null);
    }

    private Optional<String> flushToGitHub(ReportingOutcome.Builder outcome) {
        if (githubReporter == null || pendingForGitHub.isEmpty()) {
            return Optional.empty();
        }

        List<ImprovementProposal> batch = new ArrayList<>(pendingForGitHub);
        pendingForGitHub.clear();

        Optional<String> prUrl = githubReporter.reportBatch(batch);
        if (outcome != null) {
            prUrl.ifPresent(outcome::addPrUrl);
        }
        return prUrl;
    }

    /**
     * Get the current community investment metrics for display.
     */
    public ImprovementAggregator.CommunityInvestmentLedger.Snapshot getCommunityMetrics() {
        return aggregator.getCommunityInvestment();
    }

    /**
     * Get the number of proposals pending for the next GitHub batch.
     */
    public int getPendingProposalCount() {
        return pendingForGitHub.size();
    }

    private boolean shouldReportToGitHub(ImprovementProposal proposal) {
        // Only Tier 1 and Tier 2 produce GitHub PRs
        // Tier 3 (architecture proposals) are stored locally for human review
        return proposal.tier() == ImprovementTier.TIER_1_AUTOMATIC
                || proposal.tier() == ImprovementTier.TIER_2_REVIEW;
    }

    /**
     * Outcome of the reporting pipeline for a single workflow run.
     */
    public record ReportingOutcome(
            String swarmId,
            boolean telemetrySent,
            List<String> prUrls,
            int proposalsBuffered
    ) {
        public static Builder builder() { return new Builder(); }

        public static class Builder {
            private String swarmId;
            private boolean telemetrySent;
            private final List<String> prUrls = new ArrayList<>();
            private int proposalsBuffered;

            public Builder swarmId(String id) { this.swarmId = id; return this; }
            public Builder telemetrySent(boolean sent) { this.telemetrySent = sent; return this; }
            public Builder addPrUrl(String url) { prUrls.add(url); return this; }
            public Builder proposalsBuffered(int count) { this.proposalsBuffered = count; return this; }

            public ReportingOutcome build() {
                return new ReportingOutcome(swarmId, telemetrySent, List.copyOf(prUrls), proposalsBuffered);
            }
        }
    }
}
