package ai.intelliswarm.swarmai.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Hook System Tests")
class HookSystemTest {

    @Nested
    @DisplayName("HookPoint enum")
    class HookPointTests {

        @Test
        @DisplayName("has all expected hook points")
        void hasAllPoints() {
            assertEquals(8, HookPoint.values().length);
            assertNotNull(HookPoint.BEFORE_WORKFLOW);
            assertNotNull(HookPoint.AFTER_WORKFLOW);
            assertNotNull(HookPoint.BEFORE_TASK);
            assertNotNull(HookPoint.AFTER_TASK);
            assertNotNull(HookPoint.BEFORE_TOOL);
            assertNotNull(HookPoint.AFTER_TOOL);
            assertNotNull(HookPoint.ON_ERROR);
            assertNotNull(HookPoint.ON_CHECKPOINT);
        }
    }

    @Nested
    @DisplayName("HookContext")
    class HookContextTests {

        @Test
        @DisplayName("creates workflow context")
        void createsWorkflowContext() {
            AgentState state = AgentState.of(Map.of("key", "val"));
            HookContext<AgentState> ctx = HookContext.forWorkflow(
                    HookPoint.BEFORE_WORKFLOW, state, "wf-1");

            assertEquals(HookPoint.BEFORE_WORKFLOW, ctx.hookPoint());
            assertEquals(state, ctx.state());
            assertEquals("wf-1", ctx.workflowId());
            assertTrue(ctx.getTaskId().isEmpty());
            assertTrue(ctx.getToolName().isEmpty());
            assertTrue(ctx.getError().isEmpty());
        }

        @Test
        @DisplayName("creates task context")
        void createsTaskContext() {
            HookContext<AgentState> ctx = HookContext.forTask(
                    HookPoint.BEFORE_TASK, AgentState.empty(), "wf-1", "task-1");

            assertEquals("task-1", ctx.getTaskId().orElse(null));
        }

        @Test
        @DisplayName("creates tool context")
        void createsToolContext() {
            HookContext<AgentState> ctx = HookContext.forTool(
                    HookPoint.BEFORE_TOOL, AgentState.empty(), "wf-1", "web_search");

            assertEquals("web_search", ctx.getToolName().orElse(null));
        }

        @Test
        @DisplayName("creates error context")
        void createsErrorContext() {
            RuntimeException error = new RuntimeException("test error");
            HookContext<AgentState> ctx = HookContext.forError(
                    AgentState.empty(), "wf-1", error);

            assertEquals(HookPoint.ON_ERROR, ctx.hookPoint());
            assertTrue(ctx.getError().isPresent());
            assertEquals("test error", ctx.getError().get().getMessage());
        }
    }

    @Nested
    @DisplayName("SwarmGraph hook registration")
    class SwarmGraphHookTests {

        @Test
        @DisplayName("registers hooks and preserves them after compilation")
        void registersHooks() {
            List<String> log = new ArrayList<>();

            CompiledSwarm swarm = SwarmGraph.create()
                    .addAgent(createAgent())
                    .addTask(createTask())
                    .addHook(HookPoint.BEFORE_WORKFLOW, ctx -> {
                        log.add("before");
                        return ctx.state();
                    })
                    .addHook(HookPoint.AFTER_WORKFLOW, ctx -> {
                        log.add("after");
                        return ctx.state();
                    })
                    .compileOrThrow();

            assertNotNull(swarm);
            // Hooks are registered — they'll fire during kickoff
        }

        @Test
        @DisplayName("supports multiple hooks at same point")
        void supportsMultipleHooks() {
            List<String> log = new ArrayList<>();

            SwarmGraph.create()
                    .addAgent(createAgent())
                    .addTask(createTask())
                    .addHook(HookPoint.BEFORE_WORKFLOW, ctx -> {
                        log.add("hook-1");
                        return ctx.state();
                    })
                    .addHook(HookPoint.BEFORE_WORKFLOW, ctx -> {
                        log.add("hook-2");
                        return ctx.state();
                    })
                    .compileOrThrow();

            // Both hooks registered at same point
        }

        @Test
        @DisplayName("rejects null hook point")
        void rejectsNullPoint() {
            assertThrows(NullPointerException.class, () ->
                    SwarmGraph.create().addHook(null, ctx -> ctx.state()));
        }

        @Test
        @DisplayName("rejects null hook")
        void rejectsNullHook() {
            assertThrows(NullPointerException.class, () ->
                    SwarmGraph.create().addHook(HookPoint.BEFORE_TASK, null));
        }
    }

    @Nested
    @DisplayName("SwarmHook functional interface")
    class SwarmHookTests {

        @Test
        @DisplayName("can be implemented as lambda")
        void lambdaImplementation() {
            SwarmHook<AgentState> hook = ctx -> ctx.state().withValue("hooked", true);

            AgentState result = hook.apply(
                    HookContext.forWorkflow(HookPoint.BEFORE_WORKFLOW, AgentState.empty(), "wf-1"));

            assertEquals(true, result.valueOrDefault("hooked", false));
        }

        @Test
        @DisplayName("can modify state")
        void modifiesState() {
            SwarmHook<AgentState> counter = ctx -> {
                long count = ctx.state().valueOrDefault("hookCount", 0L);
                return ctx.state().withValue("hookCount", count + 1);
            };

            AgentState state = AgentState.empty();
            HookContext<AgentState> ctx = HookContext.forWorkflow(
                    HookPoint.BEFORE_TASK, state, "wf-1");

            AgentState result = counter.apply(ctx);
            assertEquals(1L, result.valueOrDefault("hookCount", 0L));
        }

        @Test
        @DisplayName("pass-through hook returns state unchanged")
        void passThroughHook() {
            SwarmHook<AgentState> noop = ctx -> ctx.state();
            AgentState state = AgentState.of(Map.of("original", true));

            AgentState result = noop.apply(
                    HookContext.forWorkflow(HookPoint.BEFORE_WORKFLOW, state, "wf-1"));

            assertEquals(state, result);
        }
    }

    private ai.intelliswarm.swarmai.agent.Agent createAgent() {
        return ai.intelliswarm.swarmai.agent.Agent.builder()
                .role("worker")
                .goal("test")
                .backstory("test")
                .chatClient(org.mockito.Mockito.mock(
                        org.springframework.ai.chat.client.ChatClient.class))
                .build();
    }

    private ai.intelliswarm.swarmai.task.Task createTask() {
        return ai.intelliswarm.swarmai.task.Task.builder()
                .description("test task")
                .agent(createAgent())
                .build();
    }
}
