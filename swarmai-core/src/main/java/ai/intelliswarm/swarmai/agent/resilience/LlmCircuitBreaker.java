package ai.intelliswarm.swarmai.agent.resilience;

import ai.intelliswarm.swarmai.exception.AgentExecutionException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Wraps LLM API calls with circuit breaker and retry logic.
 * Uses resilience4j for circuit breaker and retry patterns.
 *
 * <p>Configuration:
 * <ul>
 *   <li>Circuit breaker opens after 50% failure rate over 10 calls</li>
 *   <li>Wait 30s in open state before half-open probing</li>
 *   <li>Retry up to 3 times with exponential backoff (1s, 2s, 4s)</li>
 *   <li>Non-retryable: 400, 401, 403, context_length_exceeded</li>
 * </ul>
 */
public class LlmCircuitBreaker {

    private static final Logger logger = LoggerFactory.getLogger(LlmCircuitBreaker.class);

    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public LlmCircuitBreaker() {
        this(new ResilienceConfig());
    }

    public LlmCircuitBreaker(ResilienceConfig config) {
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(config.failureRateThreshold)
                .slowCallDurationThreshold(Duration.ofMillis(config.slowCallThresholdMs))
                .slowCallRateThreshold(config.slowCallRateThreshold)
                .slidingWindowSize(config.slidingWindowSize)
                .waitDurationInOpenState(Duration.ofMillis(config.waitDurationOpenStateMs))
                .permittedNumberOfCallsInHalfOpenState(config.permittedCallsInHalfOpen)
                .ignoreExceptions(AgentExecutionException.class)
                .build();

        this.circuitBreaker = CircuitBreakerRegistry.of(cbConfig).circuitBreaker("llm");

        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(config.maxRetryAttempts)
                .intervalFunction(attempt ->
                        config.retryInitialIntervalMs * (long) Math.pow(config.retryMultiplier, attempt - 1))
                .retryOnException(e -> isRetryable(e))
                .build();

        this.retry = RetryRegistry.of(retryConfig).retry("llm");

        // Log state transitions
        circuitBreaker.getEventPublisher()
                .onStateTransition(event ->
                        logger.warn("[CIRCUIT-BREAKER] LLM circuit breaker state: {} -> {}",
                                event.getStateTransition().getFromState(),
                                event.getStateTransition().getToState()));

        retry.getEventPublisher()
                .onRetry(event ->
                        logger.warn("[RETRY] LLM call retry attempt {} after {}ms",
                                event.getNumberOfRetryAttempts(), event.getWaitInterval().toMillis()));
    }

    /**
     * Executes an LLM call with circuit breaker and retry protection.
     */
    public <T> T execute(Supplier<T> llmCall) {
        Supplier<T> decorated = CircuitBreaker.decorateSupplier(circuitBreaker,
                Retry.decorateSupplier(retry, llmCall));
        return decorated.get();
    }

    public CircuitBreaker.State getState() {
        return circuitBreaker.getState();
    }

    public CircuitBreaker.Metrics getMetrics() {
        return circuitBreaker.getMetrics();
    }

    private boolean isRetryable(Throwable e) {
        if (e instanceof TimeoutException) return true;
        String msg = e.getMessage() != null ? e.getMessage() : "";
        if (e.getCause() != null && e.getCause().getMessage() != null) {
            msg = msg + " " + e.getCause().getMessage();
        }
        // Non-retryable: client errors and context length
        // Use word-boundary-aware matching to avoid false positives like "port 4000"
        if (msg.contains("context_length_exceeded") || msg.contains("NonTransient")) {
            return false;
        }
        // Match HTTP status codes with word boundaries (space/punctuation before/after)
        if (msg.matches("(?s).*\\b400\\b.*") || msg.matches("(?s).*\\b401\\b.*") || msg.matches("(?s).*\\b403\\b.*")) {
            return false;
        }
        return true;
    }

    /**
     * Configuration for LLM resilience behavior.
     */
    public static class ResilienceConfig {
        public float failureRateThreshold = 50f;
        public long slowCallThresholdMs = 30_000;
        public float slowCallRateThreshold = 80f;
        public int slidingWindowSize = 10;
        public long waitDurationOpenStateMs = 30_000;
        public int permittedCallsInHalfOpen = 3;
        public int maxRetryAttempts = 3;
        public long retryInitialIntervalMs = 1000;
        public double retryMultiplier = 2.0;
    }
}
