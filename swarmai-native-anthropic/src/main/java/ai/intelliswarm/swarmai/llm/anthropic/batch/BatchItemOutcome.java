package ai.intelliswarm.swarmai.llm.anthropic.batch;

import ai.intelliswarm.swarmai.api.PublicApi;
import ai.intelliswarm.swarmai.llm.anthropic.AnthropicResponse;

import java.util.Objects;

/**
 * Per-item terminal outcome inside a batch result stream. Sealed so callers
 * can {@code switch} exhaustively over the four possibilities Anthropic
 * documents:
 *
 * <ul>
 *   <li>{@link Succeeded} — the request completed; carries the parsed
 *       {@link AnthropicResponse}.</li>
 *   <li>{@link Errored} — the request failed (validation, model error,
 *       rate-limit retries exhausted). Carries the error code + message.</li>
 *   <li>{@link Canceled} — the batch was cancelled before this item ran.</li>
 *   <li>{@link Expired} — the batch's 24h deadline passed before this item
 *       was processed.</li>
 * </ul>
 */
@PublicApi(since = "1.0.24")
public sealed interface BatchItemOutcome
        permits BatchItemOutcome.Succeeded,
                BatchItemOutcome.Errored,
                BatchItemOutcome.Canceled,
                BatchItemOutcome.Expired {

    record Succeeded(AnthropicResponse response) implements BatchItemOutcome {
        public Succeeded {
            Objects.requireNonNull(response, "response must not be null");
        }
    }

    record Errored(String errorType, String message) implements BatchItemOutcome {
        public Errored {
            Objects.requireNonNull(errorType, "errorType must not be null");
            Objects.requireNonNull(message, "message must not be null");
        }
    }

    record Canceled() implements BatchItemOutcome {}

    record Expired() implements BatchItemOutcome {}
}
