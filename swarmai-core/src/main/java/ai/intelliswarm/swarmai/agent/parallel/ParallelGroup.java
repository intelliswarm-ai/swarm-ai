package ai.intelliswarm.swarmai.agent.parallel;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.List;
import java.util.Objects;

/**
 * A group of tool calls that may run concurrently with one another. Groups are
 * ordered: every call in group {@code N} must complete before any call in
 * group {@code N+1} begins.
 *
 * @param calls the calls in this group; safe to dispatch in parallel
 */
@PublicApi(since = "1.0.24")
public record ParallelGroup(List<PendingToolCall> calls) {

    public ParallelGroup {
        Objects.requireNonNull(calls, "calls must not be null");
        if (calls.isEmpty()) {
            throw new IllegalArgumentException("group must not be empty");
        }
        calls = List.copyOf(calls);
    }

    public int size() {
        return calls.size();
    }

    public boolean isSingleton() {
        return calls.size() == 1;
    }
}
