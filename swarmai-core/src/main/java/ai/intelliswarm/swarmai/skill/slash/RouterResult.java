package ai.intelliswarm.swarmai.skill.slash;

/**
 * Result of {@link SlashCommandRouter#route(String)}. One of three cases:
 *
 * <ul>
 *   <li>{@link SkillExecuted} — the input was a slash command that resolved
 *       to a registered skill, and the skill produced output</li>
 *   <li>{@link SkillNotFound} — the input was a slash command but the named
 *       skill is not registered</li>
 *   <li>{@link PassThrough} — the input is plain text (no leading slash, or
 *       slash with invalid grammar). The harness should send it to the LLM.</li>
 * </ul>
 */
public sealed interface RouterResult {

    /** A registered skill ran and produced output. */
    record SkillExecuted(String skillName, String output) implements RouterResult {}

    /** Input was {@code /name ...} but no skill named {@code name} is registered. */
    record SkillNotFound(String name) implements RouterResult {}

    /** Plain text — pass through to the LLM. */
    record PassThrough(String input) implements RouterResult {}
}
