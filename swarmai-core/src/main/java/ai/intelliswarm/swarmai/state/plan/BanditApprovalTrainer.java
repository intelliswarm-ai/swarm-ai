package ai.intelliswarm.swarmai.state.plan;

import java.util.Objects;

/**
 * Listens for plan-entry transitions and feeds rewards to a
 * {@link BanditApprovalPolicy}. Subscribes to a {@link PlanChannelAccessor}'s
 * listener stream and translates observed transitions into bandit updates.
 *
 * <p>Reward signals:
 * <ul>
 *   <li>{@code APPROVED → REJECTED} — auto-approved entry was overridden or
 *       its execution failed. Reward {@code -1.0}.</li>
 *   <li>{@code APPROVED → EXECUTED} — auto-approved entry ran successfully.
 *       Reward {@code +1.0}.</li>
 *   <li>{@code APPROVED → SUPERSEDED} — replanner replaced the entry before
 *       it executed. Treated as neutral; no reward.</li>
 * </ul>
 *
 * <p><b>decidedBy filter.</b> The trainer only scores transitions whose prior
 * APPROVED state was attributed to {@link #scoredDecidedBy} — typically
 * {@code "PLAN_POLICY"}, the value the {@code PlanAwareMutationGuard} emits on
 * its own auto-approvals. Human-approved entries are skipped: the bandit
 * doesn't deserve credit for decisions it didn't make. To train on every
 * transition regardless of attribution (looser but faster-converging), pass
 * {@code null} as {@code scoredDecidedBy} or use {@link #attachUnfiltered}.
 *
 * <p>Threading: {@link #onTransition} runs on whatever thread the accessor's
 * {@code update} call ran on. The wrapped {@link BanditApprovalPolicy} is
 * thread-safe via its underlying ThompsonSampling, so no extra synchronisation
 * is needed.
 */
public final class BanditApprovalTrainer implements PlanChannelAccessor.PlanListener {

    /** Default value of the prior {@link PlanEntry#decidedBy()} that scores transitions. */
    public static final String DEFAULT_SCORED_DECIDED_BY = "PLAN_POLICY";

    private final BanditApprovalPolicy bandit;
    private final String scoredDecidedBy;

    public BanditApprovalTrainer(BanditApprovalPolicy bandit) {
        this(bandit, DEFAULT_SCORED_DECIDED_BY);
    }

    /**
     * @param bandit          policy whose buckets receive reward updates
     * @param scoredDecidedBy only transitions whose prior APPROVED was decided
     *                        by this actor are scored. {@code null} disables the
     *                        filter and trains on every transition.
     */
    public BanditApprovalTrainer(BanditApprovalPolicy bandit, String scoredDecidedBy) {
        this.bandit = Objects.requireNonNull(bandit, "bandit");
        this.scoredDecidedBy = scoredDecidedBy;
    }

    public String scoredDecidedBy() {
        return scoredDecidedBy;
    }

    /**
     * Convenience: subscribe to {@code accessor}'s transitions and return the
     * trainer (with default filter) for fluent setup.
     */
    public static BanditApprovalTrainer attach(PlanChannelAccessor accessor,
                                                BanditApprovalPolicy bandit) {
        BanditApprovalTrainer trainer = new BanditApprovalTrainer(bandit);
        accessor.addListener(trainer);
        return trainer;
    }

    /**
     * Convenience: subscribe to {@code accessor}'s transitions with no
     * decidedBy filter — every APPROVED → EXECUTED/REJECTED transition trains
     * the bandit, regardless of who decided.
     */
    public static BanditApprovalTrainer attachUnfiltered(PlanChannelAccessor accessor,
                                                          BanditApprovalPolicy bandit) {
        BanditApprovalTrainer trainer = new BanditApprovalTrainer(bandit, null);
        accessor.addListener(trainer);
        return trainer;
    }

    @Override
    public void onTransition(PlanEntry previous, PlanEntry current) {
        if (previous == null) return; // append, not transition — no reward
        if (previous.status() != PlanEntry.Status.APPROVED) return; // only score post-approval moves

        // Skip transitions decided by an actor we don't score (typically humans).
        if (scoredDecidedBy != null && !scoredDecidedBy.equals(previous.decidedBy())) {
            return;
        }

        switch (current.status()) {
            case REJECTED -> bandit.recordOutcome(current, -1.0);
            case EXECUTED -> bandit.recordOutcome(current, 1.0);
            default -> {
                // SUPERSEDED, PROPOSED, APPROVED — no signal
            }
        }
    }
}
