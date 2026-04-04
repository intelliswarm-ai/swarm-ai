package ai.intelliswarm.swarmai.spi;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.time.Instant;
import java.util.Map;

/**
 * SPI for persistent audit trail recording.
 * Enterprise implementations write to JDBC, Elasticsearch, or other durable stores.
 * Community edition provides a no-op default.
 */
@PublicApi(since = "1.0")
public interface AuditSink {

    void record(AuditEntry entry);

    record AuditEntry(
            String id,
            Instant timestamp,
            String tenantId,
            String userId,
            String action,
            String resource,
            String outcome,
            String correlationId,
            Map<String, Object> details
    ) {
        public AuditEntry {
            if (id == null || action == null) {
                throw new IllegalArgumentException("id and action are required");
            }
        }
    }

    /**
     * No-op implementation used when no enterprise audit sink is configured.
     */
    AuditSink NOOP = entry -> {};
}
