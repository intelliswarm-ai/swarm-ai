package ai.intelliswarm.swarmai.spi;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.time.Instant;

/**
 * SPI for billing and metering hooks.
 * Enterprise implementations record usage for billing systems (Stripe, custom).
 * Community edition provides a no-op default.
 */
@PublicApi(since = "1.0")
public interface MeteringSink {

    void recordWorkflowUsage(WorkflowUsageRecord record);

    void recordTokenUsage(TokenUsageRecord record);

    record WorkflowUsageRecord(
            String workflowId,
            String tenantId,
            String processType,
            Instant startedAt,
            Instant completedAt,
            int agentCount,
            int taskCount,
            boolean success
    ) {}

    record TokenUsageRecord(
            String workflowId,
            String tenantId,
            String modelName,
            long promptTokens,
            long completionTokens,
            double estimatedCostUsd
    ) {}

    /**
     * No-op implementation used when no enterprise metering sink is configured.
     */
    MeteringSink NOOP = new MeteringSink() {
        @Override
        public void recordWorkflowUsage(WorkflowUsageRecord record) {}

        @Override
        public void recordTokenUsage(TokenUsageRecord record) {}
    };
}
