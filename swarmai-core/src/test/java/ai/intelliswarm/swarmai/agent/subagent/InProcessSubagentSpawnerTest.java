package ai.intelliswarm.swarmai.agent.subagent;

import ai.intelliswarm.swarmai.base.MockChatClientFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("InProcessSubagentSpawner / InProcessSubagent Tests")
class InProcessSubagentSpawnerTest {

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "test-subagent");
            t.setDaemon(true);
            return t;
        });
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private static SubagentSpec spec(String prompt) {
        return SubagentSpec.builder().userPrompt(prompt).build();
    }

    @Test
    @DisplayName("success path: spawn → awaitResult returns COMPLETED with the model's output")
    void successPath() {
        ChatClient mock = MockChatClientFactory.withResponse("the answer is 42");
        InProcessSubagentSpawner spawner = new InProcessSubagentSpawner(mock, executor);

        Subagent sub = spawner.spawn(spec("what is the answer?"));
        SubagentResult result = sub.awaitResult(Duration.ofSeconds(5));

        assertEquals(SubagentStatus.COMPLETED, result.status());
        assertEquals("the answer is 42", result.output());
        assertTrue(result.succeeded());
        assertNotNull(result.metrics().get("durationMs"));
        assertEquals(0, result.metrics().get("toolCount"));
    }

    @Test
    @DisplayName("failure path: ChatClient throws → result is FAILED with error class in metrics")
    void failurePath() {
        ChatClient mock = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(mock.prompt().system(anyString()).user(anyString()).call().content())
                .thenThrow(new RuntimeException("upstream broke"));

        InProcessSubagentSpawner spawner = new InProcessSubagentSpawner(mock, executor);
        Subagent sub = spawner.spawn(spec("any task"));
        SubagentResult result = sub.awaitResult(Duration.ofSeconds(5));

        assertEquals(SubagentStatus.FAILED, result.status());
        assertTrue(result.output().contains("upstream broke"),
                "Expected error message in output; got: " + result.output());
        assertTrue(result.metrics().get("errorClass").toString().contains("RuntimeException"));
    }

    @Test
    @DisplayName("timeout path: short awaitResult deadline → result is TIMED_OUT")
    void timeoutPath() {
        CountDownLatch never = new CountDownLatch(1);
        ChatClient mock = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(mock.prompt().system(anyString()).user(anyString()).call().content())
                .thenAnswer(inv -> {
                    never.await(); // blocks until test ends
                    return "never";
                });

        InProcessSubagentSpawner spawner = new InProcessSubagentSpawner(mock, executor);
        Subagent sub = spawner.spawn(spec("slow task"));

        SubagentResult result = sub.awaitResult(Duration.ofMillis(150));
        assertEquals(SubagentStatus.TIMED_OUT, result.status());
        assertNotNull(result.metrics().get("durationMs"));
    }

    @Test
    @DisplayName("cancel path: cancel before completion → result is CANCELLED")
    void cancelPath() throws InterruptedException {
        CountDownLatch never = new CountDownLatch(1);
        ChatClient mock = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(mock.prompt().system(anyString()).user(anyString()).call().content())
                .thenAnswer(inv -> {
                    never.await();
                    return "never";
                });

        InProcessSubagentSpawner spawner = new InProcessSubagentSpawner(mock, executor);
        Subagent sub = spawner.spawn(spec("slow task"));

        // Cancel from a parallel thread; awaiter should see CANCELLED quickly.
        new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException ignored) { }
            sub.cancel();
        }).start();

        SubagentResult result = sub.awaitResult(Duration.ofSeconds(5));
        assertEquals(SubagentStatus.CANCELLED, result.status());
    }

    @Test
    @DisplayName("workspace lifecycle: spec.workspaceFactory → workspace materialised + reaped on completion")
    void workspaceLifecycle() {
        ChatClient mock = MockChatClientFactory.withResponse("done");
        AtomicReference<Path> capturedRoot = new AtomicReference<>();

        SubagentSpec spec = SubagentSpec.builder()
                .userPrompt("task")
                .workspace(() -> {
                    EphemeralDirectoryWorkspace ws = new EphemeralDirectoryWorkspace("test-");
                    capturedRoot.set(ws.root());
                    return ws;
                })
                .build();

        InProcessSubagentSpawner spawner = new InProcessSubagentSpawner(mock, executor);
        Subagent sub = spawner.spawn(spec);
        SubagentResult result = sub.awaitResult(Duration.ofSeconds(5));

        assertEquals(SubagentStatus.COMPLETED, result.status());
        assertNotNull(capturedRoot.get(), "workspace factory was not invoked");
        assertFalse(Files.exists(capturedRoot.get()),
                "workspace should have been reaped after completion");
        assertEquals(capturedRoot.get().toString(), result.metrics().get("workspace"));
    }

    @Test
    @DisplayName("workspace materialisation failure → FailedSubagent yields FAILED immediately")
    void workspaceFactoryThrowsYieldsFailedHandle() {
        ChatClient mock = MockChatClientFactory.withResponse("unused");
        SubagentSpec spec = SubagentSpec.builder()
                .userPrompt("task")
                .workspace(() -> { throw new RuntimeException("disk full"); })
                .build();

        InProcessSubagentSpawner spawner = new InProcessSubagentSpawner(mock, executor);
        Subagent sub = spawner.spawn(spec);
        SubagentResult result = sub.awaitResult(Duration.ofSeconds(2));

        assertEquals(SubagentStatus.FAILED, result.status());
        assertTrue(result.output().contains("disk full"));
        // Spec id flows through even on failure
        assertEquals(spec.id(), sub.id());
    }

    @Test
    @DisplayName("metrics include type and toolCount from the spec")
    void metricsCarrySpecFields() {
        ChatClient mock = MockChatClientFactory.withResponse("ok");
        SubagentSpec spec = SubagentSpec.builder()
                .userPrompt("x")
                .type("researcher")
                .allowedTool("web_search")
                .allowedTool("fetch_url")
                .build();

        Subagent sub = new InProcessSubagentSpawner(mock, executor).spawn(spec);
        SubagentResult result = sub.awaitResult(Duration.ofSeconds(5));

        assertEquals("researcher", result.metrics().get("type"));
        assertEquals(2, result.metrics().get("toolCount"));
        assertNull(result.metrics().get("workspace"),
                "no workspace key when spec didn't request one");
    }

    @Test
    @DisplayName("concurrent spawns each get distinct ids and independent results")
    void concurrentSpawns() {
        ChatClient mock = MockChatClientFactory.withResponse("hi");
        InProcessSubagentSpawner spawner = new InProcessSubagentSpawner(mock, executor);

        Subagent a = spawner.spawn(spec("a"));
        Subagent b = spawner.spawn(spec("b"));
        Subagent c = spawner.spawn(spec("c"));

        SubagentResult ra = a.awaitResult(Duration.ofSeconds(5));
        SubagentResult rb = b.awaitResult(Duration.ofSeconds(5));
        SubagentResult rc = c.awaitResult(Duration.ofSeconds(5));

        assertEquals(SubagentStatus.COMPLETED, ra.status());
        assertEquals(SubagentStatus.COMPLETED, rb.status());
        assertEquals(SubagentStatus.COMPLETED, rc.status());
        // Distinct ids
        assertFalse(ra.id().equals(rb.id()));
        assertFalse(rb.id().equals(rc.id()));
    }

    @Test
    @DisplayName("cancel after completion is a no-op (status already COMPLETED)")
    void cancelAfterCompletionNoOp() {
        ChatClient mock = MockChatClientFactory.withResponse("done");
        Subagent sub = new InProcessSubagentSpawner(mock, executor).spawn(spec("x"));

        SubagentResult result = sub.awaitResult(Duration.ofSeconds(5));
        assertEquals(SubagentStatus.COMPLETED, result.status());

        sub.cancel(); // must not flip the state
        assertEquals(SubagentStatus.COMPLETED, sub.status());
    }

    @Test
    @DisplayName("status() returns CURRENT lifecycle state — PENDING/RUNNING before result, COMPLETED after")
    void statusObservable() throws InterruptedException {
        // Slow mock so we can observe RUNNING
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        ChatClient mock = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(mock.prompt().system(anyString()).user(anyString()).call().content())
                .thenAnswer(inv -> {
                    start.countDown();
                    finish.await();
                    return "done";
                });

        Subagent sub = new InProcessSubagentSpawner(mock, executor).spawn(spec("x"));
        assertTrue(start.await(2, TimeUnit.SECONDS), "subagent didn't start");
        assertEquals(SubagentStatus.RUNNING, sub.status());

        finish.countDown();
        SubagentResult result = sub.awaitResult(Duration.ofSeconds(2));
        assertEquals(SubagentStatus.COMPLETED, sub.status());
        assertEquals(SubagentStatus.COMPLETED, result.status());
    }
}
