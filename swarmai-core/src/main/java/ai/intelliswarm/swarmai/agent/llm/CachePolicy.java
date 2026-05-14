package ai.intelliswarm.swarmai.agent.llm;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Declarative prompt-caching configuration. Maps to Anthropic's
 * {@code cache_control} mechanism. A request may carry up to four breakpoints;
 * each marks a slot in the prompt whose prefix Claude will serialise to the
 * cache for re-use on subsequent calls.
 *
 * <p>Builder example — cache the tool registry and system prompt for an hour,
 * cache the running message history every five minutes:
 *
 * <pre>{@code
 * CachePolicy policy = CachePolicy.builder()
 *     .breakpoint(CacheBreakpoint.oneHour("tools"))
 *     .breakpoint(CacheBreakpoint.oneHour("system"))
 *     .breakpoint(CacheBreakpoint.fiveMinutes("messages:last"))
 *     .build();
 * }</pre>
 *
 * <p>Implementations that don't understand a particular slot name should ignore
 * it rather than fail; this keeps policies forward-compatible across adapters
 * (Spring AI, native Anthropic SDK, Bedrock, Vertex).
 */
@PublicApi(since = "1.0.24")
public final class CachePolicy {

    /** Anthropic-enforced maximum. */
    public static final int MAX_BREAKPOINTS = 4;

    private static final CachePolicy DISABLED = new CachePolicy(List.of());

    private final List<CacheBreakpoint> breakpoints;

    private CachePolicy(List<CacheBreakpoint> breakpoints) {
        this.breakpoints = List.copyOf(breakpoints);
    }

    /** No caching. */
    public static CachePolicy disabled() {
        return DISABLED;
    }

    public boolean isEnabled() {
        return !breakpoints.isEmpty();
    }

    public List<CacheBreakpoint> breakpoints() {
        return breakpoints;
    }

    /** Find the breakpoint targeting {@code slot}, if any. */
    public java.util.Optional<CacheBreakpoint> forSlot(String slot) {
        return breakpoints.stream().filter(b -> b.slot().equals(slot)).findFirst();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<CacheBreakpoint> breakpoints = new ArrayList<>();

        public Builder breakpoint(CacheBreakpoint breakpoint) {
            Objects.requireNonNull(breakpoint, "breakpoint must not be null");
            this.breakpoints.add(breakpoint);
            return this;
        }

        public Builder breakpoints(List<CacheBreakpoint> bps) {
            Objects.requireNonNull(bps, "breakpoints must not be null");
            this.breakpoints.addAll(bps);
            return this;
        }

        public CachePolicy build() {
            if (breakpoints.size() > MAX_BREAKPOINTS) {
                throw new IllegalArgumentException(
                        "CachePolicy supports at most " + MAX_BREAKPOINTS
                                + " breakpoints (got " + breakpoints.size() + ")");
            }
            long distinctSlots = breakpoints.stream().map(CacheBreakpoint::slot).distinct().count();
            if (distinctSlots != breakpoints.size()) {
                throw new IllegalArgumentException("Duplicate cache slot — each slot may appear at most once");
            }
            return new CachePolicy(Collections.unmodifiableList(new ArrayList<>(breakpoints)));
        }
    }
}
