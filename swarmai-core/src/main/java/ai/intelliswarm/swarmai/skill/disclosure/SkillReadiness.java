package ai.intelliswarm.swarmai.skill.disclosure;

import ai.intelliswarm.swarmai.api.PublicApi;

/**
 * Whether a skill can be invoked right now. The catalog surfaces this to the
 * LLM so it doesn't waste a turn trying to call a skill whose API key is
 * missing, whose binary isn't on PATH, or whose target platform doesn't match.
 *
 * <ul>
 *   <li>{@link #AVAILABLE} — all preconditions met</li>
 *   <li>{@link #SETUP_NEEDED} — fixable: missing env var, missing binary, etc.
 *       The catalog should surface the {@code setupHint} so the user (or
 *       another agent) can resolve it</li>
 *   <li>{@link #UNSUPPORTED} — won't work on this host (wrong OS, unsupported
 *       arch); the skill should be hidden from the LLM entirely</li>
 * </ul>
 */
@PublicApi(since = "1.0.24")
public enum SkillReadiness {
    AVAILABLE,
    SETUP_NEEDED,
    UNSUPPORTED
}
