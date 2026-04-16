package ai.intelliswarm.swarmai.selfimproving.ledger;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * Durable community-investment counter store.
 *
 * <p>Single source of truth for the totals and the per-day rollups that feed
 * the daily telemetry scheduler. All increments are upserts against two rows:
 * the global {@code scope='global'} running-total row, and the per-date row
 * in {@code community_investment_daily}.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@link JdbcLedgerStore} — JDBC-backed, active when a {@code DataSource} bean exists.</li>
 *   <li>{@link NoOpLedgerStore} — fallback when there is no database; lets the framework
 *       run cleanly in embedded/example/test scenarios.</li>
 * </ul>
 */
public interface LedgerStore {

    /**
     * Record a self-improvement pass for today.
     *
     * @param delta  per-pass deltas to add to both the global row and today's row
     * @param categories sparse category counters for today (e.g. CONVERGENCE_DEFAULT → 2)
     */
    void recordRun(LedgerDelta delta, Map<String, Long> categories);

    /**
     * Get the per-day rollup for a specific date. Empty if the deployment
     * had no activity that day.
     */
    Optional<DailyRollup> getDailyRollup(LocalDate date);

    /**
     * Mark a daily rollup as successfully reported to the central endpoint.
     * Idempotent — setting the same date twice is harmless.
     */
    void markDailyReported(LocalDate date);

    /**
     * Stable anonymous installation ID. Generated on first access and
     * persisted. Never traces back to a user or organization.
     */
    String getOrCreateInstallationId();

    /**
     * Running totals across all time, intended for the public /ledger surface
     * and the self-improvement health indicator.
     */
    GlobalSnapshot getGlobalSnapshot();

    /**
     * Per-pass increments. Mirrors the website's telemetry input schema.
     */
    record LedgerDelta(
            long workflowRuns,
            long tokensInvested,
            long observationsCollected,
            long proposalsGenerated,
            long tier1AutoEligible,
            long tier2PRsFiled,
            long tier3Proposals,
            long antiPatternsDiscovered,
            long skillsPromoted
    ) {
        public static LedgerDelta zero() {
            return new LedgerDelta(0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    /**
     * Per-day rollup matching the website's /api/v1/self-improving/telemetry schema.
     */
    record DailyRollup(
            LocalDate reportDate,
            long workflowRuns,
            long tokensInvested,
            long observationsCollected,
            long proposalsGenerated,
            long tier1AutoEligible,
            long tier2PRsFiled,
            long tier3Proposals,
            long antiPatternsDiscovered,
            long skillsPromoted,
            Map<String, Long> categories,
            boolean reported
    ) { }

    /**
     * Global running totals.
     */
    record GlobalSnapshot(
            long totalWorkflowRuns,
            long totalTokensInvested,
            long totalObservationsCollected,
            long totalProposalsGenerated,
            long totalTier1AutoEligible,
            long totalTier2PRsFiled,
            long totalTier3Proposals,
            long totalAntiPatternsDiscovered,
            long totalSkillsPromoted
    ) {
        public static GlobalSnapshot zero() {
            return new GlobalSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }
}
