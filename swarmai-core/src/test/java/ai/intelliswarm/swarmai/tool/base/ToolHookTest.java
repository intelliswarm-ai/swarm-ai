package ai.intelliswarm.swarmai.tool.base;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.base.BaseSwarmTest;
import ai.intelliswarm.swarmai.base.MockChatClientFactory;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Tool Hook Tests")
class ToolHookTest extends BaseSwarmTest {

    @Nested
    @DisplayName("ToolHookResult factory methods")
    class ToolHookResultTests {

        @Test
        @DisplayName("allow() creates ALLOW result")
        void allow_createsAllowResult() {
            ToolHookResult result = ToolHookResult.allow();
            assertEquals(ToolHookResult.Action.ALLOW, result.action());
            assertNull(result.message());
            assertNull(result.modifiedOutput());
        }

        @Test
        @DisplayName("deny() creates DENY result with reason")
        void deny_createsDenyWithReason() {
            ToolHookResult result = ToolHookResult.deny("Not authorized");
            assertEquals(ToolHookResult.Action.DENY, result.action());
            assertEquals("Not authorized", result.message());
            assertNull(result.modifiedOutput());
        }

        @Test
        @DisplayName("warn() creates WARN result with message")
        void warn_createsWarnWithMessage() {
            ToolHookResult result = ToolHookResult.warn("Rate limit approaching");
            assertEquals(ToolHookResult.Action.WARN, result.action());
            assertEquals("Rate limit approaching", result.message());
        }

        @Test
        @DisplayName("withModifiedOutput() creates ALLOW with replacement output")
        void withModifiedOutput_createsAllowWithOutput() {
            ToolHookResult result = ToolHookResult.withModifiedOutput("sanitized output");
            assertEquals(ToolHookResult.Action.ALLOW, result.action());
            assertEquals("sanitized output", result.modifiedOutput());
        }
    }

    @Nested
    @DisplayName("ToolHookContext factory methods")
    class ToolHookContextTests {

        @Test
        @DisplayName("before() creates pre-execution context")
        void before_createsPreContext() {
            ToolHookContext ctx = ToolHookContext.before(
                    "web_search", Map.of("query", "test"), "agent-1", "workflow-1");

            assertEquals("web_search", ctx.toolName());
            assertEquals("test", ctx.inputParams().get("query"));
            assertNull(ctx.output());
            assertEquals(0L, ctx.executionTimeMs());
            assertNull(ctx.error());
            assertTrue(ctx.isBeforeExecution());
            assertFalse(ctx.hasError());
        }

        @Test
        @DisplayName("after() creates post-execution context")
        void after_createsPostContext() {
            ToolHookContext ctx = ToolHookContext.after(
                    "web_search", Map.of("query", "test"), "search results", 150L, "agent-1", "workflow-1");

            assertEquals("web_search", ctx.toolName());
            assertEquals("search results", ctx.output());
            assertEquals(150L, ctx.executionTimeMs());
            assertFalse(ctx.isBeforeExecution());
            assertFalse(ctx.hasError());
        }

        @Test
        @DisplayName("error() creates error context")
        void error_createsErrorContext() {
            RuntimeException ex = new RuntimeException("connection refused");
            ToolHookContext ctx = ToolHookContext.error(
                    "web_search", Map.of(), 50L, ex, "agent-1", null);

            assertTrue(ctx.hasError());
            assertEquals(ex, ctx.error());
            assertNull(ctx.output());
        }
    }

    @Nested
    @DisplayName("ToolHook interface defaults")
    class ToolHookDefaultTests {

        @Test
        @DisplayName("default beforeToolUse returns ALLOW")
        void defaultBefore_returnsAllow() {
            ToolHook hook = new ToolHook() {};  // use defaults
            ToolHookContext ctx = ToolHookContext.before("tool", Map.of(), "agent", null);
            ToolHookResult result = hook.beforeToolUse(ctx);
            assertEquals(ToolHookResult.Action.ALLOW, result.action());
        }

        @Test
        @DisplayName("default afterToolUse returns ALLOW")
        void defaultAfter_returnsAllow() {
            ToolHook hook = new ToolHook() {};
            ToolHookContext ctx = ToolHookContext.after("tool", Map.of(), "output", 100L, "agent", null);
            ToolHookResult result = hook.afterToolUse(ctx);
            assertEquals(ToolHookResult.Action.ALLOW, result.action());
        }
    }

    @Nested
    @DisplayName("ToolHook integration with Agent builder")
    class AgentIntegrationTests {

        @Test
        @DisplayName("agent stores tool hooks from builder")
        void agent_storesToolHooks() {
            ToolHook hook1 = new ToolHook() {};
            ToolHook hook2 = new ToolHook() {};

            Agent agent = Agent.builder()
                    .role("Hooked Agent")
                    .goal("Test hooks")
                    .backstory("Test backstory")
                    .chatClient(mockChatClient)
                    .toolHook(hook1)
                    .toolHook(hook2)
                    .build();

            assertEquals(2, agent.getToolHooks().size());
        }

        @Test
        @DisplayName("agent with empty hooks list works normally")
        void agent_noHooks_worksNormally() {
            Agent agent = Agent.builder()
                    .role("Normal Agent")
                    .goal("No hooks")
                    .backstory("Test backstory")
                    .chatClient(mockChatClient)
                    .build();

            Task task = createTask(agent);
            TaskOutput output = agent.executeTask(task, Collections.emptyList());

            assertNotNull(output);
            assertTrue(output.isSuccessful());
            assertTrue(agent.getToolHooks().isEmpty());
        }

