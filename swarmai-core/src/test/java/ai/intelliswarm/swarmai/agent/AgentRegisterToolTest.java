package ai.intelliswarm.swarmai.agent;

import ai.intelliswarm.swarmai.base.MockChatClientFactory;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hot-tool-registration: the agent's tool list must accept new tools at
 * runtime so the dynamic capability loop (build-image → read-manifest →
 * register-tools) can extend a live agent without rebuilding it.
 */
class AgentRegisterToolTest {

    private static BaseTool stubTool(String name) {
        return new BaseTool() {
            @Override public String getFunctionName() { return name; }
            @Override public String getDescription() { return "stub tool " + name; }
            @Override public Map<String, Object> getParameterSchema() { return Map.of(); }
            @Override public boolean isAsync() { return false; }
            @Override public Object execute(Map<String, Object> p) { return name; }
        };
    }

    private Agent newAgent(BaseTool... initial) {
        return Agent.builder()
                .id("test-agent")
                .role("test")
                .goal("test")
                .backstory("test")
                .chatClient(MockChatClientFactory.withResponse("ok"))
                .tools(List.of(initial))
                .build();
    }

    @Test
    @DisplayName("registerTool adds a new tool to the live list")
    void registersOne() {
        Agent a = newAgent();
        assertEquals(0, a.getTools().size());
        a.registerTool(stubTool("x"));
        assertEquals(1, a.getTools().size());
        assertEquals("x", a.getTools().get(0).getFunctionName());
    }

    @Test
    @DisplayName("registerTool skips duplicate names")
    void skipsDuplicateName() {
        Agent a = newAgent(stubTool("nmap"));
        a.registerTool(stubTool("nmap"));   // same name
        a.registerTool(stubTool("nmap"));   // same name again
        assertEquals(1, a.getTools().size(),
                "registering the same name should be idempotent");
    }

    @Test
    @DisplayName("registerTools bulk-registers a list, skipping duplicates")
    void bulkRegister() {
        Agent a = newAgent(stubTool("a"));
        a.registerTools(List.of(stubTool("a"), stubTool("b"), stubTool("c")));
        assertEquals(3, a.getTools().size());
        var names = a.getTools().stream().map(BaseTool::getFunctionName).toList();
        assertEquals(List.of("a", "b", "c"), names);
    }

    @Test
    @DisplayName("registerTool returns this for fluent chaining")
    void fluent() {
        Agent a = newAgent();
        Agent same = a.registerTool(stubTool("x")).registerTool(stubTool("y"));
        assertSame(a, same);
        assertEquals(2, a.getTools().size());
    }

    @Test
    @DisplayName("registerTool tolerates null without throwing")
    void nullTolerated() {
        Agent a = newAgent();
        assertDoesNotThrow(() -> a.registerTool(null));
        assertDoesNotThrow(() -> a.registerTools(null));
        assertEquals(0, a.getTools().size());
    }

    @Test
    @DisplayName("registerTool is safe under concurrent registration")
    void concurrent() throws Exception {
        Agent a = newAgent();
        int threads = 8;
        int perThread = 50;
        AtomicInteger errors = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        a.registerTool(stubTool("t" + tid + "_" + i));
                    }
                } catch (Throwable th) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "concurrent registration timed out");
        assertEquals(0, errors.get(), "concurrent registration should not throw");
        assertEquals(threads * perThread, a.getTools().size());
    }

    @Test
    @DisplayName("getTools returns a defensive copy — mutating it doesn't affect the agent")
    void getToolsDefensiveCopy() {
        Agent a = newAgent(stubTool("x"));
        var external = a.getTools();
        external.clear();
        assertEquals(1, a.getTools().size(),
                "mutating the returned list must not affect the agent's internal state");
    }
}
