package ai.intelliswarm.swarmai.tool.safety;

import java.io.IOException;

/**
 * Helper for tools that participate in the plan-loop. Wraps the standard
 * "after the guard approved, run the operation, then notify observe()" pattern
 * so each tool method doesn't have to write the try/catch boilerplate.
 *
 * <p>Typical usage in a tool method that throws {@link IOException}:
 * <pre>
 *   MutationGuard.Decision d = mutationGuard.check(plan);
 *   if (!d.approved()) return rejected(plan, d);
 *   return GuardedOperation.runObserved(mutationGuard, d,
 *           "Created directory: " + target,
 *           () -&gt; {
 *               Files.createDirectories(target);
 *               return "Created directory: " + target;
 *           });
 * </pre>
 *
 * <p>For guards without a plan-loop decorator (i.e. the legacy chain
 * {@code KillSwitch → Audit → Supervised}), {@link MutationGuard#observe}
 * is a default no-op — the helper still works, the calls just don't
 * affect anything. So tools can call this helper unconditionally.
 *
 * <p>Both {@link IOException} and {@link RuntimeException} are caught and
 * surfaced as failure observations before being rethrown unchanged.
 */
public final class GuardedOperation {

    private GuardedOperation() { }

    /** Operation that returns a value and may throw {@link IOException}. */
    @FunctionalInterface
    public interface IoCallable<T> {
        T call() throws IOException;
    }

    /** Operation that returns no value and may throw {@link IOException}. */
    @FunctionalInterface
    public interface IoRunnable {
        void run() throws IOException;
    }

    /**
     * Run {@code op}; on success, call {@code guard.observe(id, true, successReason)};
     * on any thrown exception (checked or unchecked), call
     * {@code guard.observe(id, false, e.getMessage())} and rethrow the
     * original exception unchanged.
     */
    public static <T> T runObserved(MutationGuard guard,
                                     MutationGuard.Decision decision,
                                     String successReason,
                                     IoCallable<T> op) throws IOException {
        try {
            T result = op.call();
            guard.observe(decision.correlationId(), true, successReason);
            return result;
        } catch (IOException e) {
            guard.observe(decision.correlationId(), false, safeMessage(e));
            throw e;
        } catch (RuntimeException e) {
            guard.observe(decision.correlationId(), false, safeMessage(e));
            throw e;
        }
    }

    /** Run-no-result variant of {@link #runObserved(MutationGuard, MutationGuard.Decision, String, IoCallable)}. */
    public static void runObserved(MutationGuard guard,
                                    MutationGuard.Decision decision,
                                    String successReason,
                                    IoRunnable op) throws IOException {
        runObserved(guard, decision, successReason, () -> {
            op.run();
            return null;
        });
    }

    /**
     * Variant for operations that only throw {@link RuntimeException}, so the
     * caller doesn't have to declare {@code throws IOException}. Useful for
     * tool methods that work in terms of unchecked exceptions internally
     * (PowerShell wrappers, native calls, etc.).
     */
    public static <T> T runObservedUnchecked(MutationGuard guard,
                                              MutationGuard.Decision decision,
                                              String successReason,
                                              java.util.function.Supplier<T> op) {
        try {
            T result = op.get();
            guard.observe(decision.correlationId(), true, successReason);
            return result;
        } catch (RuntimeException e) {
            guard.observe(decision.correlationId(), false, safeMessage(e));
            throw e;
        }
    }

    private static String safeMessage(Throwable t) {
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }
}
