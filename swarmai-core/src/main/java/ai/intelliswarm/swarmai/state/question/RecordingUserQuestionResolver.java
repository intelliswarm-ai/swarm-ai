package ai.intelliswarm.swarmai.state.question;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Decorator that wraps any {@link UserQuestionResolver} and records every
 * (question, answer) pair for audit, debugging, or persistence. Useful for
 * UIs that want a side-channel view of the interaction without coupling to
 * the resolver in use, and for tests that want to assert the agent asked
 * the right things in the right order.
 *
 * <h2>Behaviour on resolver failure</h2>
 * If the wrapped resolver throws, the question is recorded with no
 * corresponding answer (the underlying exception propagates). Use
 * {@link #unansweredQuestions} to inspect those.
 */
public final class RecordingUserQuestionResolver implements UserQuestionResolver {

    private final UserQuestionResolver delegate;
    private final List<Entry> entries = Collections.synchronizedList(new ArrayList<>());

    public RecordingUserQuestionResolver(UserQuestionResolver delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public UserAnswer ask(UserQuestion question) {
        Objects.requireNonNull(question, "question");
        Entry pending = new Entry(question, null);
        entries.add(pending);
        UserAnswer answer = delegate.ask(question);
        // Replace the pending entry with the completed one — preserves order.
        synchronized (entries) {
            int idx = entries.lastIndexOf(pending);
            if (idx >= 0) {
                entries.set(idx, new Entry(question, answer));
            }
        }
        return answer;
    }

    /** Snapshot of all (question, answer?) pairs in chronological order. */
    public List<Entry> history() {
        synchronized (entries) {
            return List.copyOf(entries);
        }
    }

    /** Convenience: just the questions, in order. */
    public List<UserQuestion> askedQuestions() {
        synchronized (entries) {
            List<UserQuestion> out = new ArrayList<>(entries.size());
            for (Entry e : entries) out.add(e.question);
            return List.copyOf(out);
        }
    }

    /** Convenience: just the answers, in order; questions that errored are skipped. */
    public List<UserAnswer> receivedAnswers() {
        synchronized (entries) {
            List<UserAnswer> out = new ArrayList<>();
            for (Entry e : entries) if (e.answer != null) out.add(e.answer);
            return List.copyOf(out);
        }
    }

    /** Questions for which the wrapped resolver threw before returning an answer. */
    public List<UserQuestion> unansweredQuestions() {
        synchronized (entries) {
            List<UserQuestion> out = new ArrayList<>();
            for (Entry e : entries) if (e.answer == null) out.add(e.question);
            return List.copyOf(out);
        }
    }

    /** A single recorded interaction. {@code answer} is null if the resolver threw. */
    public record Entry(UserQuestion question, UserAnswer answer) {
        public Entry {
            Objects.requireNonNull(question, "question");
            // answer may be null
        }
    }
}
