package ai.intelliswarm.swarmai.llm.anthropic.batch;

import ai.intelliswarm.swarmai.api.PublicApi;
import ai.intelliswarm.swarmai.llm.anthropic.AnthropicRequest;

import java.util.Objects;

/**
 * One input pair for a batch submission: a caller-chosen
 * {@link #customId()} plus the {@link AnthropicRequest} to dispatch.
 *
 * <p>{@code customId} is opaque to the framework — the caller picks any
 * unique value (often a domain identifier like {@code "FY25-10K-ACME"})
 * and uses it to match the corresponding {@link BatchResult} back to the
 * input. Anthropic requires customIds to be unique within a single batch.
 */
@PublicApi(since = "1.0.24")
public record BatchItem(String customId, AnthropicRequest request) {

    public BatchItem {
        Objects.requireNonNull(customId, "customId must not be null");
        Objects.requireNonNull(request, "request must not be null");
        if (customId.isBlank()) {
            throw new IllegalArgumentException("customId must not be blank");
        }
    }
}
