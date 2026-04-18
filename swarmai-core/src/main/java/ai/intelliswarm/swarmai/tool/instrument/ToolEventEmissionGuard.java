package ai.intelliswarm.swarmai.tool.instrument;

import java.util.HashSet;
import java.util.Set;

/**
 * Re-entrancy guard shared by {@link ToolEventInterceptor} and
 * {@link BaseToolEventInterceptor}. Ensures a single tool call emits exactly one
 * {@code TOOL_STARTED / TOOL_COMPLETED / TOOL_FAILED} triplet even when the
 * invocation passes through both layers (Spring AI Function bean → adapter →
 * {@code BaseTool.execute(...)} on a CGLIB-proxied tool).
 *
 * <p>The outermost interceptor calls {@link #tryEnter(String)}; if it returns
 * {@code true} that layer owns emission for this call. Nested layers see the
 * name already claimed and skip their own publishes.</p>
 */
final class ToolEventEmissionGuard {

    private static final ThreadLocal<Set<String>> ACTIVE = ThreadLocal.withInitial(HashSet::new);

    private ToolEventEmissionGuard() {}

    /** @return true iff this thread has not already claimed emission for {@code toolName}. */
    static boolean tryEnter(String toolName) {
        return ACTIVE.get().add(toolName);
    }

    static void leave(String toolName) {
        Set<String> active = ACTIVE.get();
        active.remove(toolName);
        if (active.isEmpty()) {
            ACTIVE.remove();
        }
    }
}
