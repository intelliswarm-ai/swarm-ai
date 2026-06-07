package ai.intelliswarm.swarmai.skill.runtime;

import ai.intelliswarm.swarmai.skill.ContainerSkillSpec;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Adapter that exposes a Docker-backed {@link ContainerSkillSpec} as a
 * first-class {@link BaseTool} the agent can call repeatedly with
 * different parameters.
 *
 * <p>This is the architectural shift from "container = run-once script"
 * to "container = callable tool". The agent sees this in its tool list
 * (just like a CalculatorTool or WebSearchTool), invokes it with
 * structured params, gets a structured result back, then reasons about
 * what to do next. Same pattern Claude Code uses with its built-in
 * Bash/Read/Grep tools — we're just back-filling our own pentest action
 * set on the same shape.
 *
 * <h2>Output parsing</h2>
 * Raw stdout is rarely what an agent wants in its context window. Each
 * tool provides an {@code outputParser} that converts stdout into
 * something agent-friendly: a list of structured findings rendered as
 * a short string, a JSON document, or a one-line summary. Long blobs
 * get truncated by {@link BaseTool#getMaxResponseLength()} downstream.
 *
 * <h2>Caching</h2>
 * Container builds are cached at the runtime layer (deterministic SHA1
 * tag from the Dockerfile). Tool calls reuse the same image; only the
 * runtime per-call cost is paid.
 */
public final class ContainerBackedBaseTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(ContainerBackedBaseTool.class);

    private final String name;
    private final String description;
    private final String triggerWhen;
    private final String avoidWhen;
    private final String category;
    private final List<String> tags;
    private final Map<String, Object> parameterSchema;
    private final ContainerSkillSpec spec;
    private final ContainerSkillRuntime runtime;
    private final Function<String, String> outputParser;

    private ContainerBackedBaseTool(Builder b) {
        this.name = Objects.requireNonNull(b.name, "name");
        this.description = Objects.requireNonNull(b.description, "description");
        this.triggerWhen = b.triggerWhen != null ? b.triggerWhen : "";
        this.avoidWhen = b.avoidWhen != null ? b.avoidWhen : "";
        this.category = b.category != null ? b.category : "container";
        this.tags = b.tags != null ? List.copyOf(b.tags) : List.of();
        this.parameterSchema = Objects.requireNonNull(b.parameterSchema, "parameterSchema");
        this.spec = Objects.requireNonNull(b.spec, "spec");
        this.runtime = Objects.requireNonNull(b.runtime, "runtime");
        this.outputParser = b.outputParser != null ? b.outputParser : Function.identity();
    }

    @Override public String getFunctionName() { return name; }
    @Override public String getDescription() { return description; }
    /** Always dynamic — these tools live in code, not as Spring beans. */
    @Override public boolean isDynamic() { return true; }
    @Override public Map<String, Object> getParameterSchema() { return parameterSchema; }
    @Override public boolean isAsync() { return false; }
    @Override public String getTriggerWhen() { return triggerWhen; }
    @Override public String getAvoidWhen() { return avoidWhen; }
    @Override public String getCategory() { return category; }
    @Override public List<String> getTags() { return tags; }

    /** Container skills run with `--network bridge`; treat as elevated. */
    @Override public PermissionLevel getPermissionLevel() {
        return PermissionLevel.WORKSPACE_WRITE;
    }

    @Override public int getMaxResponseLength() {
        // Pentest tool output can be large (full nmap reports, whatweb
        // fingerprints). Cap at 8 KB so the agent's context window doesn't
        // get hijacked by a single tool call.
        return 8192;
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        ContainerSkillRuntime.ExecResult result;
        try {
            result = runtime.execute(spec, parameters);
        } catch (Exception e) {
            return "Error: " + getFunctionName() + " failed: " + e.getMessage();
        }
        if (result.ok()) {
            String parsed;
            try {
                parsed = outputParser.apply(result.stdout());
            } catch (RuntimeException e) {
                logger.warn("[{}] output parser threw: {}", name, e.getMessage());
                parsed = result.stdout();
            }
            return parsed == null || parsed.isBlank()
                    ? "(no output)"
                    : parsed;
        }
        // Non-zero exit. Many pentest tools (whatweb, nikto) exit non-zero
        // even when stdout is meaningful, so try to parse first; only fall
        // back to a bare error string when stdout is empty.
        if (!result.stdout().isBlank()) {
            try {
                String parsed = outputParser.apply(result.stdout());
                if (parsed != null && !parsed.isBlank()) {
                    return parsed + "\n(note: container exited " + result.exitCode() + ")";
                }
            } catch (RuntimeException ignored) { }
        }
        String stderr = result.stderr() == null ? "" : result.stderr().trim();
        if (stderr.length() > 600) stderr = stderr.substring(0, 597) + "...";
        return "Error: " + name + " exited " + result.exitCode()
             + (stderr.isEmpty() ? "" : ": " + stderr);
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String name, description, triggerWhen, avoidWhen, category;
        private List<String> tags;
        private Map<String, Object> parameterSchema;
        private ContainerSkillSpec spec;
        private ContainerSkillRuntime runtime;
        private Function<String, String> outputParser;

        public Builder name(String n) { this.name = n; return this; }
        public Builder description(String d) { this.description = d; return this; }
        public Builder triggerWhen(String t) { this.triggerWhen = t; return this; }
        public Builder avoidWhen(String a) { this.avoidWhen = a; return this; }
        public Builder category(String c) { this.category = c; return this; }
        public Builder tags(List<String> t) { this.tags = t; return this; }
        public Builder parameterSchema(Map<String, Object> s) { this.parameterSchema = s; return this; }
        public Builder spec(ContainerSkillSpec s) { this.spec = s; return this; }
        public Builder runtime(ContainerSkillRuntime r) { this.runtime = r; return this; }
        public Builder outputParser(Function<String, String> p) { this.outputParser = p; return this; }
        public ContainerBackedBaseTool build() { return new ContainerBackedBaseTool(this); }
    }
}
