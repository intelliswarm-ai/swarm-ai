package ai.intelliswarm.swarmai.state.plan;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Serializable snapshot of a {@link BanditApprovalPolicy}'s per-bucket
 * Thompson Sampling state. Each bucket is a 2-action model
 * (action 0 = "auto-approve was right", action 1 = "require-human was right");
 * a snapshot records both arms' Beta(α, β) parameters.
 *
 * <p>Round-trip via {@link BanditApprovalPolicy#snapshot()} and
 * {@link BanditApprovalPolicy#restore(BanditState)}. Pure data — Jackson
 * serialises this directly without custom modules.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BanditState(Map<String, BucketState> buckets) {

    public BanditState {
        // Defensive copy that preserves insertion order — useful for stable
        // serialisation output and predictable display in tools/UIs.
        buckets = buckets == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(buckets));
    }

    public static BanditState empty() {
        return new BanditState(Map.of());
    }

    @JsonIgnore
    public boolean isEmpty() {
        return buckets.isEmpty();
    }

    @JsonIgnore
    public int bucketCount() {
        return buckets.size();
    }

    /**
     * Per-bucket state. The four fields capture the two Beta arms of the
     * underlying {@code ThompsonSampling(numActions=2)}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BucketState(
            double alphaAuto, double betaAuto,
            double alphaHuman, double betaHuman) {

        public BucketState {
            requirePositive(alphaAuto, "alphaAuto");
            requirePositive(betaAuto, "betaAuto");
            requirePositive(alphaHuman, "alphaHuman");
            requirePositive(betaHuman, "betaHuman");
        }

        /** Total observations recorded in this bucket. */
        @JsonIgnore
        public long observationCount() {
            // alpha + beta - 2 prior pseudo-counts on either arm = number of updates.
            return Math.round(Math.max(0.0, alphaAuto + betaAuto - 2.0));
        }

        /** Point estimate of P(auto-approval was correct) in this bucket. */
        @JsonIgnore
        public double meanAuto() {
            return alphaAuto / (alphaAuto + betaAuto);
        }

        private static void requirePositive(double v, String name) {
            if (!(v > 0.0)) {
                throw new IllegalArgumentException(name + " must be > 0; got " + v);
            }
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<String, BucketState> buckets = new LinkedHashMap<>();

        public Builder bucket(String key, BucketState state) {
            this.buckets.put(Objects.requireNonNull(key, "key"),
                    Objects.requireNonNull(state, "state"));
            return this;
        }

        public Builder bucket(String key,
                              double alphaAuto, double betaAuto,
                              double alphaHuman, double betaHuman) {
            return bucket(key, new BucketState(alphaAuto, betaAuto, alphaHuman, betaHuman));
        }

        public BanditState build() {
            return new BanditState(Collections.unmodifiableMap(new LinkedHashMap<>(buckets)));
        }
    }
}
