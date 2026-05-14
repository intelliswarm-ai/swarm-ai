package ai.intelliswarm.swarmai.tool.finance.mcp;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.Objects;

/**
 * Auth material for an MCP connector. The connector's
 * {@link McpConnectorDefinition.AuthScheme} dictates which fields must be
 * populated:
 *
 * <ul>
 *   <li>{@code BEARER} → {@link #bearerToken} required</li>
 *   <li>{@code API_KEY} → {@link #apiKey} required; {@link #apiKeyHeader}
 *       defaults to {@code "X-API-Key"} when null</li>
 *   <li>{@code OAUTH} → {@link #oauthAccessToken} required; refresh handled
 *       externally (token expiry monitoring is a follow-up)</li>
 *   <li>{@code NONE} → {@link #NONE}</li>
 * </ul>
 */
@PublicApi(since = "1.0.24")
public record McpCredentials(
        String bearerToken,
        String apiKey,
        String apiKeyHeader,
        String oauthAccessToken) {

    public static final McpCredentials NONE = new McpCredentials(null, null, null, null);

    public static McpCredentials bearer(String token) {
        Objects.requireNonNull(token, "token must not be null");
        return new McpCredentials(token, null, null, null);
    }

    public static McpCredentials apiKey(String key) {
        Objects.requireNonNull(key, "key must not be null");
        return new McpCredentials(null, key, null, null);
    }

    public static McpCredentials apiKey(String key, String header) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(header, "header must not be null");
        return new McpCredentials(null, key, header, null);
    }

    public static McpCredentials oauth(String accessToken) {
        Objects.requireNonNull(accessToken, "accessToken must not be null");
        return new McpCredentials(null, null, null, accessToken);
    }

    public boolean isEmpty() {
        return bearerToken == null && apiKey == null && oauthAccessToken == null;
    }

    /** Authorization header value to send, or {@code null} when no header is needed. */
    public String authorizationHeaderValue() {
        if (bearerToken != null) return "Bearer " + bearerToken;
        if (oauthAccessToken != null) return "Bearer " + oauthAccessToken;
        return null;
    }
}
