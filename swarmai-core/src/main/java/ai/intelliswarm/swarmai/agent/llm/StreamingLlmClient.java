package ai.intelliswarm.swarmai.agent.llm;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.function.Consumer;

/**
 * Optional extension to {@link LlmClient}: stream the assistant's text deltas
 * as they arrive. The final {@link LlmResponse} (including tool calls + usage)
 * is still returned synchronously so the agentic loop is unchanged.
 *
 * <p>Implementations that don't support real streaming should call
 * {@link #fallback(LlmClient, LlmRequest, Consumer)} which emits the full
 * text once via the callback after a regular {@code send()}.
 *
 * @since 1.0.26
 */
@PublicApi(since = "1.0.26")
public interface StreamingLlmClient extends LlmClient {

    /**
     * Send a request and stream assistant text deltas via {@code textDelta}.
     * Returns the assembled {@link LlmResponse} once the model finishes.
     */
    LlmResponse sendStreaming(LlmRequest request, Consumer<String> textDelta);

    /** Default fallback: call {@link #send} then emit all text in one chunk. */
    static LlmResponse fallback(LlmClient client, LlmRequest request, Consumer<String> textDelta) {
        LlmResponse resp = client.send(request);
        if (resp.text() != null && !resp.text().isEmpty()) {
            textDelta.accept(resp.text());
        }
        return resp;
    }
}
