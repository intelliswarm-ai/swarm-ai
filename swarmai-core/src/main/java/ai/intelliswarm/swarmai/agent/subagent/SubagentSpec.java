package ai.intelliswarm.swarmai.agent.subagent;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Describes a subagent the parent wants to spawn. Constructed by the parent
 * (typically inside a {@code delegate_to_subagent} tool implementation) and
 * passed to a {@link SubagentSpawner}.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@link #id} — stable identifier, defaults to a fresh UUID. Surfaces in
 *       logs, metrics, and the {@link SubagentResult}.</li>
 *   <li>{@link #type} — short label for telemetry / dashboards (e.g.
 *       {@code "researcher"}, {@code "code-editor"}). Not interpreted by
 *       the runtime; descriptive only.</li>
 *   <li>{@link #systemPrompt} — system instructions handed to the model.
 *       This is what makes the subagent <em>this kind</em> of subagent.</li>
 *   <li>{@link #userPrompt} — the initial user message (the task to perform).</li>
 *   <li>{@link #allowedToolNames} — names of Spring-AI-registered tools the
 *       subagent may call. Empty list means no tools (text-only). Names that
 *       don't resolve in the registry produce an error at spawn time.</li>
 *   <li>{@link #timeout} — maximum wall-clock time before
 *       {@link Subagent#awaitResult} returns
 *       {@link SubagentStatus#TIMED_OUT}. Use {@code Duration.ZERO} to mean
 *       "no timeout"; the spawner's default (typically 5 minutes) applies.</li>
 *   <li>{@link #workspaceFactory} — optional {@link IsolatedWorkspace}
 *       provider. When non-null, the spawner materialises a workspace and
 *       reaps it after the subagent terminates. Use this when the subagent's
 *       tools need filesystem isolation.</li>
 *   <li>{@link #parentMetadata} — opaque key/value pairs the parent may
 *       attach for the spawner's bookkeeping (correlation ids, plan-channel
 *       handles, etc.). Not interpreted by the spawner.</li>
 * </ul>
 *
 * <p>Constructed via {@link #builder()} for clarity.
 */
public final class SubagentSpec {

    private final String id;
    private final String type;
    private final String systemPrompt;
    private final String userPrompt;
    private final List<String> allowedToolNames;
    private final Duration timeout;
    private final java.util.function.Supplier<IsolatedWorkspace> workspaceFactory;
    private final Map<String, Object> parentMetadata;

    private SubagentSpec(String id,
                         String type,
                         String systemPrompt,
                         String userPrompt,
                         List<String> allowedToolNames,
                         Duration timeout,
                         java.util.function.Supplier<IsolatedWorkspace> workspaceFactory,
                         Map<String, Object> parentMetadata) {
        this.id = id;
        this.type = type;
        this.systemPrompt = systemPrompt;
        this.userPrompt = userPrompt;
        this.allowedToolNames = allowedToolNames;
        this.timeout = timeout;
        this.workspaceFactory = workspaceFactory;
        this.parentMetadata = parentMetadata;
    }

    public String id() { return id; }
    public String type() { return type; }
    public String systemPrompt() { return systemPrompt; }
    public String userPrompt() { return userPrompt; }
    public List<String> allowedToolNames() { return allowedToolNames; }
    public Duration timeout() { return timeout; }
    public java.util.function.Supplier<IsolatedWorkspace> workspaceFactory() { return workspaceFactory; }
    public Map<String, Object> parentMetadata() { return parentMetadata; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String id = UUID.randomUUID().toString();
        private String type = "subagent";
        private String systemPrompt = "";
        private String userPrompt = "";
        private final java.util.List<String> allowedToolNames = new java.util.ArrayList<>();
        private Duration timeout = Duration.ZERO;
        private java.util.function.Supplier<IsolatedWorkspace> workspaceFactory;
        private final Map<String, Object> parentMetadata = new LinkedHashMap<>();

        public Builder id(String id) { this.id = Objects.requireNonNull(id, "id"); return this; }
        public Builder type(String type) { this.type = Objects.requireNonNull(type, "type"); return this; }
        public Builder systemPrompt(String s) { this.systemPrompt = s != null ? s : ""; return this; }
        public Builder userPrompt(String s) { this.userPrompt = s != null ? s : ""; return this; }

        public Builder allowedTool(String name) {
            this.allowedToolNames.add(Objects.requireNonNull(name, "name"));
            return this;
        }

        public Builder allowedTools(List<String> names) {
            if (names != null) {
                for (String n : names) allowedTool(n);
            }
            return this;
        }

        public Builder timeout(Duration d) {
            this.timeout = d != null ? d : Duration.ZERO;
            return this;
        }

        public Builder workspace(java.util.function.Supplier<IsolatedWorkspace> factory) {
            this.workspaceFactory = factory;
            return this;
        }

        public Builder parentMetadata(String key, Object value) {
            this.parentMetadata.put(Objects.requireNonNull(key, "key"), value);
            return this;
        }

        public Builder parentMetadata(Map<String, Object> map) {
            if (map != null) this.parentMetadata.putAll(map);
            return this;
        }

        public SubagentSpec build() {
            if (userPrompt.isBlank()) {
                throw new IllegalStateException("userPrompt is required");
            }
            return new SubagentSpec(
                    id, type, systemPrompt, userPrompt,
                    List.copyOf(allowedToolNames),
                    timeout,
                    workspaceFactory,
                    Map.copyOf(parentMetadata));
        }
    }

    @Override
    public String toString() {
        return "SubagentSpec{id=" + id + ", type=" + type +
                ", tools=" + allowedToolNames.size() +
                ", timeout=" + timeout +
                (workspaceFactory != null ? ", workspace=on" : "") + "}";
    }
}
