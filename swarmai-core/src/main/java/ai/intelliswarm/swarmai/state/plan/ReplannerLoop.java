package ai.intelliswarm.swarmai.state.plan;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Glues a {@link Replanner}, a {@link ReplanTriggerPolicy}, and a
 * {@link PlanChannelAccessor}. Subscribes to channel transitions; on each
 * transition consults the trigger; when fired, calls the replanner with the
 * current plan + a {@link ReplanContext} from the configured supplier; writes
 * the returned amendments back to the channel.
 *
 * <p>This is the runtime heart of the dynamic-plan loop. With it in place,
 * the swarm's plan can <em>evolve</em> mid-run as observations accumulate —
 * which is the whole point of "evolving plan, not static workflow."
 *
 * <p>Reentrancy: the loop sets a thread-local flag while applying its own
 * amendments so the trigger doesn't fire on transitions caused by the
 * replanner itself. Without this guard, an LLM replanner that emits even one
 * amendment would loop indefinitely.
 *
 * <p>Threading: {@link #onTransition} runs on whatever thread invoked the
 * accessor's {@code update}. If the replanner is slow (e.g. an LLM call),
 * that thread blocks for the duration of the call. Callers needing async
 * replans can wrap the {@link Replanner} to dispatch on an executor and
 * return {@code Amendments.none()} immediately.
 */
public final class ReplannerLoop implements PlanChannelAccessor.PlanListener, AutoCloseable {

    /** {@code decidedBy} attribution applied to supersession transitions on the OLD entry. */
    public static final String DECIDED_BY_REPLANNER = "REPLANNER";

    /**
     * {@code decidedBy} attribution applied to an amendment when the
     * {@link #amendmentPolicy re-gating policy} auto-approves it. Mirrors the
     * {@code PlanAwareMutationGuard} convention so the bandit trainer (which
     * filters on {@code "PLAN_POLICY"} by default) treats auto-approved
     * amendments as training signal alongside auto-approved tool calls.
     */
    public static final String DECIDED_BY_POLICY = "PLAN_POLICY";

    private final PlanChannelAccessor channel;
    private final Replanner replanner;
    private final ReplanTriggerPolicy trigger;
    private final Supplier<ReplanContext> contextSupplier;

    /**
     * Optional policy applied to every amendment after it lands on the channel.
     * When {@code null} (the legacy default), amendments stay PROPOSED.
     * When set, amendments get the same gating as tool-side proposals: the
     * policy decides AUTO_APPROVE → marked APPROVED with
     * {@link #DECIDED_BY_POLICY} attribution; REQUIRES_HUMAN → left PROPOSED
     * so an operator can act on them.
     */
    private final PlanApprovalPolicy amendmentPolicy;

    /**
     * Per-thread flag set while the loop is writing its own amendments;
     * prevents recursion when those writes trigger more transitions.
     */
    private final ThreadLocal<Boolean> applyingAmendments = ThreadLocal.withInitial(() -> false);

    private final AtomicBoolean closed = new AtomicBoolean(false);

    public ReplannerLoop(PlanChannelAccessor channel,
                          Replanner replanner,
                          ReplanTriggerPolicy trigger,
                          Supplier<ReplanContext> contextSupplier) {
        this(channel, replanner, trigger, contextSupplier, /*amendmentPolicy*/ null);
    }

    /**
     * @param amendmentPolicy optional re-gate for amendment entries. When set,
     *                        the loop runs each amendment through this policy
     *                        after appending; AUTO_APPROVE marks the entry
     *                        APPROVED, REQUIRES_HUMAN leaves it PROPOSED. Pass
     *                        {@code null} to retain the legacy "amendments
     *                        always stay PROPOSED" behaviour.
     */
    public ReplannerLoop(PlanChannelAccessor channel,
                          Replanner replanner,
                          ReplanTriggerPolicy trigger,
                          Supplier<ReplanContext> contextSupplier,
                          PlanApprovalPolicy amendmentPolicy) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.replanner = Objects.requireNonNull(replanner, "replanner");
        this.trigger = Objects.requireNonNull(trigger, "trigger");
        this.contextSupplier = Objects.requireNonNull(contextSupplier, "contextSupplier");
        this.amendmentPolicy = amendmentPolicy; // null is the legacy default
    }

    /** Subscribe to {@code channel}'s transitions. Caller should {@link #close} to detach. */
    public ReplannerLoop start() {
        channel.addListener(this);
        return this;
    }

    /** Convenience: construct + start in one call (no amendment re-gating). */
    public static ReplannerLoop start(PlanChannelAccessor channel,
                                       Replanner replanner,
                                       ReplanTriggerPolicy trigger,
                                       Supplier<ReplanContext> contextSupplier) {
        return new ReplannerLoop(channel, replanner, trigger, contextSupplier).start();
    }

    /** Convenience: construct + start with amendment re-gating. */
    public static ReplannerLoop start(PlanChannelAccessor channel,
                                       Replanner replanner,
                                       ReplanTriggerPolicy trigger,
                                       Supplier<ReplanContext> contextSupplier,
                                       PlanApprovalPolicy amendmentPolicy) {
        return new ReplannerLoop(channel, replanner, trigger, contextSupplier,
                amendmentPolicy).start();
    }

    @Override
    public void onTransition(PlanEntry previous, PlanEntry current) {
        if (closed.get()) return;
        if (applyingAmendments.get()) return; // skip our own writes

        ReplanTriggerPolicy.ReplanTriggerEvent event =
                new ReplanTriggerPolicy.ReplanTriggerEvent(previous, current);
        EvolvingPlan plan = channel.get();
        if (!trigger.shouldReplan(plan, event)) return;

        ReplanContext context = contextSupplier.get();
        if (context == null) return; // caller hasn't supplied context yet

        Replanner.Amendments amendments = replanner.amend(plan, context);
        if (amendments == null || amendments.isEmpty()) return;

        applyingAmendments.set(Boolean.TRUE);
        try {
            applyAmendments(amendments);
        } finally {
            applyingAmendments.set(Boolean.FALSE);
        }
    }

    private void applyAmendments(Replanner.Amendments amendments) {
        for (PlanEntry newEntry : amendments.newEntries()) {
            channel.append(newEntry);
            gateAmendmentEntry(newEntry);
        }
        for (Replanner.Amendments.Supersession s : amendments.supersessions()) {
            channel.update(p -> p.markStatus(s.oldId(),
                            PlanEntry.Status.SUPERSEDED,
                            "superseded by " + s.replacement().id(),
                            DECIDED_BY_REPLANNER)
                    .with(s.replacement()));
            gateAmendmentEntry(s.replacement());
        }
    }

    /**
     * Run an amendment entry through {@link #amendmentPolicy} (if configured).
     * Auto-approval transitions PROPOSED → APPROVED with
     * {@link #DECIDED_BY_POLICY} attribution; REQUIRES_HUMAN leaves the entry
     * in its current PROPOSED state for an operator to act on.
     *
     * <p>Skipped entirely when {@code amendmentPolicy} is {@code null}.
     */
    private void gateAmendmentEntry(PlanEntry entry) {
        if (amendmentPolicy == null) return;
        PlanApprovalPolicy.Decision verdict =
                amendmentPolicy.decide(channel.get(), entry);
        if (verdict == PlanApprovalPolicy.Decision.AUTO_APPROVE) {
            channel.markStatus(entry.id(),
                    PlanEntry.Status.APPROVED,
                    "policy auto-approved amendment",
                    DECIDED_BY_POLICY);
        }
        // REQUIRES_HUMAN → leave entry in PROPOSED for human action
    }

    @Override
    public void close() {
        closed.set(true);
        // Best-effort detach; underlying accessor may not support removal.
        if (channel instanceof InMemoryPlanChannelAccessor in) {
            in.removeListener(this);
        } else if (channel instanceof AgentStateBackedPlanChannelAccessor as) {
            as.removeListener(this);
        }
    }
}
