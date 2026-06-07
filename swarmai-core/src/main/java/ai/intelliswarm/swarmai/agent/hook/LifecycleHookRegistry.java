package ai.intelliswarm.swarmai.agent.hook;

import ai.intelliswarm.swarmai.api.PublicApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fans {@link LifecycleEvent}s to every registered {@link LifecycleHook} in
 * registration order. Stops at the first non-{@link HookAction#PROCEED} result
 * — so the first hook that wants to BLOCK or MODIFY wins, later hooks don't
 * see the event.
 *
 * <p>Thread-safe via {@link CopyOnWriteArrayList} — hooks can be hot-added
 * mid-run without coordination.
 *
 * <p>Hooks that throw are logged and skipped: a buggy audit hook should never
 * crash the agent loop. If you want strict propagation, wrap your hook in a
 * try/catch that throws a typed exception and add a fatal-exception filter.
 */
@PublicApi(since = "1.0.24")
public final class LifecycleHookRegistry {

    private static final Logger log = LoggerFactory.getLogger(LifecycleHookRegistry.class);

    private final List<LifecycleHook> hooks = new CopyOnWriteArrayList<>();

    public void register(LifecycleHook hook) {
        Objects.requireNonNull(hook, "hook must not be null");
        hooks.add(hook);
    }

    public boolean unregister(LifecycleHook hook) {
        return hooks.remove(hook);
    }

    public int size() {
        return hooks.size();
    }

    public List<LifecycleHook> hooks() {
        return List.copyOf(hooks);
    }

    /**
     * Dispatch an event through the registered hooks. Returns the first
     * non-PROCEED result, or {@link HookResult#proceed()} if every hook
     * agreed to continue.
     */
    public HookResult dispatch(LifecycleEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        for (LifecycleHook hook : hooks) {
            HookResult result;
            try {
                result = hook.onEvent(event);
            } catch (RuntimeException e) {
                log.warn("Hook {} threw while handling {}; skipping",
                        hook.getClass().getSimpleName(), event.getClass().getSimpleName(), e);
                continue;
            }
            if (result == null) {
                log.warn("Hook {} returned null result; treating as PROCEED",
                        hook.getClass().getSimpleName());
                continue;
            }
            if (result.action() != HookAction.PROCEED) {
                return result;
            }
        }
        return HookResult.proceed();
    }
}
