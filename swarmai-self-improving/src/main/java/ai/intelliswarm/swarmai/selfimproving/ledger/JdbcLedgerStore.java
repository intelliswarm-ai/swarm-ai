package ai.intelliswarm.swarmai.selfimproving.ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * JdbcTemplate-backed implementation of {@link LedgerStore}.
 *
 * <p>Upserts counters on every call. Portable SQL only: uses a SELECT + UPDATE/INSERT
 * fallback instead of MERGE/ON CONFLICT so it works across H2, Postgres, MySQL, and
 * SQLite without per-dialect code paths.
 */
public class JdbcLedgerStore implements LedgerStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcLedgerStore.class);

    private static final String GLOBAL_SCOPE = "global";
    private static final String INSTALLATION_SCOPE = "this";

    private final JdbcTemplate jdbc;

    public JdbcLedgerStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        initSchema();
    }

    /**
     * Auto-create ledger tables if they don't exist. This is needed when Flyway
     * is disabled (e.g. ollama profile with H2 where swarmai-core V1 migration
     * uses PostgreSQL-specific syntax). The DDL here mirrors V3 migration and
     * uses only portable SQL compatible with H2, PostgreSQL, MySQL, and SQLite.
     */
    private void initSchema() {
        try {
            jdbc.execute("CREATE TABLE IF NOT EXISTS community_investment_ledger (" +
                    "scope VARCHAR(64) PRIMARY KEY, " +
                    "total_workflow_runs BIGINT NOT NULL DEFAULT 0, " +
                    "total_tokens_invested BIGINT NOT NULL DEFAULT 0, " +
                    "total_observations_collected BIGINT NOT NULL DEFAULT 0, " +
                    "total_proposals_generated BIGINT NOT NULL DEFAULT 0, " +
                    "total_tier1_auto_eligible BIGINT NOT NULL DEFAULT 0, " +
                    "total_tier2_prs_filed BIGINT NOT NULL DEFAULT 0, " +
                    "total_tier3_proposals BIGINT NOT NULL DEFAULT 0, " +
                    "total_anti_patterns_discovered BIGINT NOT NULL DEFAULT 0, " +
                    "total_skills_promoted BIGINT NOT NULL DEFAULT 0, " +
                    "updated_at TIMESTAMP NOT NULL)");
            jdbc.execute("CREATE TABLE IF NOT EXISTS community_investment_daily (" +
                    "report_date DATE PRIMARY KEY, " +
                    "workflow_runs BIGINT NOT NULL DEFAULT 0, " +
                    "tokens_invested BIGINT NOT NULL DEFAULT 0, " +
                    "observations_collected BIGINT NOT NULL DEFAULT 0, " +
                    "proposals_generated BIGINT NOT NULL DEFAULT 0, " +
                    "tier1_auto_eligible BIGINT NOT NULL DEFAULT 0, " +
                    "tier2_prs_filed BIGINT NOT NULL DEFAULT 0, " +
                    "tier3_proposals BIGINT NOT NULL DEFAULT 0, " +
                    "anti_patterns_discovered BIGINT NOT NULL DEFAULT 0, " +
                    "skills_promoted BIGINT NOT NULL DEFAULT 0, " +
                    "reported_at TIMESTAMP NULL)");
            jdbc.execute("CREATE TABLE IF NOT EXISTS community_investment_daily_category (" +
                    "report_date DATE NOT NULL, " +
                    "category VARCHAR(64) NOT NULL, " +
                    "count BIGINT NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY (report_date, category))");
            jdbc.execute("CREATE TABLE IF NOT EXISTS self_improvement_installation (" +
                    "scope VARCHAR(64) PRIMARY KEY, " +
                    "installation_id VARCHAR(36) NOT NULL, " +
                    "created_at TIMESTAMP NOT NULL)");
            log.debug("Ledger schema verified/created");
        } catch (Exception e) {
            log.warn("Failed to auto-create ledger tables (non-fatal if Flyway manages them): {}", e.getMessage());
        }
    }

    @Override
    @Transactional
    public void recordRun(LedgerDelta delta, Map<String, Long> categories) {
        LocalDate today = LocalDate.now();
        Timestamp now = Timestamp.from(Instant.now());

        upsertGlobal(delta, now);
        upsertDaily(today, delta);

        if (categories != null && !categories.isEmpty()) {
            for (Map.Entry<String, Long> e : categories.entrySet()) {
                if (e.getValue() == null || e.getValue() == 0L) continue;
                upsertDailyCategory(today, e.getKey(), e.getValue());
            }
        }
    }

    private void upsertGlobal(LedgerDelta d, Timestamp now) {
        int updated = jdbc.update(
                "UPDATE community_investment_ledger SET " +
                        "total_workflow_runs = total_workflow_runs + ?, " +
                        "total_tokens_invested = total_tokens_invested + ?, " +
                        "total_observations_collected = total_observations_collected + ?, " +
                        "total_proposals_generated = total_proposals_generated + ?, " +
                        "total_tier1_auto_eligible = total_tier1_auto_eligible + ?, " +
                        "total_tier2_prs_filed = total_tier2_prs_filed + ?, " +
                        "total_tier3_proposals = total_tier3_proposals + ?, " +
                        "total_anti_patterns_discovered = total_anti_patterns_discovered + ?, " +
                        "total_skills_promoted = total_skills_promoted + ?, " +
                        "updated_at = ? " +
                        "WHERE scope = ?",
                d.workflowRuns(), d.tokensInvested(), d.observationsCollected(),
                d.proposalsGenerated(), d.tier1AutoEligible(), d.tier2PRsFiled(),
                d.tier3Proposals(), d.antiPatternsDiscovered(), d.skillsPromoted(),
                now, GLOBAL_SCOPE
        );
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO community_investment_ledger (scope, " +
                            "total_workflow_runs, total_tokens_invested, total_observations_collected, " +
                            "total_proposals_generated, total_tier1_auto_eligible, total_tier2_prs_filed, " +
                            "total_tier3_proposals, total_anti_patterns_discovered, total_skills_promoted, " +
                            "updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    GLOBAL_SCOPE,
                    d.workflowRuns(), d.tokensInvested(), d.observationsCollected(),
                    d.proposalsGenerated(), d.tier1AutoEligible(), d.tier2PRsFiled(),
                    d.tier3Proposals(), d.antiPatternsDiscovered(), d.skillsPromoted(),
                    now
            );
        }
    }

    private void upsertDaily(LocalDate date, LedgerDelta d) {
        int updated = jdbc.update(
                "UPDATE community_investment_daily SET " +
                        "workflow_runs = workflow_runs + ?, " +
                        "tokens_invested = tokens_invested + ?, " +
                        "observations_collected = observations_collected + ?, " +
                        "proposals_generated = proposals_generated + ?, " +
                        "tier1_auto_eligible = tier1_auto_eligible + ?, " +
                        "tier2_prs_filed = tier2_prs_filed + ?, " +
                        "tier3_proposals = tier3_proposals + ?, " +
                        "anti_patterns_discovered = anti_patterns_discovered + ?, " +
                        "skills_promoted = skills_promoted + ?, " +
                        "reported_at = NULL " +
                        "WHERE report_date = ?",
                d.workflowRuns(), d.tokensInvested(), d.observationsCollected(),
                d.proposalsGenerated(), d.tier1AutoEligible(), d.tier2PRsFiled(),
                d.tier3Proposals(), d.antiPatternsDiscovered(), d.skillsPromoted(),
                date
        );
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO community_investment_daily (report_date, " +
                            "workflow_runs, tokens_invested, observations_collected, " +
                            "proposals_generated, tier1_auto_eligible, tier2_prs_filed, " +
                            "tier3_proposals, anti_patterns_discovered, skills_promoted) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    date,
                    d.workflowRuns(), d.tokensInvested(), d.observationsCollected(),
                    d.proposalsGenerated(), d.tier1AutoEligible(), d.tier2PRsFiled(),
                    d.tier3Proposals(), d.antiPatternsDiscovered(), d.skillsPromoted()
            );
        }
    }

    private void upsertDailyCategory(LocalDate date, String category, long delta) {
        int updated = jdbc.update(
                "UPDATE community_investment_daily_category SET count = count + ? " +
                        "WHERE report_date = ? AND category = ?",
                delta, date, category
        );
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO community_investment_daily_category (report_date, category, count) " +
                            "VALUES (?, ?, ?)",
                    date, category, delta
            );
        }
    }

    @Override
    public Optional<DailyRollup> getDailyRollup(LocalDate date) {
        List<DailyRollup> rows = jdbc.query(
                "SELECT report_date, workflow_runs, tokens_invested, observations_collected, " +
                        "proposals_generated, tier1_auto_eligible, tier2_prs_filed, " +
                        "tier3_proposals, anti_patterns_discovered, skills_promoted, reported_at " +
                        "FROM community_investment_daily WHERE report_date = ?",
                (rs, rowNum) -> new DailyRollup(
                        rs.getDate("report_date").toLocalDate(),
                        rs.getLong("workflow_runs"),
                        rs.getLong("tokens_invested"),
                        rs.getLong("observations_collected"),
                        rs.getLong("proposals_generated"),
                        rs.getLong("tier1_auto_eligible"),
                        rs.getLong("tier2_prs_filed"),
                        rs.getLong("tier3_proposals"),
                        rs.getLong("anti_patterns_discovered"),
                        rs.getLong("skills_promoted"),
                        loadCategoriesFor(rs.getDate("report_date").toLocalDate()),
                        rs.getTimestamp("reported_at") != null
                ),
                date
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private Map<String, Long> loadCategoriesFor(LocalDate date) {
        Map<String, Long> out = new HashMap<>();
        jdbc.query(
                "SELECT category, count FROM community_investment_daily_category WHERE report_date = ?",
                rs -> { out.put(rs.getString("category"), rs.getLong("count")); },
                date
        );
        return out;
    }

    @Override
    @Transactional
    public void markDailyReported(LocalDate date) {
        jdbc.update(
                "UPDATE community_investment_daily SET reported_at = ? WHERE report_date = ?",
                Timestamp.from(Instant.now()), date
        );
    }

    @Override
    @Transactional
    public String getOrCreateInstallationId() {
        List<String> rows = jdbc.queryForList(
                "SELECT installation_id FROM self_improvement_installation WHERE scope = ?",
                String.class, INSTALLATION_SCOPE
        );
        if (!rows.isEmpty()) {
            return rows.get(0);
        }
        String id = UUID.randomUUID().toString();
        try {
            jdbc.update(
                    "INSERT INTO self_improvement_installation (scope, installation_id, created_at) VALUES (?, ?, ?)",
                    INSTALLATION_SCOPE, id, Timestamp.from(Instant.now())
            );
            log.info("Self-improvement installation ID generated: {} (persisted, stable across restarts)",
                    id.substring(0, 8) + "…");
            return id;
        } catch (Exception e) {
            // Race: another node inserted first. Re-read.
            List<String> retry = jdbc.queryForList(
                    "SELECT installation_id FROM self_improvement_installation WHERE scope = ?",
                    String.class, INSTALLATION_SCOPE
            );
            if (!retry.isEmpty()) return retry.get(0);
            throw e;
        }
    }

    @Override
    public GlobalSnapshot getGlobalSnapshot() {
        List<GlobalSnapshot> rows = jdbc.query(
                "SELECT total_workflow_runs, total_tokens_invested, total_observations_collected, " +
                        "total_proposals_generated, total_tier1_auto_eligible, total_tier2_prs_filed, " +
                        "total_tier3_proposals, total_anti_patterns_discovered, total_skills_promoted " +
                        "FROM community_investment_ledger WHERE scope = ?",
                (rs, rowNum) -> new GlobalSnapshot(
                        rs.getLong("total_workflow_runs"),
                        rs.getLong("total_tokens_invested"),
                        rs.getLong("total_observations_collected"),
                        rs.getLong("total_proposals_generated"),
                        rs.getLong("total_tier1_auto_eligible"),
                        rs.getLong("total_tier2_prs_filed"),
                        rs.getLong("total_tier3_proposals"),
                        rs.getLong("total_anti_patterns_discovered"),
                        rs.getLong("total_skills_promoted")
                ),
                GLOBAL_SCOPE
        );
        return rows.isEmpty() ? GlobalSnapshot.zero() : rows.get(0);
    }
}
