-- SwarmAI Self-Improvement: durable Community Investment Ledger.
--
-- Replaces the in-memory AtomicLong counters in ImprovementAggregator
-- so totals survive JVM restarts and multiple nodes can aggregate into
-- a single view.
--
-- Three tables:
--   1. community_investment_ledger        — global running totals (one row, scope='global')
--   2. community_investment_daily          — per-day rollup (the source of truth for daily telemetry)
--   3. community_investment_daily_category — per-day, per-category counts (sparse)
--   4. self_improvement_installation       — stable anonymous UUID for this deployment
--
-- All four are written by LedgerStore; the daily telemetry scheduler reads from
-- (2) and (3) to build its rollup payload.

CREATE TABLE IF NOT EXISTS community_investment_ledger (
    scope VARCHAR(64) PRIMARY KEY,
    total_workflow_runs BIGINT NOT NULL DEFAULT 0,
    total_tokens_invested BIGINT NOT NULL DEFAULT 0,
    total_observations_collected BIGINT NOT NULL DEFAULT 0,
    total_proposals_generated BIGINT NOT NULL DEFAULT 0,
    total_tier1_auto_eligible BIGINT NOT NULL DEFAULT 0,
    total_tier2_prs_filed BIGINT NOT NULL DEFAULT 0,
    total_tier3_proposals BIGINT NOT NULL DEFAULT 0,
    total_anti_patterns_discovered BIGINT NOT NULL DEFAULT 0,
    total_skills_promoted BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS community_investment_daily (
    report_date DATE PRIMARY KEY,
    workflow_runs BIGINT NOT NULL DEFAULT 0,
    tokens_invested BIGINT NOT NULL DEFAULT 0,
    observations_collected BIGINT NOT NULL DEFAULT 0,
    proposals_generated BIGINT NOT NULL DEFAULT 0,
    tier1_auto_eligible BIGINT NOT NULL DEFAULT 0,
    tier2_prs_filed BIGINT NOT NULL DEFAULT 0,
    tier3_proposals BIGINT NOT NULL DEFAULT 0,
    anti_patterns_discovered BIGINT NOT NULL DEFAULT 0,
    skills_promoted BIGINT NOT NULL DEFAULT 0,
    reported_at TIMESTAMP NULL
);

CREATE TABLE IF NOT EXISTS community_investment_daily_category (
    report_date DATE NOT NULL,
    category VARCHAR(64) NOT NULL,
    count BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (report_date, category)
);

CREATE TABLE IF NOT EXISTS self_improvement_installation (
    scope VARCHAR(64) PRIMARY KEY,
    installation_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL
);
