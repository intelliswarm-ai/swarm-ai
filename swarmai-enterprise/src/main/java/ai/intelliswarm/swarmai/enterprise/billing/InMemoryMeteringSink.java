package ai.intelliswarm.swarmai.enterprise.billing;

import ai.intelliswarm.swarmai.spi.MeteringSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory implementation of {@link MeteringSink} for billing and usage tracking.
 * Records per-workflow and per-tenant usage metrics.
 *
 * <p>For production, replace with a database-backed or Stripe-integrated implementation.
 */
public class InMemoryMeteringSink implements MeteringSink {

    private static final Logger logger = LoggerFactory.getLogger(InMemoryMeteringSink.class);

    private final List<WorkflowUsageRecord> workflowRecords = Collections.synchronizedList(new ArrayList<>());
    private final List<TokenUsageRecord> tokenRecords = Collections.synchronizedList(new ArrayList<>());
    private final ConcurrentHashMap<String, TenantUsageSummary> tenantSummaries = new ConcurrentHashMap<>();

    @Override
    public void recordWorkflowUsage(WorkflowUsageRecord record) {
        workflowRecords.add(record);

        if (record.tenantId() != null) {
            tenantSummaries.computeIfAbsent(record.tenantId(), TenantUsageSummary::new)
                    .recordWorkflow(record);
        }

        logger.debug("Workflow usage recorded: wf={}, tenant={}, agents={}, success={}",
                record.workflowId(), record.tenantId(), record.agentCount(), record.success());
    }

    @Override
    public void recordTokenUsage(TokenUsageRecord record) {
        tokenRecords.add(record);

        if (record.tenantId() != null) {
            tenantSummaries.computeIfAbsent(record.tenantId(), TenantUsageSummary::new)
                    .recordTokens(record);
        }

        logger.debug("Token usage recorded: wf={}, model={}, tokens={}/{}",
                record.workflowId(), record.modelName(),
                record.promptTokens(), record.completionTokens());
    }

    /** Get all workflow usage records. */
    public List<WorkflowUsageRecord> getWorkflowRecords() {
        return List.copyOf(workflowRecords);
    }

    /** Get all token usage records. */
    public List<TokenUsageRecord> getTokenRecords() {
        return List.copyOf(tokenRecords);
    }

    /** Get usage summary for a specific tenant. */
    public Optional<TenantUsageSummary> getTenantSummary(String tenantId) {
        return Optional.ofNullable(tenantSummaries.get(tenantId));
    }

    /** Get all tenant summaries. */
    public Map<String, TenantUsageSummary> getAllTenantSummaries() {
        return Map.copyOf(tenantSummaries);
    }

    /**
     * Aggregated usage summary for a tenant.
     */
    public static class TenantUsageSummary {
        private final String tenantId;
        private final AtomicLong totalWorkflows = new AtomicLong(0);
        private final AtomicLong successfulWorkflows = new AtomicLong(0);
        private final AtomicLong totalPromptTokens = new AtomicLong(0);
        private final AtomicLong totalCompletionTokens = new AtomicLong(0);
        private volatile double totalCostUsd = 0.0;

        public TenantUsageSummary(String tenantId) {
            this.tenantId = tenantId;
        }

        void recordWorkflow(WorkflowUsageRecord record) {
            totalWorkflows.incrementAndGet();
            if (record.success()) successfulWorkflows.incrementAndGet();
        }

        synchronized void recordTokens(TokenUsageRecord record) {
            totalPromptTokens.addAndGet(record.promptTokens());
            totalCompletionTokens.addAndGet(record.completionTokens());
            totalCostUsd += record.estimatedCostUsd();
        }

        public String getTenantId() { return tenantId; }
        public long getTotalWorkflows() { return totalWorkflows.get(); }
        public long getSuccessfulWorkflows() { return successfulWorkflows.get(); }
        public long getTotalPromptTokens() { return totalPromptTokens.get(); }
        public long getTotalCompletionTokens() { return totalCompletionTokens.get(); }
        public long getTotalTokens() { return totalPromptTokens.get() + totalCompletionTokens.get(); }
        public double getTotalCostUsd() { return totalCostUsd; }
    }
}
