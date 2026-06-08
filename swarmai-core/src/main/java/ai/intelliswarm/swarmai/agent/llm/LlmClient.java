package ai.intelliswarm.swarmai.agent.llm;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.Set;

/**
 * Provider-neutral LLM client SPI. Wraps a single {@code send} call that
 * takes an {@link LlmRequest} and returns an {@link LlmResponse}.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@code SpringAiChatClientAdapter} (in {@code swarmai-core}) — wraps
 *       a Spring AI {@code ChatClient} for backward-compatible default
 *       behaviour. Most substrate fields (cache, thinking, citations) are
 *       silently ignored because Spring AI doesn't yet surface them.</li>
 *   <li>{@code AnthropicLlmClient} (in {@code swarmai-native-anthropic})
 *       — wraps the native Anthropic SDK and honours every substrate field.</li>
 * </ul>
 *
 * <p>Callers don't need to know which implementation is plugged in. They
 * build an {@link LlmRequest} with whichever Phase-1 features they want; the
 * adapter either honours or silently drops them.
 *
 * <p>{@link #supportedFeatures()} lets callers query at runtime which
 * features will actually be honoured — useful for the framework's compliance
 * gates ("if you set CitationsRequired and the adapter doesn't support
 * citations, fail loudly at configuration time").
 */
@PublicApi(since = "1.0.24")
public interface LlmClient {

    /**
     * Send a request synchronously. Returns the parsed response with text +
     * tool calls + citations + token usage as the adapter supports.
     *
     * @throws RuntimeException on network / API / parse failure — adapters
     *         are encouraged to wrap in their own typed exception so callers
     *         can extract request id / status etc.
     */
    LlmResponse send(LlmRequest request);

    /** Optional human-readable id (e.g. {@code "spring-ai"}, {@code "anthropic-native"}). */
    default String name() {
        return getClass().getSimpleName();
    }

    /** Which substrate features this adapter honours at runtime. */
    default Set<Feature> supportedFeatures() {
        return Set.of();
    }

    /**
     * Provider-feature flag. The framework's compliance gates use this to
     * fail fast if the active adapter can't honour a required behaviour.
     */
    @PublicApi(since = "1.0.24")
    enum Feature {
        /** {@link CachePolicy} breakpoints are translated to provider cache_control. */
        PROMPT_CACHING,
        /** {@link ThinkingBudget} is honoured. */
        EXTENDED_THINKING,
        /** {@link CitationConfig} + document blocks produce {@link InlineCitation} on output. */
        INLINE_CITATIONS,
        /** {@link DocumentBlock.Pdf} payloads are sent to the model. */
        PDF_DOCUMENTS,
        /** Vision (image) blocks in user messages. */
        VISION,
        /** Strict tool-use schema conformance. */
        STRICT_TOOL_USE,
        /** Streaming via {@code createStreaming}. */
        STREAMING,
        /** Cache hit/miss telemetry surfaced on {@link TokenUsage}. */
        CACHE_TELEMETRY,
        /**
         * Model prefers one-shot mode: receives full context as a single input string,
         * returns a complete diff/solution. Incompatible with iterative tool-call loops.
         * Clients like {@code OpenAiResponsesClient} declare this to trigger
         * {@code OneShotEngine} instead of {@code ConversationEngine}.
         */
        ONE_SHOT
    }
}
