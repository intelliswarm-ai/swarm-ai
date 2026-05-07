package ai.intelliswarm.swarmai.state.plan;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Approval policy that gates the {@link BanditApprovalPolicy} on
 * <em>confidence per bucket</em>. While a bucket is unready (insufficient
 * observations or wide credible interval), the gate defers to a configured
 * {@link #fallbackPolicy} — typically something conservative like
 * {@link PlanApprovalPolicy#alwaysHuman()} or the static rule chain. Once the
 * bucket has accumulated enough evidence, the gate lets the bandit's verdict
 * stand.
 *
 * <p>This is the "promotion ceremony" the showcase evaluation called out as
 * missing — a principled answer to <em>when</em> the bandit gets to vote.
 *
 * <p>Two criteria, both required:
 * <ol>
 *   <li><b>Min observations</b>: at least {@link #minObservations} updates
 *       have been recorded for this bucket. Stops the gate from acting on a
 *       sample size of one or two.</li>
 *   <li><b>Max CI half-width</b>: the 95% Wald confidence interval around
 *       the bucket's mean is narrower than {@link #maxCiHalfWidth}. Stops the
 *       gate from acting while the posterior is still wide.</li>
 * </ol>
 *
 * <p>Wald is a normal-approximation interval: half-width ≈ 1.96·sqrt(Var(Beta(α, β))).
 * It's not exact for small samples but is the standard cheap-and-good-enough
 * choice for promotion criteria. Buckets with extreme means (very near 0 or 1)
 * have artificially narrow Wald intervals; the min-observations check is the
 * second guard against that.
 */
public final class BanditPromotionGate implements PlanApprovalPolicy {

    /** Default: 30 observations before a bucket may graduate. */
    public static final int DEFAULT_MIN_OBSERVATIONS = 30;

    /** Default: 95% CI half-width must be ≤ 0.10 (i.e., ±10%). */
    public static final double DEFAULT_MAX_CI_HALF_WIDTH = 0.10;

    /** z-score for a 95% normal-approximation interval. */
    private static final double Z_95 = 1.96;

    private final BanditApprovalPolicy bandit;
    private final PlanApprovalPolicy fallbackPolicy;
    private final int minObservations;
    private final double maxCiHalfWidth;

    public BanditPromotionGate(BanditApprovalPolicy bandit,
                                PlanApprovalPolicy fallbackPolicy) {
        this(bandit, fallbackPolicy, DEFAULT_MIN_OBSERVATIONS, DEFAULT_MAX_CI_HALF_WIDTH);
    }

    public BanditPromotionGate(BanditApprovalPolicy bandit,
                                PlanApprovalPolicy fallbackPolicy,
                                int minObservations,
                                double maxCiHalfWidth) {
        this.bandit = Objects.requireNonNull(bandit, "bandit");
        this.fallbackPolicy = Objects.requireNonNull(fallbackPolicy, "fallbackPolicy");
        if (minObservations < 0) {
            throw new IllegalArgumentException("minObservations must be >= 0; got " + minObservations);
        }
        if (!(maxCiHalfWidth > 0.0) || !(maxCiHalfWidth < 1.0)) {
            throw new IllegalArgumentException(
                    "maxCiHalfWidth must be in (0, 1); got " + maxCiHalfWidth);
        }
        this.minObservations = minObservations;
        this.maxCiHalfWidth = maxCiHalfWidth;
    }

    public int minObservations() { return minObservations; }
    public double maxCiHalfWidth() { return maxCiHalfWidth; }

    @Override
    public Decision decide(EvolvingPlan currentPlan, PlanEntry incoming) {
        Objects.requireNonNull(currentPlan, "currentPlan");
        Objects.requireNonNull(incoming, "incoming");
        String key = bandit.keyFor(incoming);
        if (isReady(key)) {
            return bandit.decide(currentPlan, incoming);
        }
        return fallbackPolicy.decide(currentPlan, incoming);
    }

    /**
     * True when {@code bucketKey} has accumulated enough evidence to graduate
     * — both observation-count and confidence-width criteria are met.
     * Returns false for buckets the bandit has never seen.
     */
    public boolean isReady(String bucketKey) {
        Objects.requireNonNull(bucketKey, "bucketKey");
        var stateOpt = bandit.bucketState(bucketKey);
        if (stateOpt.isEmpty()) return false;

        BanditState.BucketState state = stateOpt.get();
        if (state.observationCount() < minObservations) return false;

        double halfWidth = halfWidth(state.alphaAuto(), state.betaAuto());
        return halfWidth <= maxCiHalfWidth;
    }

    /** Returns the set of bucket keys currently ready to graduate. */
    public Set<String> readyBuckets() {
        Set<String> ready = new LinkedHashSet<>();
        for (String key : bandit.bucketKeys()) {
            if (isReady(key)) ready.add(key);
        }
        return Set.copyOf(ready);
    }

    /**
     * 95% Wald half-width for Beta(α, β):
     * {@code 1.96 · sqrt(αβ / ((α+β)² · (α+β+1)))}.
     * Exposed publicly so monitoring / dashboard code can compute the same
     * confidence intervals the gate uses internally.
     */
    public static double halfWidth(double alpha, double beta) {
        double sum = alpha + beta;
        double variance = (alpha * beta) / (sum * sum * (sum + 1.0));
        return Z_95 * Math.sqrt(variance);
    }
}
