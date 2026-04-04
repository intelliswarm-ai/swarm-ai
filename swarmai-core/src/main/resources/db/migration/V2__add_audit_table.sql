-- SwarmAI Audit Log Table
-- Used by enterprise JdbcAuditSink for persistent audit trail

CREATE TABLE IF NOT EXISTS swarmai_audit_log (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(255),
    user_id VARCHAR(255),
    action VARCHAR(255) NOT NULL,
    resource VARCHAR(500),
    outcome VARCHAR(50),
    correlation_id VARCHAR(255),
    details JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_swarmai_audit_tenant ON swarmai_audit_log(tenant_id);
CREATE INDEX IF NOT EXISTS idx_swarmai_audit_action ON swarmai_audit_log(action);
CREATE INDEX IF NOT EXISTS idx_swarmai_audit_created ON swarmai_audit_log(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_swarmai_audit_correlation ON swarmai_audit_log(correlation_id);
