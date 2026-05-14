package ai.intelliswarm.swarmai.agent.resilience;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;
import javax.net.ssl.SSLException;

/**
 * Maps an exception (or HTTP status + message) onto a {@link FailoverReason}.
 * Provider-agnostic — relies on patterns common to OpenAI, Anthropic, Bedrock,
 * and Gemini error messages.
 *
 * <p>The classifier is conservative: when uncertain, falls back to
 * {@link FailoverReason#RETRYABLE} for transient-shaped exceptions and
 * {@link FailoverReason#NOT_RECOVERABLE} for client-side ones.
 */
@PublicApi(since = "1.0.24")
public final class ErrorClassifier {

    private ErrorClassifier() {}

    /**
     * Classify by HTTP status + message body. {@code message} may be {@code null}.
     */
    public static FailoverReason classify(int httpStatus, String message) {
        String msg = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (httpStatus == 401 || httpStatus == 403) {
            return FailoverReason.SHOULD_ROTATE_CREDENTIAL;
        }
        if (httpStatus == 400 || httpStatus == 422) {
            if (mentionsContextWindow(msg)) {
                return FailoverReason.SHOULD_COMPRESS;
            }
            return FailoverReason.NOT_RECOVERABLE;
        }
        if (httpStatus == 404 || httpStatus == 405) {
            return FailoverReason.NOT_RECOVERABLE;
        }
        if (httpStatus == 408 || httpStatus == 409 || httpStatus == 429) {
            return FailoverReason.RETRYABLE;
        }
        if (httpStatus == 503 || mentionsOverloaded(msg)) {
            return FailoverReason.SHOULD_FALLBACK;
        }
        if (httpStatus >= 500 && httpStatus < 600) {
            return FailoverReason.RETRYABLE;
        }
        return FailoverReason.NOT_RECOVERABLE;
    }

    /**
     * Classify by exception. Walks the cause chain so SDK wrappers around
     * IOException still classify correctly.
     */
    public static FailoverReason classify(Throwable error) {
        if (error == null) {
            return FailoverReason.NOT_RECOVERABLE;
        }
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SocketTimeoutException
                    || t instanceof UnknownHostException
                    || t instanceof SSLException) {
                return FailoverReason.RETRYABLE;
            }
            String msg = t.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(Locale.ROOT);
                if (mentionsContextWindow(lower)) return FailoverReason.SHOULD_COMPRESS;
                if (mentionsAuth(lower)) return FailoverReason.SHOULD_ROTATE_CREDENTIAL;
                if (mentionsOverloaded(lower)) return FailoverReason.SHOULD_FALLBACK;
                if (mentionsRateLimit(lower)) return FailoverReason.RETRYABLE;
                if (mentionsTransient(lower)) return FailoverReason.RETRYABLE;
            }
            if (t == t.getCause()) break;
        }
        return FailoverReason.NOT_RECOVERABLE;
    }

    private static boolean mentionsContextWindow(String msg) {
        return msg.contains("context window")
                || msg.contains("context length")
                || msg.contains("maximum context")
                || msg.contains("token limit")
                || msg.contains("max_tokens")
                || msg.contains("prompt is too long");
    }

    private static boolean mentionsOverloaded(String msg) {
        return msg.contains("overloaded")
                || msg.contains("capacity")
                || msg.contains("unavailable")
                || msg.contains("temporarily unable");
    }

    private static boolean mentionsAuth(String msg) {
        return msg.contains("invalid api key")
                || msg.contains("authentication")
                || msg.contains("unauthorized")
                || msg.contains("forbidden")
                || msg.contains("permission denied")
                || msg.contains("invalid token")
                || msg.contains("expired");
    }

    private static boolean mentionsRateLimit(String msg) {
        return msg.contains("rate limit") || msg.contains("too many requests") || msg.contains("429");
    }

    private static boolean mentionsTransient(String msg) {
        return msg.contains("timeout")
                || msg.contains("connection reset")
                || msg.contains("connection refused")
                || msg.contains("temporarily")
                || msg.contains("retry");
    }
}
