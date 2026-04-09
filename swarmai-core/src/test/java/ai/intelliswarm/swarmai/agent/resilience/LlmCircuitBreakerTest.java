package ai.intelliswarm.swarmai.agent.resilience;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for LlmCircuitBreaker — the resilience layer for LLM API calls.
 *
 * If this doesn't work, a failing LLM API causes cascading failures across
 * all agents in a workflow. The circuit breaker must:
 * 1. Retry transient failures with backoff
 * 2. Stop retrying non-transient errors (400, 401, 403)
 * 3. Open the circuit after sustained failures
 * 4. Reject calls while circuit is open
 * 5. Probe with half-open state before closing
 */
@DisplayName("LlmCircuitBreaker — LLM API Resilience")
class LlmCircuitBreakerTest {

    // ================================================================
    // RETRY BEHAVIOR
    // ================================================================

    @Nested
    @DisplayName("Retry Logic")
    class RetryLogic {

        @Test
        @DisplayName("retries transient failure and succeeds on second attempt")
        void retriesTransientFailure() {
            LlmCircuitBreaker breaker = createFastBreaker();
            AtomicInteger attempts = new AtomicInteger(0);

            String result = breaker.execute(() -> {
                int attempt = attempts.incrementAndGet();
                if (attempt == 1) {
                    throw new RuntimeException("Temporary server error 500");
                }
                return "Success on attempt " + attempt;
            });

            assertEquals("Success on attempt 2", result);
            assertTrue(attempts.get() >= 2,
                "Should retry at least once. Attempts: " + attempts.get());
        }

        @Test
        @DisplayName("retries TimeoutException")
        void retriesTimeout() {
            LlmCircuitBreaker breaker = createFastBreaker();
            AtomicInteger attempts = new AtomicInteger(0);

            String result = breaker.execute(() -> {
                int attempt = attempts.incrementAndGet();
                if (attempt == 1) {
                    throw new RuntimeException(new TimeoutException("LLM call timed out"));
                }
                return "Success";
            });

            assertEquals("Success", result);
            assertTrue(attempts.get() >= 2, "Should retry timeout");
        }

        @Test
        @DisplayName("does NOT retry 400 Bad Request (client error)")
        void doesNotRetry400() {
            LlmCircuitBreaker breaker = createFastBreaker();
            AtomicInteger attempts = new AtomicInteger(0);

            assertThrows(Exception.class, () -> breaker.execute(() -> {
                attempts.incrementAndGet();
                throw new RuntimeException("400 Bad Request: invalid prompt");
            }));

            assertEquals(1, attempts.get(),
                "400 is non-retryable — should fail immediately without retry");
        }

        @Test
        @DisplayName("does NOT retry 401 Unauthorized")
        void doesNotRetry401() {
            LlmCircuitBreaker breaker = createFastBreaker();
            AtomicInteger attempts = new AtomicInteger(0);

            assertThrows(Exception.class, () -> breaker.execute(() -> {
                attempts.incrementAndGet();
                throw new RuntimeException("401 Unauthorized: invalid API key");
            }));

            assertEquals(1, attempts.get(),
                "401 is non-retryable — bad API key won't fix itself on retry");
        }

        @Test
        @DisplayName("does NOT retry 403 Forbidden")
        void doesNotRetry403() {
            LlmCircuitBreaker breaker = createFastBreaker();
            AtomicInteger attempts = new AtomicInteger(0);

            assertThrows(Exception.class, () -> breaker.execute(() -> {
                attempts.incrementAndGet();
                throw new RuntimeException("403 Forbidden: insufficient permissions");
            }));

            assertEquals(1, attempts.get(),
                "403 is non-retryable — permissions won't change on retry");
        }

        @Test
        @DisplayName("does NOT retry context_length_exceeded")
        void doesNotRetryContextLength() {
            LlmCircuitBreaker breaker = createFastBreaker();
            AtomicInteger attempts = new AtomicInteger(0);

            assertThrows(Exception.class, () -> breaker.execute(() -> {
                attempts.incrementAndGet();
                throw new RuntimeException("context_length_exceeded: input too long");
            }));

            assertEquals(1, attempts.get(),
                "context_length_exceeded is non-retryable — input must be reduced");
        }

        @Test
        @DisplayName("gives up after max retry attempts")
        void givesUpAfterMaxRetries() {
            LlmCircuitBreaker.ResilienceConfig config = new LlmCircuitBreaker.ResilienceConfig();
            config.maxRetryAttempts = 3;
            config.retryInitialIntervalMs = 10; // fast for testing
            config.slidingWindowSize = 100; // don't trip circuit breaker
            LlmCircuitBreaker breaker = new LlmCircuitBreaker(config);

            AtomicInteger attempts = new AtomicInteger(0);

            assertThrows(Exception.class, () -> breaker.execute(() -> {
                attempts.incrementAndGet();
                throw new RuntimeException("Server error 502");
            }));

            assertEquals(3, attempts.get(),
                "Should attempt exactly maxRetryAttempts=" + config.maxRetryAttempts);
        }
    }

    // ================================================================
    // CIRCUIT BREAKER STATE TRANSITIONS
    // ================================================================

    @Nested
    @DisplayName("Circuit Breaker State Machine")
    class CircuitBreakerState {

        @Test
        @DisplayName("starts in CLOSED state")
        void startsClosedState() {
            LlmCircuitBreaker breaker = createFastBreaker();
            assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
        }

