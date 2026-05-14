package ai.intelliswarm.swarmai.agent.llm;

import ai.intelliswarm.swarmai.api.PublicApi;

/**
 * Effort level for adaptive extended thinking. Maps to Anthropic's
 * {@code output_config.effort} request field, which Claude Opus 4.7 and
 * other adaptive-thinking models use in place of the legacy
 * {@code thinking.budget_tokens} field.
 *
 * <p>The model picks its own thinking budget within the effort ceiling
 * — you tell it how hard to try, not how many tokens to burn.
 *
 * <p>Rough guidance:
 * <ul>
 *   <li>{@link #LOW} — quick clarifications, simple tool plans</li>
 *   <li>{@link #MEDIUM} — default; code review, document synthesis,
 *       single-step finance math</li>
 *   <li>{@link #HIGH} — DCF / LBO building, multi-document reconciliation,
 *       multi-step debugging</li>
 *   <li>{@link #XHIGH} — research-grade analysis, hard SWE refactors</li>
 *   <li>{@link #MAX} — the model goes as hard as it can; reserve for
 *       genuinely difficult problems where output quality matters more
 *       than latency</li>
 * </ul>
 */
@PublicApi(since = "1.0.24")
public enum ThinkingEffort {
    LOW,
    MEDIUM,
    HIGH,
    XHIGH,
    MAX
}
