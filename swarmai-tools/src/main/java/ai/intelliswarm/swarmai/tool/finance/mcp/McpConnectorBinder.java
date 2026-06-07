package ai.intelliswarm.swarmai.tool.finance.mcp;

import ai.intelliswarm.swarmai.api.PublicApi;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Turns {@link McpConnectorDefinition}s + {@link McpCredentials} into live
 * {@link McpSyncClient}s, then enumerates each server's tools.
 *
 * <p>Pipeline:
 * <pre>{@code
 * registry.activeConnectors()
 *   → for each definition, resolve credentials
 *   → build HttpClientSseClientTransport with Authorization header injected
 *   → McpClient.sync(transport).build() + initialize()
 *   → call listTools() to enumerate server-side capabilities
 * }</pre>
 *
 * <p>The result is an ordered list of {@link Binding}s — connector + active
 * client + the list of {@code McpSchema.Tool} the server advertises. Callers
 * then turn those into {@code BaseTool}s either via the existing
 * {@code McpToolAdapter} (stdio-shaped) or a custom binding strategy.
 *
 * <p>OAuth scheme: the bearer-token slot is used as-is; token refresh /
 * expiry monitoring is a follow-up.
 */
@PublicApi(since = "1.0.24")
public final class McpConnectorBinder {

    private static final Logger log = LoggerFactory.getLogger(McpConnectorBinder.class);

    private final McpConnectorRegistry registry;
    private final McpCredentialResolver credentialResolver;

    public McpConnectorBinder(McpConnectorRegistry registry, McpCredentialResolver credentialResolver) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.credentialResolver = Objects.requireNonNull(credentialResolver, "credentialResolver must not be null");
    }

    /**
     * Bind every active connector and return the bindings. Skips connectors
     * with missing credentials, logs the reason. Caller is responsible for
     * closing the returned clients via {@link Binding#client()}.
     */
    public List<Binding> bindAll() {
        List<Binding> out = new ArrayList<>();
        for (McpConnectorDefinition def : registry.activeConnectors()) {
            McpCredentials credentials = credentialResolver.resolve(def).orElse(null);
            if (credentials == null && def.authScheme() != McpConnectorDefinition.AuthScheme.NONE) {
                log.info("Skipping connector {} — credentials not configured", def.name());
                continue;
            }
            try {
                Binding binding = bindOne(def, credentials);
                out.add(binding);
            } catch (RuntimeException e) {
                log.warn("Failed to bind connector {}: {}", def.name(), e.getMessage());
            }
        }
        return List.copyOf(out);
    }

    /** Bind a single connector with explicit credentials — useful for tests + targeted activation. */
    public Binding bindOne(McpConnectorDefinition def, McpCredentials credentials) {
        Objects.requireNonNull(def, "definition must not be null");
        HttpClientSseClientTransport.Builder transportBuilder =
                HttpClientSseClientTransport.builder(def.endpoint());

        // Inject credentials into every outbound request.
        if (credentials != null && !credentials.isEmpty()) {
            transportBuilder.customizeRequest(requestBuilder -> {
                String authValue = credentials.authorizationHeaderValue();
                if (authValue != null) {
                    requestBuilder.header("Authorization", authValue);
                }
                if (credentials.apiKey() != null) {
                    String header = credentials.apiKeyHeader() != null
                            ? credentials.apiKeyHeader() : "X-API-Key";
                    requestBuilder.header(header, credentials.apiKey());
                }
            });
        }

        McpSyncClient client = McpClient.sync(transportBuilder.build()).build();
        try {
            client.initialize();
            McpSchema.ListToolsResult tools = client.listTools();
            return new Binding(def, client, tools.tools());
        } catch (RuntimeException e) {
            try { client.close(); } catch (RuntimeException ignored) {}
            throw new ConnectorBindException("Failed to initialize connector " + def.name(), e);
        }
    }

    /** Status report of every catalog entry: bound? skipped? failed? */
    public Map<String, BindStatus> statusReport() {
        Map<String, BindStatus> report = new LinkedHashMap<>();
        for (var statusEntry : registry.statusReport().entrySet()) {
            McpConnectorRegistry.Status.Result baseStatus = statusEntry.getValue();
            if (baseStatus.status() != McpConnectorRegistry.Status.ACTIVE) {
                report.put(statusEntry.getKey(), new BindStatus(
                        BindResult.NOT_ACTIVE, baseStatus.reason(), null));
                continue;
            }
            McpConnectorDefinition def = baseStatus.definition();
            McpCredentials cred = credentialResolver.resolve(def).orElse(null);
            if (cred == null && def.authScheme() != McpConnectorDefinition.AuthScheme.NONE) {
                report.put(statusEntry.getKey(), new BindStatus(
                        BindResult.CREDENTIALS_MISSING,
                        "No credentials resolved for " + def.name(), null));
            } else {
                report.put(statusEntry.getKey(), new BindStatus(
                        BindResult.READY, "Active and credentials resolved", def));
            }
        }
        return Map.copyOf(report);
    }

    /** One connected connector — definition + live SDK client + advertised tools. */
    @PublicApi(since = "1.0.24")
    public record Binding(McpConnectorDefinition definition, McpSyncClient client, List<McpSchema.Tool> tools) {}

    @PublicApi(since = "1.0.24")
    public enum BindResult { READY, CREDENTIALS_MISSING, NOT_ACTIVE, FAILED }

    @PublicApi(since = "1.0.24")
    public record BindStatus(BindResult result, String reason, McpConnectorDefinition definition) {}

    @PublicApi(since = "1.0.24")
    public static final class ConnectorBindException extends RuntimeException {
        public ConnectorBindException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
