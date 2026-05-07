package ai.intelliswarm.swarmai.state.plan;

import ai.intelliswarm.swarmai.rl.bandit.ThompsonSampling;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Approval policy that learns per-bucket whether auto-approval is safe, using
 * Thompson Sampling. Each bucket maintains a 2-action {@link ThompsonSampling}
 * model where:
 * <ul>
 *   <li>Action 0 — "auto-approving entries in this bucket has been the right call"</li>
 *   <li>Action 1 — "requiring a human has been the right call"</li>
 * </ul>
 * On every {@link #decide}, the bandit samples both Beta distributions and
 * picks the action with the higher sample. The natural exploration of Thompson
 * Sampling means uncertain buckets occasionally try AUTO_APPROVE and learn from
 * the outcome; well-evidenced buckets converge to the empirically-correct
 * action.
 *
 * <p><b>Safety guard — warmup window.</b> Until a bucket has accumulated
 * {@link #warmupCount} observations, the policy returns
 * {@link Decision#REQUIRES_HUMAN} unconditionally. This keeps cold buckets
 * from being randomly auto-approved on a 50/50 prior.
 *
 * <p><b>Bucket key.</b> Configurable via constructor; defaults to
 * {@code proposedBy + ":" + risk}. Buckets that share a key share a model.
 *
 * <p><b>Reward semantics.</b> Callers feed rewards via {@link #recordOutcome}:
 * <ul>
 *   <li>{@code reward > 0} — auto-approving entries like this one has been
 *       successful (executed without harm, operator endorsed, etc.).
 *       Increments action 0's Beta α; action 1's β.</li>
 *   <li>{@code reward <= 0} — auto-approving entries like this one has been
 *       wrong (execution failed, operator overrode to REJECT, etc.).
 *       Increments action 1's α; action 0's β.</li>
 * </ul>
 * Continuous rewards in {@code [-1, 1]} are quantised to a binary success at
 * {@code 0}; for fine-grained signals, call {@link #recordOutcome} multiple
 * times with binary outcomes that reflect the underlying observation rate.
 *
 * <p>Recommended composition with {@link RiskTieredApprovalPolicy} and
 * {@link DriftBudgetApprovalPolicy}:
 * <pre>
 * // Bandit as a learned veto (most conservative — all three must agree):
 * PlanApprovalPolicy.all(tier, budget, bandit)
 *
 * // Bandit as a confidence bypass (high bucket confidence overrules static rules):
 * PlanApprovalPolicy.any(PlanApprovalPolicy.all(tier, budget), bandit)
 * </pre>
 */
public final class BanditApprovalPolicy implements PlanApprovalPolicy {

    /** Default {@code proposedBy + ":" + risk} bucket key. */
    public static final Function<PlanEntry, String> DEFAULT_BUCKET_KEY =
            e -> e.proposedBy() + ":" + e.risk();

    /** Default observations required before the bucket may auto-approve. */
    public static final int DEFAULT_WARMUP_COUNT = 10;

    private final Function<PlanEntry, String> bucketKey;
    private final int warmupCount;
    private final Map<String, ThompsonSampling> buckets = new ConcurrentHashMap<>();

    public BanditApprovalPolicy() {
        this(DEFAULT_BUCKET_KEY, DEFAULT_WARMUP_COUNT);
    }

    /**
     * @param bucketKey   how to assign a {@link PlanEntry} to a learning bucket
     * @param warmupCount minimum observations a bucket must accumulate before
     *                    it can return {@link Decision#AUTO_APPROVE}; must be
     *                    {@code >= 0}
     */
    public BanditApprovalPolicy(Function<PlanEntry, String> bucketKey, int warmupCount) {
        this.bucketKey = Objects.requireNonNull(bucketKey, "bucketKey");
        if (warmupCount < 0) {
            throw new IllegalArgumentException("warmupCount must be non-negative, was " + warmupCount);
        }
        this.warmupCount = warmupCount;
    }

    public int warmupCount() {
        return warmupCount;
    }

    /** Total observations recorded for a bucket; returns 0 for unseen keys. */
    public long observationCount(String key) {
        ThompsonSampling ts = buckets.get(key);
        if (ts == null) return 0L;
        // Every recordOutcome updates both arms in lockstep, so either arm's
        // count equals the number of calls — using arm 0 avoids double-counting.
        return Math.round(ts.getCount(0));
    }

    /**
     * Estimated success probability of auto-approval for a bucket (point
     * estimate; not the sampled value). Returns {@code 0.5} for unseen keys.
     */
    public double meanSuccessRate(String key) {
        ThompsonSampling ts = buckets.get(key);
        if (ts == null) return 0.5;
        return ts.getMean(0);
    }

    @Override
    public Decision decide(EvolvingPlan currentPlan, PlanEntry incoming) {
        Objects.requireNonNull(currentPlan, "currentPlan");
        Objects.requireNonNull(incoming, "incoming");

        String key = bucketKey.apply(incoming);
        ThompsonSampling ts = buckets.computeIfAbsent(key, k -> new ThompsonSampling(2));

        long obs = Math.round(ts.getCount(0));
        if (obs < warmupCount) {
            return Decision.REQUIRES_HUMAN;
        }

        int action = ts.selectAction();
        return action == 0 ? Decision.AUTO_APPROVE : Decision.REQUIRES_HUMAN;
    }

    /**
     * Records an outcome observation for the bucket containing {@code entry}.
     *
     * @param entry  the entry whose downstream outcome is now known
     * @param reward {@code > 0} marks auto-approval as having been correct;
     *               {@code <= 0} marks it as having been wrong
     */
    public void recordOutcome(PlanEntry entry, double reward) {
        Objects.requireNonNull(entry, "entry");
        String key = bucketKey.apply(entry);
        ThompsonSampling ts = buckets.computeIfAbsent(key, k -> new ThompsonSampling(2));
        boolean autoApproveWasRight = reward > 0;
        ts.update(0, autoApproveWasRight);
        ts.update(1, !autoApproveWasRight);
    }

    /**
     * Capture the per-bucket Beta state as a serializable {@link BanditState}.
     * Intended for cross-session persistence — the returned snapshot can be
     * restored later via {@link #restore(BanditState)}.
     */
    public BanditState snapshot() {
        java.util.Map<String, BanditState.BucketState> snapshotMap = new java.util.LinkedHashMap<>();
        for (var entry : buckets.entrySet()) {
            ThompsonSampling ts = entry.getValue();
            double[] auto = ts.getParameters(0);
            double[] human = ts.getParameters(1);
            snapshotMap.put(entry.getKey(),
                    new BanditState.BucketState(auto[0], auto[1], human[0], human[1]));
        }
        return new BanditState(snapshotMap);
    }

    /**
     * Restore per-bucket Beta state from a snapshot. Existing in-memory
     * buckets that are not in {@code state} are left untouched; buckets in
     * {@code state} replace their in-memory counterparts (or are created
     * fresh).
     */
    public void restore(BanditState state) {
        Objects.requireNonNull(state, "state");
        for (var entry : state.buckets().entrySet()) {
            ThompsonSampling ts = buckets.computeIfAbsent(entry.getKey(),
                    k -> new ThompsonSampling(2));
            BanditState.BucketState bs = entry.getValue();
            ts.setParameters(0, bs.alphaAuto(), bs.betaAuto());
            ts.setParameters(1, bs.alphaHuman(), bs.betaHuman());
        }
    }

    /**
     * Bucket key for a plan entry — exposed so policies and tests can compute
     * the same key the bandit will see without having to apply
     * {@link #DEFAULT_BUCKET_KEY} themselves.
     */
    public String keyFor(PlanEntry entry) {
        return bucketKey.apply(entry);
    }

    /**
     * Per-bucket Beta state, or empty if the bucket has never been seen.
     * Useful for promotion gates and external monitoring.
     */
    public java.util.Optional<BanditState.BucketState> bucketState(String key) {
        ThompsonSampling ts = buckets.get(key);
        if (ts == null) return java.util.Optional.empty();
        double[] auto = ts.getParameters(0);
        double[] human = ts.getParameters(1);
        return java.util.Optional.of(new BanditState.BucketState(auto[0], auto[1], human[0], human[1]));
    }

    /**
     * Snapshot view of every known bucket key. Order is unspecified.
     */
    public java.util.Set<String> bucketKeys() {
        return java.util.Set.copyOf(buckets.keySet());
    }
}
