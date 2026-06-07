package ai.intelliswarm.swarmai.agent.hook;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LifecycleHookRegistryTest {

    @Test
    void emptyRegistryProceedsByDefault() {
        LifecycleHookRegistry registry = new LifecycleHookRegistry();
        HookResult result = registry.dispatch(userPrompt("hello"));
        assertThat(result.action()).isEqualTo(HookAction.PROCEED);
    }

    @Test
    void hooksFireInRegistrationOrder() {
        LifecycleHookRegistry registry = new LifecycleHookRegistry();
        List<String> order = new ArrayList<>();
        registry.register(new LifecycleHook() {
            @Override public HookResult onUserPromptSubmit(LifecycleEvent.UserPromptSubmit e) {
                order.add("first");
                return HookResult.proceed();
            }
        });
        registry.register(new LifecycleHook() {
            @Override public HookResult onUserPromptSubmit(LifecycleEvent.UserPromptSubmit e) {
                order.add("second");
                return HookResult.proceed();
            }
        });
        registry.dispatch(userPrompt("hello"));
        assertThat(order).containsExactly("first", "second");
    }

    @Test
    void firstNonProceedShortCircuits() {
        LifecycleHookRegistry registry = new LifecycleHookRegistry();
        List<String> order = new ArrayList<>();
        registry.register(new LifecycleHook() {
            @Override public HookResult onUserPromptSubmit(LifecycleEvent.UserPromptSubmit e) {
                order.add("first");
                return HookResult.block("blocked by first");
            }
        });
        registry.register(new LifecycleHook() {
            @Override public HookResult onUserPromptSubmit(LifecycleEvent.UserPromptSubmit e) {
                order.add("second");
                return HookResult.proceed();
            }
        });
        HookResult result = registry.dispatch(userPrompt("hello"));
        assertThat(order).containsExactly("first");
        assertThat(result.action()).isEqualTo(HookAction.BLOCK);
        assertThat(result.message()).isEqualTo("blocked by first");
    }

    @Test
    void modifyCarriesNewPayload() {
        LifecycleHookRegistry registry = new LifecycleHookRegistry();
        registry.register(new LifecycleHook() {
            @Override public HookResult onUserPromptSubmit(LifecycleEvent.UserPromptSubmit e) {
                return HookResult.modify("[REWRITTEN] " + e.prompt());
            }
        });
        HookResult result = registry.dispatch(userPrompt("hello"));
        assertThat(result.action()).isEqualTo(HookAction.MODIFY);
        assertThat(result.modifiedPayload()).isEqualTo("[REWRITTEN] hello");
    }

    @Test
    void throwingHooksAreSkipped() {
        LifecycleHookRegistry registry = new LifecycleHookRegistry();
        List<String> order = new ArrayList<>();
        registry.register(new LifecycleHook() {
            @Override public HookResult onUserPromptSubmit(LifecycleEvent.UserPromptSubmit e) {
                order.add("buggy");
                throw new RuntimeException("oh no");
            }
        });
        registry.register(new LifecycleHook() {
            @Override public HookResult onUserPromptSubmit(LifecycleEvent.UserPromptSubmit e) {
                order.add("survivor");
                return HookResult.block("after-buggy");
            }
        });
        HookResult result = registry.dispatch(userPrompt("hello"));
        assertThat(order).containsExactly("buggy", "survivor");
        assertThat(result.action()).isEqualTo(HookAction.BLOCK);
    }

    @Test
    void dispatchRoutesByEventType() {
        LifecycleHookRegistry registry = new LifecycleHookRegistry();
        List<String> calls = new ArrayList<>();
        registry.register(new LifecycleHook() {
            @Override public HookResult onUserPromptSubmit(LifecycleEvent.UserPromptSubmit e) {
                calls.add("prompt:" + e.prompt());
                return HookResult.proceed();
            }
            @Override public HookResult onPreCompact(LifecycleEvent.PreCompact e) {
                calls.add("compact:" + e.currentTokens());
                return HookResult.proceed();
            }
            @Override public HookResult onStop(LifecycleEvent.Stop e) {
                calls.add("stop:" + e.stopReason());
                return HookResult.proceed();
            }
        });
        registry.dispatch(userPrompt("hi"));
        registry.dispatch(new LifecycleEvent.PreCompact(
                "corr1", "agent1", Instant.now(), 100_000, 25_000, Map.of()));
        registry.dispatch(new LifecycleEvent.Stop(
                "corr1", "agent1", Instant.now(), "end_turn", Map.of()));

        assertThat(calls).containsExactly("prompt:hi", "compact:100000", "stop:end_turn");
    }

    @Test
    void unregisterRemovesHook() {
        LifecycleHookRegistry registry = new LifecycleHookRegistry();
        LifecycleHook hook = new LifecycleHook() {
            @Override public HookResult onUserPromptSubmit(LifecycleEvent.UserPromptSubmit e) {
                return HookResult.block("blocked");
            }
        };
        registry.register(hook);
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.dispatch(userPrompt("x")).action()).isEqualTo(HookAction.BLOCK);

        assertThat(registry.unregister(hook)).isTrue();
        assertThat(registry.size()).isZero();
        assertThat(registry.dispatch(userPrompt("x")).action()).isEqualTo(HookAction.PROCEED);
    }

    @Test
    void hookOverridingOnEventDirectlyAlsoWorks() {
        // A hook that wants every event in one method overrides onEvent itself.
        LifecycleHookRegistry registry = new LifecycleHookRegistry();
        List<String> log = new ArrayList<>();
        registry.register(new LifecycleHook() {
            @Override public HookResult onEvent(LifecycleEvent e) {
                log.add(e.getClass().getSimpleName());
                return HookResult.proceed();
            }
        });
        registry.dispatch(userPrompt("p"));
        registry.dispatch(new LifecycleEvent.FileChanged(
                "c", "a", Instant.now(), "/x", LifecycleEvent.FileChanged.ChangeKind.MODIFIED, Map.of()));
        assertThat(log).containsExactly("UserPromptSubmit", "FileChanged");
    }

    // ---------- helpers ----------

    private static LifecycleEvent.UserPromptSubmit userPrompt(String prompt) {
        return new LifecycleEvent.UserPromptSubmit(
                "corr-1", "agent-1", Instant.now(), prompt, Map.of());
    }
}
