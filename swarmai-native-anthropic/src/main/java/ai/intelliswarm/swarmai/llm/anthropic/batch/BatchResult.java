package ai.intelliswarm.swarmai.llm.anthropic.batch;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.Objects;

/**
 * One result row from a batch: the input {@code customId} plus the terminal
 * {@link BatchItemOutcome}. Callers use {@code customId} to join the result
 * back to the originating {@link BatchItem}.
 */
@PublicApi(since = "1.0.24")
public record BatchResult(String customId, BatchItemOutcome outcome) {

    public BatchResult {
        Objects.requireNonNull(customId, "customId must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
    }
}
