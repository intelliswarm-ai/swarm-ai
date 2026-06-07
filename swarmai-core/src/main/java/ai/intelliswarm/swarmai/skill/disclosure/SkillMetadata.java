package ai.intelliswarm.swarmai.skill.disclosure;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.Objects;

/**
 * Lightweight Tier-1 skill listing — what the catalog injects into the agent's
 * tool list. Carries enough for the LLM to decide "do I want this skill?"
 * without paying the token cost of the full body.
 *
 * <p>If the model selects a skill from the listing, the runtime fetches the
 * full body via {@link SkillCatalog#view(String)} on demand — that's the
 * Tier-2 step.
 *
 * <p>Token-budget rules of thumb:
 * <ul>
 *   <li>Tier-1 metadata: ~50 tokens per skill — fits 100+ skills in 5K tokens</li>
 *   <li>Tier-2 body: 500–5000 tokens, fetched only when the skill is invoked</li>
 * </ul>
 *
 * @param name           skill identifier (kebab-case)
 * @param description    one-line capability summary (≤ 200 chars)
 * @param whenToUse      natural-language router hint
 * @param argumentHint   short argument-shape preview (≤ 60 chars), {@code null} if none
 * @param scope          where this skill came from
 * @param readiness      whether the skill is invokable now
 * @param setupHint      remediation hint when readiness is SETUP_NEEDED
 * @param disableModelInvocation true → user-only, hide from the LLM's selection pass
 */
@PublicApi(since = "1.0.24")
public record SkillMetadata(
        String name,
        String description,
        String whenToUse,
        String argumentHint,
        SkillScope scope,
        SkillReadiness readiness,
        String setupHint,
        boolean disableModelInvocation) {

    public SkillMetadata {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(readiness, "readiness must not be null");
    }

    /** Visible to the LLM during selection: AVAILABLE skills not flagged user-only. */
    public boolean visibleToLlm() {
        return readiness == SkillReadiness.AVAILABLE && !disableModelInvocation;
    }

    /** Render a one-line listing entry for inclusion in the model's prompt. */
    public String toListingLine() {
        StringBuilder sb = new StringBuilder("- ").append(name).append(": ").append(description);
        if (whenToUse != null && !whenToUse.isBlank()) {
            sb.append(" (use when: ").append(whenToUse).append(")");
        }
        if (argumentHint != null && !argumentHint.isBlank()) {
            sb.append("  args: ").append(argumentHint);
        }
        return sb.toString();
    }
}
