package ai.intelliswarm.swarmai.skill.disclosure;

import ai.intelliswarm.swarmai.api.PublicApi;

/**
 * Where a skill was discovered from. Loosely mirrors Claude Code's scope
 * cascade — used by {@link SkillCatalog} to filter what's visible to which
 * agents and to resolve duplicate-name collisions across layers.
 *
 * <p>Precedence is most-specific wins on duplicate names:
 * {@code PROJECT} overrides {@code PERSONAL} overrides {@code ENTERPRISE}.
 * {@code PLUGIN} sits parallel to {@code PERSONAL} — installed via a plugin
 * marketplace rather than written by hand.
 */
@PublicApi(since = "1.0.24")
public enum SkillScope {
    /** Managed centrally (e.g. by an admin or compliance team). */
    ENTERPRISE,
    /** User-personal skills (e.g. {@code ~/.swarm/skills}). */
    PERSONAL,
    /** Lives inside the current project (e.g. {@code .swarm/skills/}). */
    PROJECT,
    /** Shipped by an installed plugin. */
    PLUGIN
}
