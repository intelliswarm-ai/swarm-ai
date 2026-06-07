package ai.intelliswarm.swarmai.skill.runtime;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Self-describing manifest carried by a Docker image, declaring the set of
 * <em>callable capabilities</em> the image exposes. The framework reads this
 * manifest after building or pulling an image and emits one
 * {@link ContainerBackedBaseTool} per declared capability, registering them
 * with the live Agent.
 *
 * <p>This is what makes containerized skills <em>discoverable</em> instead
 * of opaque. Without a manifest, an image is a single
 * {@code docker run image} call; with a manifest, it's a typed library of
 * named, parameterised actions the agent can pick from.
 *
 * <h2>Where the manifest lives</h2>
 * Two transports, in priority order:
 * <ol>
 *   <li><b>Docker LABEL</b> — {@code LABEL swarmai.manifest=<base64-json>}
 *       embedded in the image at build time. Read via
 *       {@code docker inspect}; no container needs to start.</li>
 *   <li><b>Filesystem</b> — {@code /etc/swarmai/manifest.json} or
 *       {@code /etc/swarmai/manifest.yaml} inside the image. Read by
 *       running a one-shot container with a {@code cat} command.</li>
 * </ol>
 *
 * <h2>Schema</h2>
 * <pre>{@code
 * {
 *   "name": "kali-toolbox",
 *   "version": "1",
 *   "description": "Kali pentest tool collection",
 *   "tools": [
 *     {
 *       "name": "kali_nmap",
 *       "description": "Port scan + service detection",
 *       "triggerWhen": "First step of any network audit",
 *       "avoidWhen": "Ranges larger than /16",
 *       "category": "security/network",
 *       "tags": ["network", "discovery"],
 *       "parameters": [
 *         {"name": "target", "type": "string", "required": true,
 *          "description": "CIDR or hostname"}
 *       ],
 *       "entrypoint": "nmap -F -sV -T4 $TARGET",
 *       "timeoutSeconds": 300,
 *       "network": "bridge",
 *       "memory": "1024m"
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <h2>Why a record</h2>
 * Manifests are pure data. The reader produces an immutable
 * {@code CapabilityManifest}; the {@link ManifestToolFactory} consumes one
 * and emits BaseTools. Keeping the data shape immutable means the agent's
 * tool list is deterministic given a manifest — easier to audit and
 * reproduce.
 */
public record CapabilityManifest(
        String name,
        String version,
        String description,
        List<Capability> tools) {

    /** Current manifest schema version. Increment on breaking changes. */
    public static final String SCHEMA_VERSION = "1";

    public CapabilityManifest {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) throw new IllegalArgumentException("manifest name is blank");
        version = (version == null || version.isBlank()) ? SCHEMA_VERSION : version.trim();
        description = description == null ? "" : description.trim();
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /** Convenience: the tool count in this manifest. */
    public int size() { return tools.size(); }

    /** Lookup a single capability by name; null if absent. */
    public Capability find(String toolName) {
        if (toolName == null) return null;
        for (Capability c : tools) {
            if (toolName.equals(c.name())) return c;
        }
        return null;
    }

    // =====================================================================
    // Capability — one callable inside the image
    // =====================================================================

    /**
     * One callable capability inside a container image. Maps directly to
     * a {@link ContainerBackedBaseTool} via {@link ManifestToolFactory}.
     *
     * @param name           tool name the agent sees (must be a valid
     *                       function-name identifier; lowercase + underscores
     *                       recommended).
     * @param description    one-paragraph description shown to the agent.
     *                       Must include WHAT the tool does and the
     *                       expected output shape.
     * @param triggerWhen    when the agent should pick this tool. Free text;
     *                       used in tool-selection scoring.
     * @param avoidWhen      when the agent should NOT pick this tool. Free
     *                       text; used in tool-selection scoring.
     * @param category       coarse grouping (e.g. {@code "security/network"}).
     *                       May be empty.
     * @param tags           fine-grained labels for filtering. May be empty.
     * @param parameters     ordered list of declared parameters.
     * @param entrypoint     shell command run inside the container. Parameter
     *                       values are exposed as environment variables named
     *                       by {@link Parameter#envVar()} (or the parameter's
     *                       {@code name} uppercased if unset).
     * @param timeoutSeconds wall-clock cap on container execution. Required;
     *                       must be > 0. Floored at 30s downstream to avoid
     *                       impossibly tight LLM-suggested deadlines.
     * @param network        Docker network mode: {@code none}, {@code bridge},
     *                       {@code host}. Default {@code bridge}. {@code host}
     *                       is rejected by {@link ContainerPolicy} unless
     *                       explicitly allowed.
     * @param memory         Docker memory cap (e.g. {@code "512m"}). Optional.
     */
    public record Capability(
            String name,
            String description,
            String triggerWhen,
            String avoidWhen,
            String category,
            List<String> tags,
            List<Parameter> parameters,
            String entrypoint,
            long timeoutSeconds,
            String network,
            String memory) {

        public Capability {
            Objects.requireNonNull(name, "tool name");
            if (!name.matches("[a-z][a-z0-9_]{0,63}")) {
                throw new IllegalArgumentException(
                        "tool name must match [a-z][a-z0-9_]{0,63}; got '" + name + "'");
            }
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException("tool '" + name + "' missing description");
            }
            triggerWhen = triggerWhen == null ? "" : triggerWhen.trim();
            avoidWhen = avoidWhen == null ? "" : avoidWhen.trim();
            category = category == null ? "" : category.trim();
            tags = tags == null ? List.of() : List.copyOf(tags);
            parameters = parameters == null ? List.of() : List.copyOf(parameters);
            if (entrypoint == null || entrypoint.isBlank()) {
                throw new IllegalArgumentException("tool '" + name + "' missing entrypoint");
            }
            if (timeoutSeconds <= 0) {
                throw new IllegalArgumentException(
                        "tool '" + name + "' timeoutSeconds must be > 0");
            }
            network = (network == null || network.isBlank()) ? "bridge" : network.trim();
            memory = memory == null ? null : memory.trim();
        }

        /** Convert to a {@link Duration} — convenience for downstream code. */
        public Duration timeout() { return Duration.ofSeconds(timeoutSeconds); }

        /** Build an env-var map (paramName → ENV_VAR_NAME) for this capability. */
        public Map<String, String> envFromParams() {
            Map<String, String> map = new LinkedHashMap<>();
            for (Parameter p : parameters) {
                map.put(p.name(), p.effectiveEnvVar());
            }
            return map;
        }
    }

    // =====================================================================
    // Parameter — one declared input to a capability
    // =====================================================================

    /**
     * One declared parameter on a capability.
     *
     * @param name        parameter name (snake_case recommended). Used as the
     *                    JSON Schema property key and as the lookup key the
     *                    agent passes when invoking the tool.
     * @param type        parameter type. {@code string} only for now;
     *                    {@code integer}, {@code boolean}, {@code array} are
     *                    reserved.
     * @param description shown to the agent in the parameter schema.
     * @param required    whether the agent must supply this parameter.
     * @param envVar      explicit ENV_VAR name inside the container. When
     *                    null/blank, defaults to {@code name.toUpperCase()}.
     */
    public record Parameter(
            String name,
            String type,
            String description,
            boolean required,
            String envVar) {

        public Parameter {
            Objects.requireNonNull(name, "parameter name");
            if (!name.matches("[a-z][a-z0-9_]{0,63}")) {
                throw new IllegalArgumentException(
                        "parameter name must match [a-z][a-z0-9_]{0,63}; got '" + name + "'");
            }
            type = (type == null || type.isBlank()) ? "string" : type.trim();
            if (!type.equals("string")) {
                // Reserved for future. For now we coerce silently to keep
                // forward compatibility — the manifest can declare a richer
                // type but the runtime treats everything as a string env var.
                type = "string";
            }
            description = description == null ? "" : description.trim();
            envVar = envVar == null ? null : envVar.trim();
        }

        /** Defaults envVar to {@code name.toUpperCase()} when not set. */
        public String effectiveEnvVar() {
            return (envVar == null || envVar.isBlank()) ? name.toUpperCase() : envVar;
        }
    }
}
