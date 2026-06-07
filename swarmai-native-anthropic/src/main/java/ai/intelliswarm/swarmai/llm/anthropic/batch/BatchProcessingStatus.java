package ai.intelliswarm.swarmai.llm.anthropic.batch;

import ai.intelliswarm.swarmai.api.PublicApi;

/**
 * Batch processing state as reported by Anthropic. Mirrors the SDK enum
 * but lives in our package so callers don't pull the SDK type into their
 * code.
 *
 * <p>State transitions:
 * <pre>
 *   IN_PROGRESS → ENDED          (normal completion, all per-item outcomes available)
 *   IN_PROGRESS → CANCELING      (cancel requested, items already running finish)
 *   CANCELING   → ENDED          (cancel finalised)
 * </pre>
 *
 * <p>Results are only available when {@link #ENDED}.
 */
@PublicApi(since = "1.0.24")
public enum BatchProcessingStatus {
    IN_PROGRESS,
    CANCELING,
    ENDED
}