        @Test
        @DisplayName("withAdditionalTools preserves tool hooks")
        void withAdditionalTools_preservesHooks() {
            AtomicInteger hookCalls = new AtomicInteger(0);
            ToolHook countingHook = new ToolHook() {
                @Override
                public ToolHookResult beforeToolUse(ToolHookContext context) {
                    hookCalls.incrementAndGet();
                    return ToolHookResult.allow();
                }
            };

            Agent agent = Agent.builder()
                    .role("Agent with hooks")
                    .goal("Test preservation")
                    .backstory("Test backstory")
                    .chatClient(mockChatClient)
                    .toolHook(countingHook)
                    .build();

            Agent expanded = agent.withAdditionalTools(List.of());
            assertEquals(1, expanded.getToolHooks().size());
        }
    }

    @Nested
    @DisplayName("Hook execution scenarios")
    class HookScenarioTests {

        @Test
        @DisplayName("audit hook tracks tool invocations")
        void auditHook_tracksInvocations() {
            List<String> auditLog = new ArrayList<>();

            ToolHook auditHook = new ToolHook() {
                @Override
                public ToolHookResult beforeToolUse(ToolHookContext ctx) {
                    auditLog.add("BEFORE:" + ctx.toolName());
                    return ToolHookResult.allow();
                }

                @Override
                public ToolHookResult afterToolUse(ToolHookContext ctx) {
                    auditLog.add("AFTER:" + ctx.toolName() + ":" + ctx.executionTimeMs() + "ms");
                    return ToolHookResult.allow();
                }
            };

            // Verify the hook works in isolation
            ToolHookContext before = ToolHookContext.before("web_search", Map.of(), "agent-1", null);
            auditHook.beforeToolUse(before);
            assertEquals(1, auditLog.size());
            assertEquals("BEFORE:web_search", auditLog.get(0));

            ToolHookContext after = ToolHookContext.after("web_search", Map.of(), "results", 200L, "agent-1", null);
            auditHook.afterToolUse(after);
            assertEquals(2, auditLog.size());
            assertTrue(auditLog.get(1).startsWith("AFTER:web_search:"));
        }

        @Test
        @DisplayName("rate-limiting hook denies after threshold")
        void rateLimitHook_deniesAfterThreshold() {
            AtomicInteger callCount = new AtomicInteger(0);
            int maxCalls = 3;

            ToolHook rateLimitHook = new ToolHook() {
                @Override
                public ToolHookResult beforeToolUse(ToolHookContext ctx) {
                    if (callCount.incrementAndGet() > maxCalls) {
                        return ToolHookResult.deny("Rate limit exceeded: max " + maxCalls + " calls");
                    }
                    return ToolHookResult.allow();
                }
            };

            ToolHookContext ctx = ToolHookContext.before("expensive_tool", Map.of(), "agent-1", null);

            // First 3 calls allowed
            for (int i = 0; i < 3; i++) {
                assertEquals(ToolHookResult.Action.ALLOW, rateLimitHook.beforeToolUse(ctx).action());
            }

            // 4th call denied
            ToolHookResult result = rateLimitHook.beforeToolUse(ctx);
            assertEquals(ToolHookResult.Action.DENY, result.action());
            assertTrue(result.message().contains("Rate limit exceeded"));
        }

        @Test
        @DisplayName("output sanitization hook modifies output")
        void sanitizationHook_modifiesOutput() {
            ToolHook sanitizeHook = new ToolHook() {
                @Override
                public ToolHookResult afterToolUse(ToolHookContext ctx) {
                    if (ctx.output() != null && ctx.output().contains("SECRET")) {
                        return ToolHookResult.withModifiedOutput(
                                ctx.output().replace("SECRET", "[REDACTED]"));
                    }
                    return ToolHookResult.allow();
                }
            };

            ToolHookContext ctx = ToolHookContext.after(
                    "read_file", Map.of(), "Data contains SECRET info", 10L, "agent-1", null);
            ToolHookResult result = sanitizeHook.afterToolUse(ctx);

            assertEquals("Data contains [REDACTED] info", result.modifiedOutput());
        }

        @Test
        @DisplayName("multiple hooks chain correctly")
        void multipleHooks_chainCorrectly() {
            List<String> order = new ArrayList<>();

            ToolHook hook1 = new ToolHook() {
                @Override
                public ToolHookResult beforeToolUse(ToolHookContext ctx) {
                    order.add("hook1-before");
                    return ToolHookResult.allow();
                }

                @Override
                public ToolHookResult afterToolUse(ToolHookContext ctx) {
                    order.add("hook1-after");
                    return ToolHookResult.allow();
                }
            };

            ToolHook hook2 = new ToolHook() {
                @Override
                public ToolHookResult beforeToolUse(ToolHookContext ctx) {
                    order.add("hook2-before");
                    return ToolHookResult.allow();
                }

                @Override
                public ToolHookResult afterToolUse(ToolHookContext ctx) {
                    order.add("hook2-after");
                    return ToolHookResult.allow();
                }
            };

            // Simulate the hook chain
            ToolHookContext preCtx = ToolHookContext.before("tool", Map.of(), "agent", null);
            hook1.beforeToolUse(preCtx);
            hook2.beforeToolUse(preCtx);

            ToolHookContext postCtx = ToolHookContext.after("tool", Map.of(), "out", 10L, "agent", null);
            hook1.afterToolUse(postCtx);
            hook2.afterToolUse(postCtx);

            assertEquals(List.of("hook1-before", "hook2-before", "hook1-after", "hook2-after"), order);
        }
    }
}
