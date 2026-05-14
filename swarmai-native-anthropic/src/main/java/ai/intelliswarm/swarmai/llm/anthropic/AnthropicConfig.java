package ai.intelliswarm.swarmai.llm.anthropic;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for {@link AnthropicNativeClient}. Mostly a thin wrapper around
 * the SDK's own knobs (api key, base url, retries, timeout) so callers don't
 * have to import {@code com.anthropic.client.okhttp.AnthropicOkHttpClient}
 * directly.
 *
 * <p>The {@link #fromEnv()} factory reads {@code ANTHROPIC_API_KEY} (or
 * {@code ANTHROPIC_AUTH_TOKEN}) from the environment — matches the SDK's own
 * default precedence.
 *
 * @param apiKey       Anthropic API key; {@code null} delegates to env-var lookup
 * @param baseUrl      optional override (Bedrock-like gateways, internal proxies); {@code null} → SDK default
 * @param maxRetries   exponential-backoff retry count for transient errors
 * @param requestTimeout per-request timeout
 */
@PublicApi(since = "1.0.24")
public record AnthropicConfig(
        String apiKey,
        String baseUrl,
        int maxRetries,
        Duration requestTimeout) {

    public AnthropicConfig {
        Objects.requireNonNull(requestTimeout, "requestTimeout must not be null");
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be ≥ 0");
        }
    }

    /** Use environment variables for credentials; sensible defaults otherwise. */
    public static AnthropicConfig fromEnv() {
        return new AnthropicConfig(null, null, 2, Duration.ofMinutes(5));
    }

    public static AnthropicConfig withApiKey(String apiKey) {
        return new AnthropicConfig(apiKey, null, 2, Duration.ofMinutes(5));
    }

    public AnthropicConfig withBaseUrl(String baseUrl) {
        return new AnthropicConfig(apiKey, baseUrl, maxRetries, requestTimeout);
    }

    public AnthropicConfig withMaxRetries(int maxRetries) {
        return new AnthropicConfig(apiKey, baseUrl, maxRetries, requestTimeout);
    }

    public AnthropicConfig withRequestTimeout(Duration timeout) {
        return new AnthropicConfig(apiKey, baseUrl, maxRetries, timeout);
    }
}
