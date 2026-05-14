package ai.intelliswarm.swarmai.skill.disclosure;

import ai.intelliswarm.swarmai.api.PublicApi;

/**
 * How the agent invokes the skill with respect to its own context window.
 *
 * <ul>
 *   <li>{@link #INHERIT} — run inline with the current agent's context. Cheap;
 *       the skill body and outputs land in the running conversation.</li>
 *   <li>{@link #FORK} — spawn a subagent with an isolated context window. The
 *       skill's intermediate work doesn't pollute the parent; only the final
 *       output is returned. Costs a roundtrip but keeps the parent context
 *       clean — required for skills that produce verbose intermediate output
 *       (e.g. {@code dcf-model}, {@code earnings-analysis}).</li>
 * </ul>
 */
@PublicApi(since = "1.0.24")
public enum SkillContextMode {
    INHERIT,
    FORK
}
