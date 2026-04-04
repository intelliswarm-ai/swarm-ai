package ai.intelliswarm.swarmai.enterprise.audit;

import ai.intelliswarm.swarmai.spi.AuditSink;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;

/**
 * JDBC-backed implementation of {@link AuditSink}.
 * Writes audit entries to the swarmai_audit_log table.
 *
 * <p>Schema managed by Flyway migration V2__add_audit_table.sql.
 */
public class JdbcAuditSink implements AuditSink {

    private static final Logger logger = LoggerFactory.getLogger(JdbcAuditSink.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String INSERT_SQL =
            "INSERT INTO swarmai_audit_log (tenant_id, user_id, action, resource, outcome, correlation_id, details, created_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)";

    private final JdbcTemplate jdbcTemplate;

    public JdbcAuditSink(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
        logger.info("JdbcAuditSink initialized");
    }

    @Override
    public void record(AuditEntry entry) {
        try {
            String detailsJson = null;
            if (entry.details() != null && !entry.details().isEmpty()) {
                try {
                    detailsJson = mapper.writeValueAsString(entry.details());
                } catch (JsonProcessingException e) {
                    logger.warn("Failed to serialize audit details: {}", e.getMessage());
                }
            }

            jdbcTemplate.update(INSERT_SQL,
                    entry.tenantId(),
                    entry.userId(),
                    entry.action(),
                    entry.resource(),
                    entry.outcome(),
                    entry.correlationId(),
                    detailsJson,
                    Timestamp.from(entry.timestamp() != null ? entry.timestamp() : Instant.now())
            );

            logger.debug("Audit recorded: action={}, resource={}, tenant={}",
                    entry.action(), entry.resource(), entry.tenantId());

        } catch (Exception e) {
            // Audit should never break the workflow
            logger.error("Failed to write audit entry: {}", e.getMessage());
        }
    }
}
