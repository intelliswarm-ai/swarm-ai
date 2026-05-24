package ai.intelliswarm.swarmai.llm.ollama;

import ai.intelliswarm.swarmai.api.PublicApi;

/**
 * Thrown when the native Ollama transport fails — non-2xx HTTP, malformed
 * response body, transport timeout, or interrupted I/O.
 *
 * <p>Carries {@link #httpStatus()} when available so callers can distinguish
 * "model not found" (404) from "server unreachable" (-1).
 */
@PublicApi(since = "1.0.27")
public class OllamaClientException extends RuntimeException {

    private final int httpStatus;

    public OllamaClientException(String message) {
        this(message, -1, null);
    }

    public OllamaClientException(String message, int httpStatus) {
        this(message, httpStatus, null);
    }

    public OllamaClientException(String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    /** -1 when the failure happened before/without an HTTP response. */
    public int httpStatus() { return httpStatus; }
}
