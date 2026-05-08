package ai.intelliswarm.swarmai.tool.safety;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("GuardedOperation Tests")
class GuardedOperationTest {

    /** Capturing guard that records every observe(...) call. */
    private static class CapturingGuard implements MutationGuard {
        final java.util.List<Observation> observations = new java.util.ArrayList<>();

        record Observation(String correlationId, boolean success, String reason) {}

        @Override
        public Decision check(MutationPlan plan) {
            return Decision.approve("test", "ok", "id-fixed");
        }

        @Override
        public void observe(String correlationId, boolean success, String reason) {
            observations.add(new Observation(correlationId, success, reason));
        }
    }

    private static MutationGuard.Decision approvedDecision() {
        return MutationGuard.Decision.approve("test", "ok", "id-1");
    }

    @Test
    @DisplayName("success path: returns the value AND emits observe(true, successReason)")
    void successPath() throws IOException {
        CapturingGuard guard = new CapturingGuard();
        String result = GuardedOperation.runObserved(guard, approvedDecision(),
                "did the thing", () -> "the value");

        assertEquals("the value", result);
        assertEquals(1, guard.observations.size());
        var obs = guard.observations.get(0);
        assertEquals("id-1", obs.correlationId());
        assertTrue(obs.success());
        assertEquals("did the thing", obs.reason());
    }

    @Test
    @DisplayName("IOException path: emits observe(false, e.message) and rethrows the original")
    void ioExceptionPath() {
        CapturingGuard guard = new CapturingGuard();
        IOException thrown = assertThrows(IOException.class,
                () -> GuardedOperation.runObserved(guard, approvedDecision(),
                        "ignored on failure", () -> {
                            throw new IOException("disk full");
                        }));

        assertEquals("disk full", thrown.getMessage());
        assertEquals(1, guard.observations.size());
        var obs = guard.observations.get(0);
        assertEquals("id-1", obs.correlationId());
        assertTrue(!obs.success());
        assertEquals("disk full", obs.reason());
    }

    @Test
    @DisplayName("RuntimeException path: emits observe(false, e.message) and rethrows the original")
    void runtimeExceptionPath() {
        CapturingGuard guard = new CapturingGuard();
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> GuardedOperation.runObserved(guard, approvedDecision(),
                        "ignored", () -> {
                            throw new IllegalStateException("bad state");
                        }));

        assertEquals("bad state", thrown.getMessage());
        assertEquals(1, guard.observations.size());
        assertTrue(!guard.observations.get(0).success());
    }

    @Test
    @DisplayName("exception with null message: falls back to the exception class name")
    void nullMessageFallsBackToClassName() {
        CapturingGuard guard = new CapturingGuard();
        assertThrows(NullPointerException.class,
                () -> GuardedOperation.runObserved(guard, approvedDecision(),
                        "x", () -> {
                            throw new NullPointerException();
                        }));
        assertEquals("NullPointerException", guard.observations.get(0).reason());
    }

    @Test
    @DisplayName("Runnable variant works (no return value)")
    void runnableVariant() throws IOException {
        CapturingGuard guard = new CapturingGuard();
        AtomicReference<String> sideEffect = new AtomicReference<>();

        GuardedOperation.runObserved(guard, approvedDecision(),
                "ran", () -> sideEffect.set("done"));

        assertEquals("done", sideEffect.get());
        assertTrue(guard.observations.get(0).success());
    }

    @Test
    @DisplayName("decision with null correlationId: observe is still called (and no-ops in PlanAware)")
    void nullCorrelationIdAccepted() throws IOException {
        CapturingGuard guard = new CapturingGuard();
        MutationGuard.Decision d = MutationGuard.Decision.approve("test", "ok"); // no correlation id
        assertNull(d.correlationId());

        String result = GuardedOperation.runObserved(guard, d, "ok", () -> "value");

        assertEquals("value", result);
        // The helper still called observe — but with null correlation. Real
        // PlanAwareMutationGuard.observe ignores null correlation ids.
        assertNull(guard.observations.get(0).correlationId());
    }

    @Test
    @DisplayName("runObservedUnchecked: success path emits observe(true, reason)")
    void uncheckedSuccess() {
        CapturingGuard guard = new CapturingGuard();
        String result = GuardedOperation.runObservedUnchecked(guard, approvedDecision(),
                "ok", () -> "value");
        assertEquals("value", result);
        assertTrue(guard.observations.get(0).success());
    }

    @Test
    @DisplayName("runObservedUnchecked: RuntimeException path emits observe(false, msg) and rethrows")
    void uncheckedFailure() {
        CapturingGuard guard = new CapturingGuard();
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> GuardedOperation.runObservedUnchecked(guard, approvedDecision(),
                        "x", () -> { throw new IllegalStateException("boom"); }));
        assertEquals("boom", thrown.getMessage());
        assertTrue(!guard.observations.get(0).success());
        assertEquals("boom", guard.observations.get(0).reason());
    }

    @Test
    @DisplayName("works with a guard that doesn't override observe (default no-op)")
    void defaultObserveNoOp() throws IOException {
        // Guard implementation that only defines check; observe() is the default no-op
        MutationGuard guard = plan -> approvedDecision();

        String result = GuardedOperation.runObserved(guard, approvedDecision(),
                "ok", () -> "value");

        assertEquals("value", result);
        // No way to assert "the no-op fired", but if it threw or didn't run, we wouldn't reach here
    }
}
