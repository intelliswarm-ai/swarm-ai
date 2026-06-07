package ai.intelliswarm.swarmai.skill.disclosure;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Extended SKILL.md frontmatter — fields that drive how the runtime invokes a
 * skill, layered on top of the original
 * {@link ai.intelliswarm.swarmai.skill.SkillDefinition}.
 *
 * <p>Today's {@code SkillDefinition} captures content (instructions, code,
 * references, tests). This captures <em>invocation policy</em>:
 * <ul>
 *   <li>{@link #scope()} — which scope cascade layer this skill lives in</li>
 *   <li>{@link #whenToUse()} — natural-language router hint (Tier-1 visible)</li>
 *   <li>{@link #allowedTools()} — per-skill tool allowlist (intersected with
 *       the agent's permissions; empty = inherit)</li>
 *   <li>{@link #model()} — preferred model id ({@code null} = inherit)</li>
 *   <li>{@link #effort()} — compute envelope ({@link SkillEffort#STANDARD} by default)</li>
 *   <li>{@link #contextMode()} — INHERIT (inline) or FORK (subagent)</li>
 *   <li>{@link #agent()} — alternate agent persona to use ({@code null} = caller)</li>
 *   <li>{@link #hooks()} — lifecycle hooks bound at the skill level (refs by class name)</li>
 *   <li>{@link #paths()} — file-glob auto-attachment patterns</li>
 *   <li>{@link #shell()} — shell hint for skills that spawn subprocesses</li>
 *   <li>{@link #disableModelInvocation()} — when true, skill is only invokable
 *       by user / explicit code, never auto-selected by the LLM</li>
 *   <li>{@link #userInvocableOnly()} — slash-command discoverable but not LLM-selectable</li>
 *   <li>{@link #argumentHint()} — argument shape shown to the LLM at Tier-2</li>
 *   <li>{@link #readiness()} — current runtime readiness</li>
 *   <li>{@link #setupHint()} — when {@link #readiness()} is SETUP_NEEDED, what to do</li>
 * </ul>
 */
@PublicApi(since = "1.0.24")
public record SkillFrontmatter(
        SkillScope scope,
        String whenToUse,
        List<String> allowedTools,
        String model,
        SkillEffort effort,
        SkillContextMode contextMode,
        String agent,
        List<String> hooks,
        List<String> paths,
        String shell,
        boolean disableModelInvocation,
        boolean userInvocableOnly,
        String argumentHint,
        SkillReadiness readiness,
        String setupHint,
        Map<String, String> extras) {

    public SkillFrontmatter {
        Objects.requireNonNull(scope, "scope must not be null");
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        hooks = hooks == null ? List.of() : List.copyOf(hooks);
        paths = paths == null ? List.of() : List.copyOf(paths);
        extras = extras == null ? Map.of() : Map.copyOf(extras);
        if (effort == null) effort = SkillEffort.STANDARD;
        if (contextMode == null) contextMode = SkillContextMode.INHERIT;
        if (readiness == null) readiness = SkillReadiness.AVAILABLE;
    }

    public static Builder builder(SkillScope scope) {
        return new Builder(scope);
    }

    /** Sensible default for project-scope skills with no extra policy. */
    public static SkillFrontmatter defaultsFor(SkillScope scope) {
        return builder(scope).build();
    }

    @PublicApi(since = "1.0.24")
    public static final class Builder {
        private final SkillScope scope;
        private String whenToUse;
        private List<String> allowedTools = List.of();
        private String model;
        private SkillEffort effort = SkillEffort.STANDARD;
        private SkillContextMode contextMode = SkillContextMode.INHERIT;
        private String agent;
        private List<String> hooks = List.of();
        private List<String> paths = List.of();
        private String shell;
        private boolean disableModelInvocation;
        private boolean userInvocableOnly;
        private String argumentHint;
        private SkillReadiness readiness = SkillReadiness.AVAILABLE;
        private String setupHint;
        private Map<String, String> extras = Map.of();

        Builder(SkillScope scope) {
            this.scope = scope;
        }

        public Builder whenToUse(String value) { this.whenToUse = value; return this; }
        public Builder allowedTools(List<String> tools) { this.allowedTools = tools; return this; }
        public Builder allowedTools(String... tools) { this.allowedTools = List.of(tools); return this; }
        public Builder model(String value) { this.model = value; return this; }
        public Builder effort(SkillEffort value) { this.effort = value; return this; }
        public Builder contextMode(SkillContextMode value) { this.contextMode = value; return this; }
        public Builder agent(String value) { this.agent = value; return this; }
        public Builder hooks(List<String> value) { this.hooks = value; return this; }
        public Builder paths(List<String> value) { this.paths = value; return this; }
        public Builder paths(String... value) { this.paths = List.of(value); return this; }
        public Builder shell(String value) { this.shell = value; return this; }
        public Builder disableModelInvocation(boolean v) { this.disableModelInvocation = v; return this; }
        public Builder userInvocableOnly(boolean v) { this.userInvocableOnly = v; return this; }
        public Builder argumentHint(String value) { this.argumentHint = value; return this; }
        public Builder readiness(SkillReadiness value) { this.readiness = value; return this; }
        public Builder setupHint(String value) { this.setupHint = value; return this; }
        public Builder extras(Map<String, String> value) { this.extras = value; return this; }

        public SkillFrontmatter build() {
            return new SkillFrontmatter(scope, whenToUse, allowedTools, model, effort,
                    contextMode, agent, hooks, paths, shell, disableModelInvocation,
                    userInvocableOnly, argumentHint, readiness, setupHint, extras);
        }
    }
}
