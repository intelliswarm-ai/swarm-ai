package ai.intelliswarm.swarmai.llm.anthropic.batch;

import ai.intelliswarm.swarmai.api.PublicApi;

/**
 * Per-status counts for a batch. The five buckets are mutually exclusive
 * once a request has reached its terminal state; while the batch is still
 * {@link BatchProcessingStatus#IN_PROGRESS}, {@link #processing()} is the
 * number that haven't yet been dispatched or are still running.
 */
@PublicApi(since = "1.0.24")
public record BatchRequestCounts(
        long processing,
        long succeeded,
        long errored,
        long canceled,
        long expired) {

    /** Sum of every bucket — equals the total request count submitted. */
    public long total() {
        return processing + succeeded + errored + canceled + expired;
    }

    /** True when no requests remain in the {@code processing} bucket. */
    public boolean allTerminal() {
        return processing == 0;
    }
}
