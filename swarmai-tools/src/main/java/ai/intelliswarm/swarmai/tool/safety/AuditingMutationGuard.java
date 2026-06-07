package ai.intelliswarm.swarmai.tool.safety;

import java.util.Objects;

/**
 * Decorator that writes the request and the resulting decision to an
 * {@link AuditLogger} every time {@link #check(MutationPlan)} is called.
 * Wrap any other {@link MutationGuard} (e.g. {@link SupervisedMutationGuard})
 * with this and tools get audited transparently.
 */
public class AuditingMutationGuard implements MutationGuard {

    private final MutationGuard delegate;
    private final AuditLogger auditLogger;

    public AuditingMutationGuard(MutationGuard delegate, AuditLogger auditLogger) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.auditLogger = Objects.requireNonNull(auditLogger, "auditLogger");
    }

    @Override
    public Decision check(MutationPlan plan) {
        auditLogger.logRequest(plan);
        Decision decision = delegate.check(plan);
        auditLogger.logDecision(plan, decision);
        return decision;
    }
}
