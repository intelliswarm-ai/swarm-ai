package ai.intelliswarm.swarmai.state.plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * {@link Supplier} of {@link ReplanContext} that auto-derives observations
 * from recent terminal transitions on a {@link PlanChannelAccessor}. Lets a
 * {@link ReplannerLoop} react to what's actually been happening on the channel
 * without the caller hand-crafting observations.
 *
 * <p>Default behaviour: walk the channel's entries from newest to oldest,
 * pick up to {@link #DEFAULT_MAX_OBSERVATIONS} entries whose status is
 * {@link PlanEntry.Status#EXECUTED} or {@link PlanEntry.Status#REJECTED}, and
 * format each via {@link #DEFAULT_FORMATTER} into a one-line observation.
 * Observations come back in chronological (oldest-first) order so the
 * replanner sees a forward narrative.
 *
 * <p>Customisation:
 * <ul>
 *   <li>{@link Builder#goal(String)} — the goal string handed to the replanner</li>
 *   <li>{@link Builder#maxObservations(int)} — cap on observation count</li>
 *   <li>{@link Builder#trackedStatuses(Set)} — which terminal statuses count
 *       (defaults to {@code EXECUTED, REJECTED})</li>
 *   <li>{@link Builder#formatter(Function)} — custom one-line formatter</li>
 *   <li>{@link Builder#metadata(String, Object)} — extra context for the
 *       replanner under {@link ReplanContext#metadata}</li>
 * </ul>
 *
 * <p>Wiring:
 * <pre>
 *   Supplier&lt;ReplanContext&gt; ctx = ChannelDerivedReplanContextSupplier.builder(channel)
 *           .goal("ship the change safely")
 *           .maxObservations(8)
 *           .build();
 *
 *   ReplannerLoop.start(channel, replanner, trigger, ctx);
 * </pre>
 */
public final class ChannelDerivedReplanContextSupplier implements Supplier<ReplanContext> {

    public static final int DEFAULT_MAX_OBSERVATIONS = 10;

    public static final Set<PlanEntry.Status> DEFAULT_TRACKED_STATUSES = EnumSet.of(
            PlanEntry.Status.EXECUTED, PlanEntry.Status.REJECTED);

    /** Default observation: one line per entry summarising status, payload type, and reason. */
    public static final Function<PlanEntry, String> DEFAULT_FORMATTER = e -> {
        StringBuilder sb = new StringBuilder();
        sb.append(e.status().name().toLowerCase()).append(' ');
        Object summary = e.metadata().get("summary");
        if (summary != null) {
            sb.append('"').append(summary).append('"');
        } else {
            sb.append(e.proposedBy());
        }
        if (e.decidedBy() != null) {
            sb.append(" (by ").append(e.decidedBy()).append(')');
        }
        if (e.reason() != null && !e.reason().isBlank()) {
            sb.append(" — ").append(e.reason());
        }
        return sb.toString();
    };

    private final PlanChannelAccessor channel;
    private final String goal;
    private final int maxObservations;
    private final Set<PlanEntry.Status> trackedStatuses;
    private final Function<PlanEntry, String> formatter;
    private final java.util.Map<String, Object> metadata;

    private ChannelDerivedReplanContextSupplier(PlanChannelAccessor channel,
                                                 String goal,
                                                 int maxObservations,
                                                 Set<PlanEntry.Status> trackedStatuses,
                                                 Function<PlanEntry, String> formatter,
                                                 java.util.Map<String, Object> metadata) {
        this.channel = channel;
        this.goal = goal;
        this.maxObservations = maxObservations;
        this.trackedStatuses = trackedStatuses;
        this.formatter = formatter;
        this.metadata = metadata;
    }

    @Override
    public ReplanContext get() {
        EvolvingPlan plan = channel.get();
        List<String> observations = new ArrayList<>();

        // Walk newest → oldest; collect terminal entries up to the cap.
        List<PlanEntry> entries = plan.entries();
        for (int i = entries.size() - 1; i >= 0 && observations.size() < maxObservations; i--) {
            PlanEntry e = entries.get(i);
            if (trackedStatuses.contains(e.status())) {
                observations.add(formatter.apply(e));
            }
        }

        // Reverse so the replanner reads them in time order.
        Collections.reverse(observations);

        ReplanContext.Builder builder = ReplanContext.builder().goal(goal);
        for (String obs : observations) {
            builder.addObservation(obs);
        }
        if (!metadata.isEmpty()) {
            builder.metadata(metadata);
        }
        return builder.build();
    }

    public static Builder builder(PlanChannelAccessor channel) {
        return new Builder(channel);
    }

    /** Quick-construct with a goal and the defaults. */
    public static ChannelDerivedReplanContextSupplier of(PlanChannelAccessor channel, String goal) {
        return builder(channel).goal(goal).build();
    }

    public static final class Builder {
        private final PlanChannelAccessor channel;
        private String goal = "";
        private int maxObservations = DEFAULT_MAX_OBSERVATIONS;
        private Set<PlanEntry.Status> trackedStatuses = DEFAULT_TRACKED_STATUSES;
        private Function<PlanEntry, String> formatter = DEFAULT_FORMATTER;
        private final java.util.Map<String, Object> metadata = new java.util.LinkedHashMap<>();

        private Builder(PlanChannelAccessor channel) {
            this.channel = Objects.requireNonNull(channel, "channel");
        }

        public Builder goal(String goal) {
            this.goal = goal != null ? goal : "";
            return this;
        }

        public Builder maxObservations(int maxObservations) {
            if (maxObservations < 0) {
                throw new IllegalArgumentException(
                        "maxObservations must be non-negative; got " + maxObservations);
            }
            this.maxObservations = maxObservations;
            return this;
        }

        public Builder trackedStatuses(Set<PlanEntry.Status> statuses) {
            Objects.requireNonNull(statuses, "statuses");
            this.trackedStatuses = EnumSet.copyOf(statuses);
            return this;
        }

        public Builder formatter(Function<PlanEntry, String> formatter) {
            this.formatter = Objects.requireNonNull(formatter, "formatter");
            return this;
        }

        public Builder metadata(String key, Object value) {
            this.metadata.put(Objects.requireNonNull(key, "key"), value);
            return this;
        }

        public ChannelDerivedReplanContextSupplier build() {
            return new ChannelDerivedReplanContextSupplier(
                    channel, goal, maxObservations,
                    trackedStatuses, formatter,
                    java.util.Collections.unmodifiableMap(
                            new java.util.LinkedHashMap<>(metadata)));
        }
    }
}
