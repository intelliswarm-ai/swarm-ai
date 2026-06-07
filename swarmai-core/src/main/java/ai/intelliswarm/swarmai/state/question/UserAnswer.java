package ai.intelliswarm.swarmai.state.question;

import java.time.Instant;
import java.util.Objects;

/**
 * The user's response to a {@link UserQuestion}. Exactly one of
 * {@code selectedOption} (a choice from {@link UserQuestion#options})
 * or {@code freeformAnswer} (typed text) is non-null.
 *
 * <p>Resolvers are responsible for ensuring this invariant — see
 * {@link #ofOption} and {@link #ofFreeform} for safe construction.
 *
 * @param questionId      id of the {@link UserQuestion} this answers
 * @param selectedOption  the chosen option, or null if freeform
 * @param freeformAnswer  the typed answer, or null if an option was selected
 * @param answeredAt      when the user answered
 */
public record UserAnswer(
        String questionId,
        String selectedOption,
        String freeformAnswer,
        Instant answeredAt) {

    public UserAnswer {
        Objects.requireNonNull(questionId, "questionId");
        boolean hasOption = selectedOption != null;
        boolean hasFreeform = freeformAnswer != null;
        if (hasOption == hasFreeform) {
            throw new IllegalArgumentException(
                    "exactly one of selectedOption / freeformAnswer must be non-null "
                            + "(got selectedOption=" + selectedOption
                            + ", freeformAnswer=" + freeformAnswer + ")");
        }
        if (hasOption && selectedOption.isBlank()) {
            throw new IllegalArgumentException("selectedOption must not be blank");
        }
        if (hasFreeform && freeformAnswer.isBlank()) {
            throw new IllegalArgumentException("freeformAnswer must not be blank");
        }
        if (answeredAt == null) answeredAt = Instant.now();
    }

    /** Construct an answer that selected one of the predefined options. */
    public static UserAnswer ofOption(String questionId, String option) {
        return new UserAnswer(questionId, option, null, Instant.now());
    }

    /** Construct a freeform answer. */
    public static UserAnswer ofFreeform(String questionId, String text) {
        return new UserAnswer(questionId, null, text, Instant.now());
    }

    /** True if the user typed a custom answer instead of selecting an option. */
    public boolean isFreeform() {
        return freeformAnswer != null;
    }

    /** The effective answer text — either the selected option or the freeform input. */
    public String value() {
        return selectedOption != null ? selectedOption : freeformAnswer;
    }
}
