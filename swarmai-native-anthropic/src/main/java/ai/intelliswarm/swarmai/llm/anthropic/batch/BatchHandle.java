package ai.intelliswarm.swarmai.llm.anthropic.batch;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Server-side handle for a submitted Anthropic Message Batch. Callers poll
 * {@link AnthropicBatchClient#status(String)} on {@link #id()} until
 * {@link #processingStatus()} reports {@link BatchProcessingStatus#ENDED};
 * once ended, {@link AnthropicBatchClient#results(String)} returns the
 * per-item outcomes.
 *
 * <p>Batches always finalise by {@link #expiresAt()} (Anthropic guarantees
 * within 24h of submission as of 2026-Q1) — if the deadline passes, any
 * still-processing items move to the {@code expired} bucket. Callers using
 * batches for offline analytics should track {@link #expiresAt()} relative
 * to {@code Instant.now()} to decide whether to poll vs. resubmit.
 */
@PublicApi(since = "1.0.24")
public record BatchHandle(
        String id,
        BatchProcessingStatus processingStatus,
        BatchRequestCounts requestCounts,
        Instant createdAt,
        Optional<Instant> endedAt,
        Instant expiresAt,
        Optional<String> resultsUrl) {

    public BatchHandle {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(processingStatus, "processingStatus must not be null");
        Objects.requireNonNull(requestCounts, "requestCounts must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        Objects.requireNonNull(endedAt, "endedAt must not be null (use Optional.empty())");
        Objects.requireNonNull(resultsUrl, "resultsUrl must not be null (use Optional.empty())");
    }

    /** Convenience: results are downloadable. */
    public boolean isReady() {
        return processingStatus == BatchProcessingStatus.ENDED && resultsUrl.isPresent();
    }
}
