package ai.intelliswarm.swarmai.state.plan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Inputs handed to a {@link Replanner} alongside the current plan: the
 * original goal driving the workflow, recent observations to react to, and
 * arbitrary metadata the swarm has attached.
 *
 * <p>The replanner uses this context to decide whether and how to amend the
 * plan. Observations are domain-specific strings — tool outputs, evaluation
 * scores, error messages, operator notes — collected by the swarm runtime
 * since the last replan. Implementations should treat them as opaque text
 * and feed them into whatever reasoning the replanner does.
 *
 * <p>Constructed via the builder; immutable.
 */
public final class ReplanContext {

    private final String goal;
    private final List<String> observations;
    private final Map<String, Object> metadata;

    private ReplanContext(String goal,
                          List<String> observations,
                          Map<String, Object> metadata) {
        this.goal = goal;
        this.observations = observations;
        this.metadata = metadata;
    }

    /** The original goal of the workflow; never null, may be empty. */
    public String goal() {
        return goal;
    }

    /** Observations gathered since the last replan; never null, may be empty. */
    public List<String> observations() {
        return observations;
    }

    /** Free-form metadata for replanner-specific extensions; never null, may be empty. */
    public Map<String, Object> metadata() {
        return metadata;
    }

    public static ReplanContext of(String goal) {
        return builder().goal(goal).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String goal = "";
        private final java.util.List<String> observations = new java.util.ArrayList<>();
        private final Map<String, Object> metadata = new LinkedHashMap<>();

        public Builder goal(String goal) {
            this.goal = goal != null ? goal : "";
            return this;
        }

        public Builder addObservation(String observation) {
            if (observation != null && !observation.isBlank()) {
                this.observations.add(observation);
            }
            return this;
        }

        public Builder observations(List<String> observations) {
            if (observations != null) {
                for (String o : observations) addObservation(o);
            }
            return this;
        }

        public Builder metadata(String key, Object value) {
            this.metadata.put(Objects.requireNonNull(key, "key"), value);
            return this;
        }

        public Builder metadata(Map<String, Object> m) {
            if (m != null) this.metadata.putAll(m);
            return this;
        }

        public ReplanContext build() {
            return new ReplanContext(
                    goal,
                    List.copyOf(observations),
                    Map.copyOf(metadata));
        }
    }
}
