package ai.intelliswarm.swarmai.agent.llm;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.Objects;

/**
 * Extended-thinking configuration. Maps to Anthropic's {@code thinking} request
 * object plus, for adaptive-mode models, the {@code output_config} request
 * field. The shape varies by mode:
 *
 * <ul>
 *   <li>{@link ThinkingMode#DISABLED} — skip thinking entirely.</li>
 *   <li>{@link ThinkingMode#ENABLED} / {@link ThinkingMode#INTERLEAVED} —
 *       caller sets an explicit token budget. {@link #budgetTokens()} is
 *       the upper bound; the model may spend less. {@link #effort()} is
 *       {@code null}. Used by Sonnet 4.5, Opus 4.1, and earlier models.</li>
 *   <li>{@link ThinkingMode#ADAPTIVE} — caller sets an effort tier
 *       ({@link ThinkingEffort}); the model picks its own budget within
 *       that ceiling. {@link #budgetTokens()} is {@code 0}, {@link #effort()}
 *       is non-null. Required by Opus 4.7 and other newer adaptive-only models.</li>
 * </ul>
 *
 * <p>Sensible budgets by task (ENABLED / INTERLEAVED modes):
 * <ul>
 *   <li>1024–2048 — quick clarifications, short tool plans</li>
 *   <li>4096–8192 — code review, document synthesis, single-step finance</li>
 *   <li>16384–32768 — DCF/LBO building, multi-document reconciliation, hard SWE tasks</li>
 * </ul>
 *
 * <p>{@link #DISABLED} is the no-thinking sentinel.
 */
@PublicApi(since = "1.0.24")
public record ThinkingBudget(
        ThinkingMode mode, int budgetTokens, ThinkingDisplay display, ThinkingEffort effort) {

    public static final ThinkingBudget DISABLED =
            new ThinkingBudget(ThinkingMode.DISABLED, 0, ThinkingDisplay.OMITTED, null);

    public ThinkingBudget {
        Objects.requireNonNull(mode, "mode must not be null");
        Objects.requireNonNull(display, "display must not be null");
        switch (mode) {
            case DISABLED -> {
                if (budgetTokens != 0) {
                    throw new IllegalArgumentException(
                            "budgetTokens must be 0 when mode is DISABLED");
                }
                if (effort != null) {
                    throw new IllegalArgumentException(
                            "effort must be null when mode is DISABLED");
                }
            }
            case ENABLED, INTERLEAVED -> {
                if (budgetTokens < 1024) {
                    throw new IllegalArgumentException(
                            "budgetTokens must be at least 1024 when thinking is "
                                    + mode + " (got " + budgetTokens + ")");
                }
                if (effort != null) {
                    throw new IllegalArgumentException(
                            "effort must be null when mode is " + mode
                                    + "; use adaptive() to set an effort tier");
                }
            }
            case ADAPTIVE -> {
                if (budgetTokens != 0) {
                    throw new IllegalArgumentException(
                            "budgetTokens must be 0 when mode is ADAPTIVE — the model "
                                    + "picks its own budget within the effort ceiling");
                }
                Objects.requireNonNull(effort,
                        "effort must not be null when mode is ADAPTIVE");
            }
        }
    }

    /** Backwards-compatible 3-arg constructor — defaults effort to null. */
    public ThinkingBudget(ThinkingMode mode, int budgetTokens, ThinkingDisplay display) {
        this(mode, budgetTokens, display, null);
    }

    public boolean isEnabled() {
        return mode != ThinkingMode.DISABLED;
    }

    public static ThinkingBudget enabled(int budgetTokens) {
        return new ThinkingBudget(ThinkingMode.ENABLED, budgetTokens, ThinkingDisplay.SUMMARIZED, null);
    }

    public static ThinkingBudget interleaved(int budgetTokens) {
        return new ThinkingBudget(ThinkingMode.INTERLEAVED, budgetTokens, ThinkingDisplay.SUMMARIZED, null);
    }

    /**
     * Adaptive thinking — the model picks its own budget within the effort
     * ceiling. Required by Claude Opus 4.7 and other newer adaptive-only
     * models. Display defaults to {@link ThinkingDisplay#SUMMARIZED}.
     */
    public static ThinkingBudget adaptive(ThinkingEffort effort) {
        return new ThinkingBudget(ThinkingMode.ADAPTIVE, 0, ThinkingDisplay.SUMMARIZED, effort);
    }

    public ThinkingBudget withDisplay(ThinkingDisplay newDisplay) {
        return new ThinkingBudget(mode, budgetTokens, newDisplay, effort);
    }
}
