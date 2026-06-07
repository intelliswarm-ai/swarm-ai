package ai.intelliswarm.swarmai.tool.safety;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage-gap tests for the {@link MutationGuard} additions in 1.0.19:
 * the default {@link MutationGuard#observe} no-op and
 * {@link MutationGuard.Decision#withCorrelationId}.
 */
@DisplayName("MutationGuard coverage gaps (1.0.19)")
class MutationGuardCoverageGapsTest {

    @Nested
    @DisplayName("Default observe() no-op")
    class DefaultObserve {

        @Test
        @DisplayName("a guard that didn't override observe() accepts any input without effect")
        void defaultObserveAcceptsAnything() {
            // Plain MutationGuard implementation that only defines check().
            MutationGuard guard = plan -> MutationGuard.Decision.approve("test", "ok");

            // None of these should throw or have any visible effect.
            guard.observe(null, true, null);
            guard.observe("any-id", true, "details");
            guard.observe("any-id", false, "details");
            guard.observe("", true, "");
        }
    }

    @Nested
    @DisplayName("Decision.withCorrelationId")
    class DecisionWithCorrelationId {

        @Test
        @DisplayName("approve / reject factories produce a Decision with null correlationId")
        void factoriesStartWithNullCorrelation() {
            assertNull(MutationGuard.Decision.approve("a", "r").correlationId());
            assertNull(MutationGuard.Decision.reject("a", "r").correlationId());
        }

        @Test
        @DisplayName("withCorrelationId(value) sets the id and preserves every other field")
        void withCorrelationIdSetsId() {
            MutationGuard.Decision base = MutationGuard.Decision.approve("alice", "ok");
            MutationGuard.Decision withId = base.withCorrelationId("xyz");

            assertTrue(withId.approved());
            assertEquals("alice", withId.decidedBy());
            assertEquals("ok", withId.reason());
            assertEquals("xyz", withId.correlationId());
        }

        @Test
        @DisplayName("withCorrelationId(null) clears the id")
        void withCorrelationIdNullClears() {
            MutationGuard.Decision withId = MutationGuard.Decision.approve("a", "r")
                    .withCorrelationId("xyz");
            assertNull(withId.withCorrelationId(null).correlationId());
        }

        @Test
        @DisplayName("withCorrelationId returns a fresh record — original is untouched")
        void withCorrelationIdIsImmutable() {
            MutationGuard.Decision base = MutationGuard.Decision.approve("a", "r");
            MutationGuard.Decision modified = base.withCorrelationId("xyz");
            assertFalse(base == modified);
            assertNull(base.correlationId());
        }

        @Test
        @DisplayName("4-arg static factories produce a Decision with the given correlationId")
        void fourArgFactories() {
            MutationGuard.Decision a = MutationGuard.Decision.approve("a", "r", "id-1");
            assertEquals("id-1", a.correlationId());

            MutationGuard.Decision r = MutationGuard.Decision.reject("a", "r", "id-2");
            assertEquals("id-2", r.correlationId());
            assertFalse(r.approved());
        }

        @Test
        @DisplayName("auxiliary 3-arg constructor is preserved (backwards compat)")
        void threeArgConstructorPreserved() {
            // Sources written before 1.0.19 build Decisions via this 3-arg form.
            MutationGuard.Decision d = new MutationGuard.Decision(true, "alice", "ok");
            assertTrue(d.approved());
            assertEquals("alice", d.decidedBy());
            assertEquals("ok", d.reason());
            assertNull(d.correlationId());
        }
    }
}
