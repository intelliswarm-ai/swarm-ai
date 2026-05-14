package ai.intelliswarm.swarmai.agent.llm;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.Objects;

/**
 * Extended-thinking configuration. Maps to Anthropic's {@code thinking} request
 * object. {@link #budgetTokens()} is the upper bound on tokens spent inside the
 * pre-response reasoning pass; the model may spend less.
 *
 * <p>Sensible budgets by task:
 * <ul>
 *   <li>1024–2048 — quick clarifications, short tool plans</li>
 *   <li>4096–8192 — code review, document synthesis, single-step finance</li>
 *   <li>16384–32768 — DCF/LBO building, multi-document reconciliation, hard SWE tasks</li>
 * </ul>
 *
 * <p>{@link #DISABLED} is the no-thinking sentinel.
 */
@PublicApi(since = "1.0.24")
public record ThinkingBudget(ThinkingMode mode, int budgetTokens, ThinkingDisplay display) {

    public static final ThinkingBudget DISABLED =
            new ThinkingBudget(ThinkingMode.DISABLED, 0, ThinkingDisplay.OMITTED);

    public ThinkingBudget {
        Objects.requireNonNull(mode, "mode must not be null");
        Objects.requireNonNull(display, "display must not be null");
        if (mode != ThinkingMode.DISABLED && budgetTokens < 1024) {
            throw new IllegalArgumentException(
                    "budgetTokens must be at least 1024 when thinking is enabled (got " + budgetTokens + ")");
        }
        if (mode == ThinkingMode.DISABLED && budgetTokens != 0) {
            throw new IllegalArgumentException("budgetTokens must be 0 when mode is DISABLED");
        }
    }

    public boolean isEnabled() {
        return mode != ThinkingMode.DISABLED;
    }

    public static ThinkingBudget enabled(int budgetTokens) {
        return new ThinkingBudget(ThinkingMode.ENABLED, budgetTokens, ThinkingDisplay.SUMMARIZED);
    }

    public static ThinkingBudget interleaved(int budgetTokens) {
        return new ThinkingBudget(ThinkingMode.INTERLEAVED, budgetTokens, ThinkingDisplay.SUMMARIZED);
    }

    public ThinkingBudget withDisplay(ThinkingDisplay newDisplay) {
        return new ThinkingBudget(mode, budgetTokens, newDisplay);
    }
}