        @Test
        @DisplayName("opens circuit after sustained failures")
        void opensAfterSustainedFailures() {
            LlmCircuitBreaker.ResilienceConfig config = new LlmCircuitBreaker.ResilienceConfig();
            config.failureRateThreshold = 50f;
            config.slidingWindowSize = 4; // small window for fast test
            config.waitDurationOpenStateMs = 60_000; // stay open
            config.maxRetryAttempts = 1; // no retries — each call is one attempt
            config.retryInitialIntervalMs = 10;
            LlmCircuitBreaker breaker = new LlmCircuitBreaker(config);

            // Fill the sliding window with failures
            for (int i = 0; i < 4; i++) {
                try {
                    breaker.execute(() -> {
                        throw new RuntimeException("NonTransient server error");
                    });
                } catch (Exception ignored) {}
            }

            // Circuit should now be open
            assertEquals(CircuitBreaker.State.OPEN, breaker.getState(),
                "Circuit should open after 4/4 failures (100% > 50% threshold). " +
                "State: " + breaker.getState() + ", Metrics: " +
                "failures=" + breaker.getMetrics().getNumberOfFailedCalls() +
                ", total=" + breaker.getMetrics().getNumberOfBufferedCalls());
        }

        @Test
        @DisplayName("rejects calls when circuit is open")
        void rejectsWhenOpen() {
            LlmCircuitBreaker.ResilienceConfig config = new LlmCircuitBreaker.ResilienceConfig();
            config.failureRateThreshold = 50f;
            config.slidingWindowSize = 4;
            config.waitDurationOpenStateMs = 60_000;
            config.maxRetryAttempts = 1;
            config.retryInitialIntervalMs = 10;
            LlmCircuitBreaker breaker = new LlmCircuitBreaker(config);

            // Open the circuit
            for (int i = 0; i < 4; i++) {
                try { breaker.execute(() -> { throw new RuntimeException("NonTransient fail"); }); }
                catch (Exception ignored) {}
            }

            // Now try a call — should be rejected
            assertThrows(CallNotPermittedException.class,
                () -> breaker.execute(() -> "Should not execute"),
                "Open circuit should reject calls with CallNotPermittedException");
        }

        @Test
        @DisplayName("successful calls keep circuit closed")
        void successKeepsCircuitClosed() {
            LlmCircuitBreaker breaker = createFastBreaker();

            for (int i = 0; i < 20; i++) {
                breaker.execute(() -> "Success");
            }

            assertEquals(CircuitBreaker.State.CLOSED, breaker.getState(),
                "Circuit should stay CLOSED with all successful calls");
            assertEquals(0, breaker.getMetrics().getNumberOfFailedCalls());
        }
    }

    // ================================================================
    // EDGE CASES — Adversarial exception patterns
    // ================================================================

    @Nested
    @DisplayName("Exception Classification Edge Cases")
    class ExceptionEdgeCases {

        @Test
        @DisplayName("null exception message is retryable (fail-safe)")
        void nullMessageIsRetryable() {
            LlmCircuitBreaker breaker = createFastBreaker();
            AtomicInteger attempts = new AtomicInteger(0);

            String result = breaker.execute(() -> {
                int attempt = attempts.incrementAndGet();
                if (attempt == 1) {
                    throw new RuntimeException((String) null);
                }
                return "Recovered";
            });

            assertTrue(attempts.get() >= 2,
                "Null message exception should be retried (fail-safe)");
        }

        @Test
        @DisplayName("nested cause with 401 is non-retryable")
        void nestedCauseNonRetryable() {
            LlmCircuitBreaker breaker = createFastBreaker();
            AtomicInteger attempts = new AtomicInteger(0);

            assertThrows(Exception.class, () -> breaker.execute(() -> {
                attempts.incrementAndGet();
                throw new RuntimeException("LLM call failed",
                    new RuntimeException("401 Unauthorized"));
            }));

            assertEquals(1, attempts.get(),
                "Nested cause containing 401 should not be retried");
        }

        @Test
        @DisplayName("rate limit (429) IS retried")
        void rateLimitIsRetried() {
            LlmCircuitBreaker breaker = createFastBreaker();
            AtomicInteger attempts = new AtomicInteger(0);

            String result = breaker.execute(() -> {
                int attempt = attempts.incrementAndGet();
                if (attempt == 1) {
                    throw new RuntimeException("Rate limit exceeded 429 Too Many Requests");
                }
                return "Success after rate limit";
            });

            assertTrue(attempts.get() >= 2,
                "429 rate limit should be retried — it's transient");
        }

        @Test
        @DisplayName("EDGE CASE: '400' in URL doesn't trigger non-retry")
        void numberInUrlDoesNotTriggerNonRetry() {
            LlmCircuitBreaker breaker = createFastBreaker();
            AtomicInteger attempts = new AtomicInteger(0);

            // Exception message contains "400" but as part of a URL, not an HTTP status
            String result = breaker.execute(() -> {
                int attempt = attempts.incrementAndGet();
                if (attempt == 1) {
                    throw new RuntimeException("Failed to connect to port 4000 on server");
                }
                return "Recovered";
            });

            // This test probes whether the "400" substring match is too broad.
            // "port 4000" contains "400" which could falsely match the 400 check.
            // If attempts == 1, the check is too aggressive.
            if (attempts.get() == 1) {
                fail("WEAKNESS DETECTED: '400' in 'port 4000' falsely triggered non-retry. " +
                    "The isRetryable() check uses substring matching which is too broad. " +
                    "Fix: match '400 ' (with space) or use status code extraction.");
            }
        }
    }

    // ================================================================
    // Helpers
    // ================================================================

    private LlmCircuitBreaker createFastBreaker() {
        LlmCircuitBreaker.ResilienceConfig config = new LlmCircuitBreaker.ResilienceConfig();
        config.retryInitialIntervalMs = 10; // fast for testing
        config.retryMultiplier = 1.0; // no backoff growth
        config.slidingWindowSize = 100; // large window — don't trip circuit
        return new LlmCircuitBreaker(config);
    }
}
