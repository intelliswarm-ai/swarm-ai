package ai.intelliswarm.swarmai.llm.anthropic;

import ai.intelliswarm.swarmai.api.PublicApi;

/**
 * Wraps SDK-side failures behind a SwarmAI-typed exception so callers don't
 * need to import {@code com.anthropic.errors.*}. The original SDK exception
 * is preserved as the cause for stack-trace fidelity.
 *
 * <p>{@link #requestId()} is the Anthropic-issued {@code request-id} header
 * when the SDK had a response in hand at the time of failure; {@code null}
 * for purely client-side failures (timeouts, JSON decode errors).
 */
@PublicApi(since = "1.0.24")
public class AnthropicClientException extends RuntimeException {

    private final String requestId;
    private final Integer httpStatus;

    public AnthropicClientException(String message, Throwable cause, String requestId, Integer httpStatus) {
        super(message, cause);
        this.requestId = requestId;
        this.httpStatus = httpStatus;
    }

    public AnthropicClientException(String message, Throwable cause) {
        this(message, cause, null, null);
    }

    public String requestId() {
        return requestId;
    }

    public Integer httpStatus() {
        return httpStatus;
    }
}
