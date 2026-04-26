package ai.intelliswarm.swarmai.tool.safety;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("KillSwitchMutationGuard Unit Tests")
class KillSwitchMutationGuardTest {

    @AfterEach
    void clear() {
        System.clearProperty(KillSwitch.PROP);
    }

    private static MutationPlan plan() {
        return MutationPlan.builder()
                .toolName("windows_filesystem").summary("x")
                .op("delete", "/x").build();
    }

    @Test
    @DisplayName("when the switch is OFF, the call passes through to the delegate")
    void offDelegates() {
        AtomicInteger calls = new AtomicInteger(0);
        MutationGuard inner = p -> {
            calls.incrementAndGet();
            return MutationGuard.Decision.approve("a", "ok");
        };
        MutationGuard guard = new KillSwitchMutationGuard(inner);

        MutationGuard.Decision d = guard.check(plan());

        assertTrue(d.approved());
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("when the switch is ON, the delegate is bypassed and the plan is rejected by SYSTEM")
    void onShortCircuits() {
        System.setProperty(KillSwitch.PROP, "true");
        AtomicInteger calls = new AtomicInteger(0);
        MutationGuard inner = p -> {
            calls.incrementAndGet();
            return MutationGuard.Decision.approve("a", "ok");
        };
        MutationGuard guard = new KillSwitchMutationGuard(inner);

        MutationGuard.Decision d = guard.check(plan());

        assertFalse(d.approved());
        assertEquals("SYSTEM", d.decidedBy());
        assertTrue(d.reason().toLowerCase().contains("kill-switch"));
        assertEquals(0, calls.get(), "delegate must not be called");
    }
}
