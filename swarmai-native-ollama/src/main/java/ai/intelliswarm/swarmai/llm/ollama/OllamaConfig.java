package ai.intelliswarm.swarmai.llm.ollama;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Connection + per-request defaults for the {@link OllamaLlmClient}.
 *
 * <p>{@code baseUrl} points at the Ollama server (e.g. {@code http://localhost:11434});
 * the client appends the {@code /api/chat} suffix. {@code defaultOptions}
 * carries Ollama-specific runtime options ({@code num_ctx}, {@code num_predict},
 * {@code seed}, ...) that can't travel through the OpenAI-shaped Spring AI
 * request. Per-call options on an {@link ai.intelliswarm.swarmai.agent.llm.LlmRequest}
 * still override what's set here.
 *
 * <p>Built via {@link #builder()} or {@link #fromEnv()} (which reads
 * {@code OLLAMA_BASE_URL} and {@code OLLAMA_NUM_CTX}).
 */
@PublicApi(since = "1.0.27")
public final class OllamaConfig {

    public static final String DEFAULT_BASE_URL = "http://localhost:11434";
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofMinutes(5);
    public static final int DEFAULT_NUM_CTX = 16384;

    private final String baseUrl;
    private final Duration requestTimeout;
    private final Map<String, Object> defaultOptions;

    private OllamaConfig(Builder b) {
        this.baseUrl = trimTrailingSlash(Objects.requireNonNull(b.baseUrl, "baseUrl"));
        this.requestTimeout = Objects.requireNonNull(b.requestTimeout, "requestTimeout");
        this.defaultOptions = Map.copyOf(b.defaultOptions);
    }

    public String baseUrl() { return baseUrl; }
    public Duration requestTimeout() { return requestTimeout; }
    public Map<String, Object> defaultOptions() { return defaultOptions; }

    public String chatEndpoint() { return baseUrl + "/api/chat"; }

    public static Builder builder() { return new Builder(); }

    /**
     * Build from environment: {@code OLLAMA_BASE_URL} sets the server,
     * {@code OLLAMA_NUM_CTX} sets the default context window. Anything
     * unset uses the {@code DEFAULT_*} constants on this class.
     */
    public static OllamaConfig fromEnv() {
        Builder b = builder();
        String url = firstNonBlank(System.getenv("OLLAMA_BASE_URL"), System.getProperty("OLLAMA_BASE_URL"));
        if (url != null) b.baseUrl(url);
        String numCtx = firstNonBlank(System.getenv("OLLAMA_NUM_CTX"), System.getProperty("OLLAMA_NUM_CTX"));
        if (numCtx != null) {
            try {
                b.option("num_ctx", Integer.parseInt(numCtx.trim()));
            } catch (NumberFormatException ignored) {
                // fall through to default num_ctx below
            }
        }
        if (!b.defaultOptions.containsKey("num_ctx")) {
            b.option("num_ctx", DEFAULT_NUM_CTX);
        }
        return b.build();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    @PublicApi(since = "1.0.27")
    public static final class Builder {
        private String baseUrl = DEFAULT_BASE_URL;
        private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;
        private final Map<String, Object> defaultOptions = new LinkedHashMap<>();

        public Builder baseUrl(String url) { this.baseUrl = url; return this; }
        public Builder requestTimeout(Duration d) { this.requestTimeout = d; return this; }
        public Builder option(String key, Object value) { this.defaultOptions.put(key, value); return this; }
        public Builder options(Map<String, Object> opts) { this.defaultOptions.putAll(opts); return this; }

        public OllamaConfig build() { return new OllamaConfig(this); }
    }
}
