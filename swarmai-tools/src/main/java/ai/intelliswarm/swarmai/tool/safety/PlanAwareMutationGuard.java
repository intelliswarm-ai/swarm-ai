package ai.intelliswarm.swarmai.tool.safety;

import ai.intelliswarm.swarmai.state.plan.PlanApprovalPolicy;
import ai.intelliswarm.swarmai.state.plan.PlanChannelAccessor;
import ai.intelliswarm.swarmai.state.plan.PlanEntry;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mutation guard that participates in the swarm's evolving plan: every
 * {@link #check(MutationPlan)} call appends a {@link PlanEntry} to the channel,
 * consults a configured {@link PlanApprovalPolicy}, and either auto-approves
 * (marks the entry APPROVED, returns approve) or routes to the wrapped
 * {@link MutationGuard} (marks APPROVED or REJECTED based on its decision).
 *
 * <p>Decorator order recommendation (outermost → innermost):
 * <pre>
 * KillSwitch → Audit → PlanAware → Supervised
 * </pre>
 * Kill-switch still silences everything; audit still records every check;
 * PlanAware decides whether to invoke the human gate; Supervised is the
 * fallback human gate when policy says human is required.
 *
 * <p>Decision attribution in returned {@link Decision}:
 * <ul>
 *   <li>{@link #DECIDED_BY_POLICY} — policy auto-approved without human review</li>
 *   <li>(delegate's value) — human (or downstream policy) made the call</li>
 * </ul>
 *
 * <p>Each approved {@link Decision} carries the entry's id as its
 * {@code correlationId}. Tools that want to surface execution outcomes back
 * into the channel call {@link #observe} with that id after their operation
 * completes:
 * <ul>
 *   <li>{@code observe(id, true, ...)} → entry transitions APPROVED → EXECUTED.</li>
 *   <li>{@code observe(id, false, ...)} → entry transitions APPROVED → REJECTED.</li>
 * </ul>
 * Observe calls for unknown correlation ids (e.g. ids whose entry was already
 * observed once, or that came from a different guard) are silently ignored —
 * tools can call {@link #observe} unconditionally regardless of which guard
 * they were configured with.
 */
public class PlanAwareMutationGuard implements MutationGuard {

    /** Sentinel attribution placed in {@link Decision#decidedBy()} when the policy auto-approved. */
    public static final String DECIDED_BY_POLICY = "PLAN_POLICY";

    private final MutationGuard humanDelegate;
    private final PlanChannelAccessor channel;
    private final PlanApprovalPolicy policy;

    /**
     * Tracks which entry ids this guard issued a Decision for and whose
     * outcome has not yet been observed. {@link #observe} consults this set
     * to filter out unknown ids (so calls from unrelated guard chains are
     * harmless) and to make double-observe a no-op (only the first wins).
     */
    private final Set<String> pendingCorrelationIds = ConcurrentHashMap.newKeySet();

    /**
     * @param humanDelegate guard to consult when the policy demands human
     *                      review (typically a {@code SupervisedMutationGuard})
     * @param channel       accessor for the plan channel of this session
     * @param policy        policy that decides AUTO_APPROVE vs REQUIRES_HUMAN
     */
    public PlanAwareMutationGuard(MutationGuard humanDelegate,
                                   PlanChannelAccessor channel,
                                   PlanApprovalPolicy policy) {
        this.humanDelegate = Objects.requireNonNull(humanDelegate, "humanDelegate");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    public Decision check(MutationPlan plan) {
        Objects.requireNonNull(plan, "plan");

        // Append the proposal to the channel.
        PlanEntry proposed = MutationPlanAdapter.toEntry(plan);
        channel.append(proposed);
        String correlationId = proposed.id();

        // Consult the policy against the plan as it now stands.
        PlanApprovalPolicy.Decision policyDecision =
                policy.decide(channel.get(), proposed);

        if (policyDecision == PlanApprovalPolicy.Decision.AUTO_APPROVE) {
            channel.markStatus(correlationId, PlanEntry.Status.APPROVED,
                    "policy auto-approved", DECIDED_BY_POLICY);
            pendingCorrelationIds.add(correlationId);
            return Decision.approve(DECIDED_BY_POLICY,
                    "policy auto-approved (entry " + correlationId + ")",
                    correlationId);
        }

        // REQUIRES_HUMAN — defer to the wrapped guard and reflect its verdict
        // back into the channel.
        Decision humanDecision = humanDelegate.check(plan);
        String humanReason = humanDecision.decidedBy()
                + (humanDecision.reason().isEmpty() ? "" : ": " + humanDecision.reason());
        if (humanDecision.approved()) {
            channel.markStatus(correlationId, PlanEntry.Status.APPROVED,
                    humanReason, humanDecision.decidedBy());
            pendingCorrelationIds.add(correlationId);
            return humanDecision.withCorrelationId(correlationId);
        }

        // Rejected at the human gate — terminal; no observe() expected.
        channel.markStatus(correlationId, PlanEntry.Status.REJECTED,
                humanReason, humanDecision.decidedBy());
        return humanDecision.withCorrelationId(correlationId);
    }

    @Override
    public void observe(String correlationId, boolean success, String reason) {
        if (correlationId == null) return;
        // Atomically remove — also gives us double-observe protection.
        if (!pendingCorrelationIds.remove(correlationId)) return;

        PlanEntry.Status target = success ? PlanEntry.Status.EXECUTED : PlanEntry.Status.REJECTED;
        String resolvedReason = success
                ? (reason == null || reason.isBlank() ? "tool reported success" : reason)
                : ("execution failed" + (reason == null || reason.isBlank() ? "" : ": " + reason));
        // Carry forward the prior decidedBy — execution outcome is attributed to
        // whoever approved the entry (typically PLAN_POLICY for auto-approve).
        // The channel's 3-arg markStatus preserves the existing decidedBy.
        channel.markStatus(correlationId, target, resolvedReason);
    }
}
