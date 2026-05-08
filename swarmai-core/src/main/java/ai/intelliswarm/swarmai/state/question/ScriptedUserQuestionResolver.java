package ai.intelliswarm.swarmai.state.question;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Deterministic {@link UserQuestionResolver} that returns pre-programmed
 * answers in FIFO order. Use it in tests and automated examples where
 * stdin-based interactivity isn't viable.
 *
 * <h2>Answer matching</h2>
 * Each scripted answer is a string. The resolver tries to match it as an
 * option (exact, case-insensitive); if {@link UserQuestion#options()} is
 * empty or no option matches and {@link UserQuestion#allowFreeform()} is
 * true, it's returned as a freeform answer. Otherwise the resolver throws
 * {@link UserQuestionException}.
 */
public final class ScriptedUserQuestionResolver implements UserQuestionResolver {

    private final Queue<String> scriptedAnswers;
    private final List<UserQuestion> askedHistory = Collections.synchronizedList(new ArrayList<>());

    public ScriptedUserQuestionResolver(String... answers) {
        this(Arrays.asList(answers));
    }

    public ScriptedUserQuestionResolver(List<String> answers) {
        Objects.requireNonNull(answers, "answers");
        this.scriptedAnswers = new ConcurrentLinkedQueue<>(answers);
    }

    /** Append more answers to the script after construction. */
    public void enqueue(String answer) {
        scriptedAnswers.add(Objects.requireNonNull(answer, "answer"));
    }

    /** Snapshot of every question asked so far, in order. */
    public List<UserQuestion> askedHistory() {
        synchronized (askedHistory) {
            return List.copyOf(askedHistory);
        }
    }

    /** Number of scripted answers still available. */
    public int remaining() {
        return scriptedAnswers.size();
    }

    @Override
    public UserAnswer ask(UserQuestion question) {
        Objects.requireNonNull(question, "question");
        String scripted = scriptedAnswers.poll();
        if (scripted == null) {
            throw new UserQuestionException(
                    "no scripted answer available for question: " + question.question());
        }
        askedHistory.add(question);

        // Try option match first
        for (String opt : question.options()) {
            if (opt.equalsIgnoreCase(scripted)) {
                return UserAnswer.ofOption(question.id(), opt);
            }
        }

        // No option match — freeform if allowed
        if (question.options().isEmpty() || question.allowFreeform()) {
            return UserAnswer.ofFreeform(question.id(), scripted);
        }

        throw new UserQuestionException(
                "scripted answer '" + scripted + "' did not match any option of: "
                        + question.options() + " (and freeform is not allowed)");
    }
}
