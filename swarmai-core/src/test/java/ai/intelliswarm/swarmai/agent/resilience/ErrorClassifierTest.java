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

    /**
     * Regression for the 1.0.24-SNAPSHOT regression that hard-failed 14/25
     * judge-all workflows on attempt 1: OpenAI returned an empty response
     * body, Spring AI surfaced it as RestClientException → HttpMessageNotReadable
     * → JsonEOFException, the previous classifier missed every keyword and
     * fell through to NOT_RECOVERABLE → callWithRetry threw without retrying.
     * Response-side breakage like this is transient: a retry of the same
     * request typically succeeds, so it must classify as RETRYABLE.
     */
    @Test
    void openAiEmptyResponseBodyChainIsRetryable() {
        // Build the actual exception chain Spring AI throws on an empty body:
        //   RestClientException("Error while extracting response …")
        //     ↳ HttpMessageNotReadableException("JSON parse error: Unexpected end-of-input")
        //         ↳ JsonEOFException("Unexpected end-of-input: expected close marker for Object")
        //
        // We construct concrete instances if the libraries are available; the
        // classifier matches by class name so this works whether the test
        // module pulls Spring AI / Jackson on the test classpath or not.
        Throwable chain;
        try {
            Class<?> jsonEof = Class.forName("com.fasterxml.jackson.core.io.JsonEOFException");
            Throwable eof = (Throwable) jsonEof.getDeclaredConstructor(
                    com.fasterxml.jackson.core.JsonParser.class,
                    com.fasterxml.jackson.core.JsonToken.class,
                    String.class)
                    .newInstance(null, null,
                            "Unexpected end-of-input: expected close marker for Object");
            chain = new RuntimeException("Error while extracting response for type "
                    + "[org.springframework.ai.openai.api.OpenAiApi$ChatCompletion] "
                    + "and content type [application/json]", eof);
        } catch (ReflectiveOperationException e) {
            // Library not on classpath in this build — simulate the message-
            // chain shape so the keyword path covers us.
            chain = new RuntimeException("Error while extracting response for type "
                    + "[org.springframework.ai.openai.api.OpenAiApi$ChatCompletion] "
                    + "and content type [application/json]",
                    new java.io.IOException(
                            "Unexpected end-of-input: expected close marker for Object"));
        }
        assertThat(ErrorClassifier.classify(chain)).isEqualTo(FailoverReason.RETRYABLE);
    }

    @Test
    void plainIoExceptionIsRetryable() {
        // Bare IOException (e.g. broken pipe, connection reset wrapped as IOE)
        // is response-side and should retry.
        assertThat(ErrorClassifier.classify(new java.io.IOException("Broken pipe")))
                .isEqualTo(FailoverReason.RETRYABLE);
    }
}
