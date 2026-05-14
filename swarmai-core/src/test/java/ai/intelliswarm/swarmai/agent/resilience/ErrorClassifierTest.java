package ai.intelliswarm.swarmai.agent.resilience;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorClassifierTest {

    @Test
    void httpAuthErrorsRotateCredential() {
        assertThat(ErrorClassifier.classify(401, "invalid api key"))
                .isEqualTo(FailoverReason.SHOULD_ROTATE_CREDENTIAL);
        assertThat(ErrorClassifier.classify(403, "forbidden"))
                .isEqualTo(FailoverReason.SHOULD_ROTATE_CREDENTIAL);
    }

    @Test
    void contextWindowFromBadRequestCompresses() {
        assertThat(ErrorClassifier.classify(400, "Prompt is too long for context window"))
                .isEqualTo(FailoverReason.SHOULD_COMPRESS);
        assertThat(ErrorClassifier.classify(422, "exceeds maximum context length"))
                .isEqualTo(FailoverReason.SHOULD_COMPRESS);
    }

    @Test
    void badRequestWithoutContextHintIsNotRecoverable() {
        assertThat(ErrorClassifier.classify(400, "tool schema invalid"))
                .isEqualTo(FailoverReason.NOT_RECOVERABLE);
    }

    @Test
    void rateLimitRetryable() {
        assertThat(ErrorClassifier.classify(429, "rate limit exceeded"))
                .isEqualTo(FailoverReason.RETRYABLE);
        assertThat(ErrorClassifier.classify(408, "request timeout"))
                .isEqualTo(FailoverReason.RETRYABLE);
    }

    @Test
    void serviceUnavailableFallsOver() {
        assertThat(ErrorClassifier.classify(503, "service unavailable"))
                .isEqualTo(FailoverReason.SHOULD_FALLBACK);
        // 500-level "overloaded" mention → fallback
        assertThat(ErrorClassifier.classify(500, "model is overloaded"))
                .isEqualTo(FailoverReason.SHOULD_FALLBACK);
    }

    @Test
    void plain5xxRetryable() {
        assertThat(ErrorClassifier.classify(502, "bad gateway"))
                .isEqualTo(FailoverReason.RETRYABLE);
    }

    @Test
    void networkExceptionsRetryable() {
        assertThat(ErrorClassifier.classify(new SocketTimeoutException("read timed out")))
                .isEqualTo(FailoverReason.RETRYABLE);
        assertThat(ErrorClassifier.classify(new UnknownHostException("api.anthropic.com")))
                .isEqualTo(FailoverReason.RETRYABLE);
        assertThat(ErrorClassifier.classify(new SSLHandshakeException("handshake failed")))
                .isEqualTo(FailoverReason.RETRYABLE);
    }

    @Test
    void wrappedSocketTimeoutStillRetryable() {
        Throwable wrapper = new RuntimeException("wrapper", new SocketTimeoutException("timed out"));
        assertThat(ErrorClassifier.classify(wrapper)).isEqualTo(FailoverReason.RETRYABLE);
    }

    @Test
    void messageBasedAuthDetection() {
        assertThat(ErrorClassifier.classify(new RuntimeException("Authentication failed: invalid token")))
                .isEqualTo(FailoverReason.SHOULD_ROTATE_CREDENTIAL);
    }

    @Test
    void nullErrorIsNotRecoverable() {
        assertThat(ErrorClassifier.classify((Throwable) null)).isEqualTo(FailoverReason.NOT_RECOVERABLE);
    }
}
