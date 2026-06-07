package ai.intelliswarm.swarmai.state.question;

/**
 * SPI for presenting a {@link UserQuestion} to the user and returning their
 * {@link UserAnswer}. Implementations decide how to surface the question
 * (stdin/stdout, REST callback, web UI, IDE plugin, mock for tests) and how
 * to enforce the invariants of the question's option set.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #ask} must block until an answer is available, or throw if it
 *       cannot get one.</li>
 *   <li>The returned {@link UserAnswer#questionId()} must equal
 *       {@link UserQuestion#id()}.</li>
 *   <li>If {@link UserQuestion#isFreeformOnly()} is true, the answer must be
 *       freeform.</li>
 *   <li>If {@link UserQuestion#options()} is non-empty and
 *       {@link UserQuestion#allowFreeform()} is false, the answer must select
 *       one of those options.</li>
 *   <li>Implementations may throw {@link UserQuestionException} for I/O,
 *       cancellation, or timeout failures.</li>
 * </ul>
 */
@FunctionalInterface
public interface UserQuestionResolver {

    UserAnswer ask(UserQuestion question);

    /**
     * Thrown when a resolver cannot obtain an answer (cancelled, timed out,
     * I/O error). Unchecked because the agent's tool-call path treats it as
     * a tool failure.
     */
    class UserQuestionException extends RuntimeException {
        public UserQuestionException(String message) { super(message); }
        public UserQuestionException(String message, Throwable cause) { super(message, cause); }
    }
}
