package ai.intelliswarm.swarmai.llm.anthropic;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.List;
import java.util.Objects;

/**
 * Anthropic <em>server-side</em> tool declaration. Distinct from
 * {@link AnthropicToolSpec} (user-defined tools that the caller executes):
 * server tools are executed inside Anthropic's infrastructure, so the model's
 * response carries result blocks directly without round-tripping through the
 * caller.
 *
 * <p>Sealed family today:
 * <ul>
 *   <li>{@link WebSearch} — model fetches search results inline</li>
 *   <li>{@link WebFetch} — model retrieves and reads a URL</li>
 *   <li>{@link CodeExecution} — model runs Python/Bash in a sandbox</li>
 *   <li>{@link Memory} — model reads/writes a persistent {@code /memories} surface</li>
 * </ul>
 *
 * <p>Add via {@link AnthropicRequest.Builder#serverTool(ServerToolSpec)}.
 */
@PublicApi(since = "1.0.24")
public sealed interface ServerToolSpec permits
        ServerToolSpec.WebSearch,
        ServerToolSpec.WebFetch,
        ServerToolSpec.CodeExecution,
        ServerToolSpec.Memory {

    /** Anthropic's web-search server tool. */
    @PublicApi(since = "1.0.24")
    record WebSearch(List<String> allowedDomains, List<String> blockedDomains, Integer maxUses)
            implements ServerToolSpec {
        public WebSearch {
            allowedDomains = allowedDomains == null ? List.of() : List.copyOf(allowedDomains);
            blockedDomains = blockedDomains == null ? List.of() : List.copyOf(blockedDomains);
        }

        public static WebSearch unrestricted() {
            return new WebSearch(List.of(), List.of(), null);
        }

        public static WebSearch allowOnly(List<String> domains) {
            return new WebSearch(domains, List.of(), null);
        }

        public static WebSearch block(List<String> domains) {
            return new WebSearch(List.of(), domains, null);
        }
    }

    /** Anthropic's web-fetch server tool (retrieve + read a URL). */
    @PublicApi(since = "1.0.24")
    record WebFetch(List<String> allowedDomains, Integer maxUses, Integer maxContentTokens)
            implements ServerToolSpec {
        public WebFetch {
            allowedDomains = allowedDomains == null ? List.of() : List.copyOf(allowedDomains);
        }

        public static WebFetch unrestricted() {
            return new WebFetch(List.of(), null, null);
        }
    }

    /**
     * Anthropic's code-execution server tool (Python + Bash + files inside a
     * sandboxed container). Configurable in future SDK versions; today this
     * is a marker — the SDK uses the {@code code_execution_20260120} type.
     */
    @PublicApi(since = "1.0.24")
    record CodeExecution() implements ServerToolSpec {}

    /**
     * Anthropic's memory server tool — agent reads/writes a long-lived
     * {@code /memories} surface across turns.
     */
    @PublicApi(since = "1.0.24")
    record Memory() implements ServerToolSpec {}

    /** Reject null in any sealed-interface position. */
    static ServerToolSpec requireNonNull(ServerToolSpec spec) {
        return Objects.requireNonNull(spec, "ServerToolSpec must not be null");
    }
}
