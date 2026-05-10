package ai.intelliswarm.swarmai.skill.runtime;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A typed observation emitted by an audit phase.
 *
 * <p>Findings are the lingua franca of phased audits: Phase R emits
 * {@code PORT_OPEN} findings; Phase E reads those findings and only runs
 * its tooling against hosts/ports that actually exist; Phase Report
 * aggregates everything into the user-visible output. This decoupling
 * is what eliminates the "loop nikto over 256 unreachable hosts"
 * anti-pattern the monolithic single-container approach falls into.
 *
 * <p>Inspired by Pentest-Swarm-AI's blackboard primitives
 * ({@code SUBDOMAIN}, {@code PORT_OPEN}, {@code CVE_MATCH}, ...) but
 * scoped to a single in-process audit run rather than a persistent
 * pgvector store.
 *
 * <p>Standard {@code type} values used by built-in phases:
 * <ul>
 *   <li>{@code HOST_UP} — host responded to host-discovery probes</li>
 *   <li>{@code PORT_OPEN} — TCP port confirmed open with service banner</li>
 *   <li>{@code SERVICE_VERSION} — version-detection result for an open port</li>
 *   <li>{@code HTTP_FINGERPRINT} — web-server identification via whatweb/httpx</li>
 *   <li>{@code TLS_INFO} — certificate / cipher info for an https endpoint</li>
 * </ul>
 *
 * <p>Custom phases may use additional types — the type field is a free
 * string so the architecture doesn't lock callers into the built-in set.
 *
 * @param type        broad category (see standard values above)
 * @param host        IP / hostname the finding pertains to (required)
 * @param port        TCP port if applicable, null for host-level findings
 * @param service     service banner / detected protocol, null when unknown
 * @param attributes  free-form key/value annotations (cipher, server header, etc.)
 * @param at          when the finding was recorded (UTC)
 */
public record Finding(
        String type,
        String host,
        Integer port,
        String service,
        Map<String, String> attributes,
        Instant at) {

    public Finding {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(host, "host");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        if (at == null) at = Instant.now();
    }

    /** Convenience: PORT_OPEN finding for the recon phase. */
    public static Finding portOpen(String host, int port, String service) {
        return new Finding("PORT_OPEN", host, port, service, Map.of(), Instant.now());
    }

    /** Convenience: HOST_UP finding when only host-level reachability is known. */
    public static Finding hostUp(String host) {
        return new Finding("HOST_UP", host, null, null, Map.of(), Instant.now());
    }

    /** Convenience: HTTP_FINGERPRINT with structured attribute map. */
    public static Finding httpFingerprint(String host, int port, Map<String, String> attrs) {
        Map<String, String> attributes = new LinkedHashMap<>(attrs);
        return new Finding("HTTP_FINGERPRINT", host, port, "http", attributes, Instant.now());
    }

    /** True if the finding describes a network port (port != null). */
    public boolean hasPort() {
        return port != null;
    }

    /** Pretty one-line summary for log + report output. */
    public String summary() {
        StringBuilder sb = new StringBuilder(type).append("  ").append(host);
        if (port != null) sb.append(":").append(port);
        if (service != null && !service.isBlank()) sb.append("  (").append(service).append(")");
        if (!attributes.isEmpty()) {
            sb.append("  [");
            boolean first = true;
            for (var e : attributes.entrySet()) {
                if (!first) sb.append(", ");
                sb.append(e.getKey()).append("=").append(e.getValue());
                first = false;
            }
            sb.append("]");
        }
        return sb.toString();
    }
}
