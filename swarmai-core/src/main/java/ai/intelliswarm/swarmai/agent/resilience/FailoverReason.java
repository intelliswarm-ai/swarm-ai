package ai.intelliswarm.swarmai.agent.resilience;

import ai.intelliswarm.swarmai.api.PublicApi;

/**
 * Categorisation of an LLM-call failure into the action the framework should
 * take. Centralising this here means the retry loop, circuit breaker, and
 * provider fallback chain all read from one decision rather than re-deriving
 * it from raw exception types.
 *
 * <p>Every error classifies into exactly one outcome, and downstream code
 * reads the taxonomy rather than re-deriving intent from raw exception types.
 *
 * <ul>
 *   <li>{@link #RETRYABLE} — transient: retry with exponential backoff
 *       (5xx, network hiccup, 429 with Retry-After, SSL handshake glitch)</li>
 *   <li>{@link #SHOULD_COMPRESS} — context window exceeded: trigger
 *       compaction and retry the same request</li>
 *   <li>{@link #SHOULD_ROTATE_CREDENTIAL} — auth failure: rotate API key
 *       and retry (401/403, expired key, revoked token)</li>
 *   <li>{@link #SHOULD_FALLBACK} — provider unavailable: try next provider
 *       in the fallback chain (sustained 5xx, model_overloaded, region down)</li>
 *   <li>{@link #NOT_RECOVERABLE} — bad request: don't retry, surface to caller
 *       (400 with schema error, content-policy refusal, malformed tool definition)</li>
 * </ul>
 */
@PublicApi(since = "1.0.24")
public enum FailoverReason {
    RETRYABLE,
    SHOULD_COMPRESS,
    SHOULD_ROTATE_CREDENTIAL,
    SHOULD_FALLBACK,
    NOT_RECOVERABLE
}
