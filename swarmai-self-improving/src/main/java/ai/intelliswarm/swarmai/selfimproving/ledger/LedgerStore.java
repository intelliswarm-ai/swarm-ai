package ai.intelliswarm.swarmai.selfimproving.ledger;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
     * Persist a raw observation so it survives JVM restarts.
     * Called by the self-improvement pipeline after each workflow execution.
     */
    void recordObservation(String swarmId, String observationType, String description, String evidenceJson);

    /**
     * Load recent persisted observations for cross-JVM pattern extraction.
     *
     * @param limit maximum number of observations to return (most recent first)
     */
    List<StoredObservation> getRecentObservations(int limit);

    /**
     * A persisted observation row, used to re-hydrate the PatternExtractor's
     * historical observation list across JVM boundaries.
     */
    record StoredObservation(
            String swarmId,
            String observationType,
            String description,
            String evidenceJson,
            Instant createdAt
    ) {}

    /**
     * Record a self-evolution event (swarm restructuring using existing capabilities).
     * Evolutions are persisted for cross-JVM learning and Studio visualization.
     */
    void recordEvolution(ai.intelliswarm.swarmai.selfimproving.model.SwarmEvolution evolution);

    /**
     * Get recent evolution events for Studio timeline visualization.
     */
    List<StoredEvolution> getRecentEvolutions(int limit);

    record StoredEvolution(String swarmId, String evolutionType, String reason,
                           String beforeJson, String afterJson, java.time.Instant createdAt) {}

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
