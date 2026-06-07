package ai.intelliswarm.swarmai.llm.anthropic.files;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.time.Instant;
import java.util.Objects;

/**
 * Server-side metadata for a file stored in the Anthropic Files service.
 * Returned by {@link AnthropicFilesClient#upload}, {@link AnthropicFilesClient#list},
 * and {@link AnthropicFilesClient#metadata}.
 *
 * <p>Uploaded files live in the caller's Anthropic workspace and stay there
 * until {@link AnthropicFilesClient#delete} is invoked — useful for
 * (1) avoiding base64-PDF round-trips on large documents, and
 * (2) sharing the same document across many requests without re-uploading.
 */
@PublicApi(since = "1.0.24")
public record AnthropicFile(
        String id,
        String filename,
        String mimeType,
        long sizeBytes,
        Instant createdAt,
        boolean downloadable) {

    public AnthropicFile {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(filename, "filename must not be null");
        Objects.requireNonNull(mimeType, "mimeType must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must be non-negative");
        }
    }
}
