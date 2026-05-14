package ai.intelliswarm.swarmai.tool.finance.mcp;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves credentials for a given {@link McpConnectorDefinition}. Decoupled
 * from any specific secret store — production deployments wire this to
 * Vault / AWS Secrets Manager / similar; tests use {@link InMemory}.
 *
 * <p>Default impl: {@link FromEnvironment} reads env vars named
 * {@code SWARM_MCP_<NAME>_BEARER} / {@code SWARM_MCP_<NAME>_API_KEY} /
 * {@code SWARM_MCP_<NAME>_OAUTH_TOKEN} where {@code <NAME>} is the connector
 * name uppercased with hyphens replaced by underscores
 * (e.g. {@code lseg-lfa} → {@code SWARM_MCP_LSEG_LFA_BEARER}).
 */
@PublicApi(since = "1.0.24")
public interface McpCredentialResolver {

    /** Resolve credentials for the supplied connector, or {@link Optional#empty} if not configured. */
    Optional<McpCredentials> resolve(McpConnectorDefinition connector);

    /** Always returns empty — useful for catalogs that should report status but never activate. */
    McpCredentialResolver NONE = connector -> Optional.empty();

    /** Reads credentials from environment variables. */
    @PublicApi(since = "1.0.24")
    final class FromEnvironment implements McpCredentialResolver {
        private final java.util.function.Function<String, String> envLookup;

        public FromEnvironment() {
            this(System::getenv);
        }

        /** Visible for testing — inject a custom env-var source. */
        public FromEnvironment(java.util.function.Function<String, String> envLookup) {
            this.envLookup = envLookup;
        }

        @Override
        public Optional<McpCredentials> resolve(McpConnectorDefinition connector) {
            String key = "SWARM_MCP_" + connector.name().toUpperCase(Locale.ROOT).replace('-', '_');
            String bearer = envLookup.apply(key + "_BEARER");
            String apiKey = envLookup.apply(key + "_API_KEY");
            String apiKeyHeader = envLookup.apply(key + "_API_KEY_HEADER");
            String oauthToken = envLookup.apply(key + "_OAUTH_TOKEN");

            if (oauthToken != null) return Optional.of(McpCredentials.oauth(oauthToken));
            if (bearer != null) return Optional.of(McpCredentials.bearer(bearer));
            if (apiKey != null) {
                return apiKeyHeader != null
                        ? Optional.of(McpCredentials.apiKey(apiKey, apiKeyHeader))
                        : Optional.of(McpCredentials.apiKey(apiKey));
            }
            return Optional.empty();
        }
    }

    /** Map-backed resolver — useful for tests + deterministic configuration. */
    @PublicApi(since = "1.0.24")
    final class InMemory implements McpCredentialResolver {
        private final Map<String, McpCredentials> credentials;

        public InMemory(Map<String, McpCredentials> credentials) {
            this.credentials = Map.copyOf(credentials);
        }

        @Override
        public Optional<McpCredentials> resolve(McpConnectorDefinition connector) {
            return Optional.ofNullable(credentials.get(connector.name()));
        }
    }
}
