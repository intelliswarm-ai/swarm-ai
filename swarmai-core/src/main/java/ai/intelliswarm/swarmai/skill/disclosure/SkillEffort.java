package ai.intelliswarm.swarmai.skill.disclosure;

import ai.intelliswarm.swarmai.api.PublicApi;

/**
 * Skill-declared compute envelope. Maps to a thinking-budget tier when running
 * with the {@link ai.intelliswarm.swarmai.llm.anthropic.AnthropicNativeClient}
 * adapter, and to a temperature/max-tokens recipe when running on Spring AI.
 *
 * <ul>
 *   <li>{@link #MINIMAL} — short structured tasks: format conversion, classification.
 *       Thinking disabled.</li>
 *   <li>{@link #STANDARD} — typical analytical work: code review, comp lookups.
 *       Thinking budget ~4K tokens.</li>
 *   <li>{@link #EXTENDED} — hard multi-step reasoning: DCF/LBO build,
 *       reconciliation, hard SWE tasks. Thinking budget ~16K–32K tokens.</li>
 * </ul>
 */
@PublicApi(since = "1.0.24")
public enum SkillEffort {
    MINIMAL,
    STANDARD,
    EXTENDED
}
