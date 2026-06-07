package ai.intelliswarm.swarmai.agent.wait.checkpoint;

import ai.intelliswarm.swarmai.agent.wait.Signal;
import ai.intelliswarm.swarmai.agent.wait.SignalDispatcher;
import ai.intelliswarm.swarmai.agent.wait.WaitForSignalRequest;
import ai.intelliswarm.swarmai.message.typed.TypedAssistantMessage;
import ai.intelliswarm.swarmai.message.typed.TypedMessage;
import ai.intelliswarm.swarmai.message.typed.TypedMessageHistory;
import ai.intelliswarm.swarmai.message.typed.TypedSystemMessage;
import ai.intelliswarm.swarmai.message.typed.TypedUserMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Phase 4 of the pause-resume primitive — checkpoint-and-release. */
class CheckpointPhaseFourTest {

    // =================================================================
    // WaitSentinel — pure utility
    // =================================================================
    @Nested
    @DisplayName("WaitSentinel")
    class Sentinel {

        @Test
        void roundTrip() {
            String token = WaitSentinel.forWaitId("wait_a1b2c3d4");
            assertEquals("WAITING_FOR_SIGNAL:wait_a1b2c3d4", token);
            assertEquals("wait_a1b2c3d4",
                    WaitSentinel.parseWaitId(token).orElseThrow());
            assertTrue(WaitSentinel.indicatesPause(token));
        }

        @Test
        void parsesEmbeddedSentinelInLongerText() {
            String reply = "Sure thing — pausing now. " + WaitSentinel.forWaitId("wait_xyz")
                    + " is the wait id.";
            assertEquals("wait_xyz",
                    WaitSentinel.parseWaitId(reply).orElseThrow());
        }

        @Test
        void firstSentinelWinsIfMultiple() {
            String reply = WaitSentinel.forWaitId("wait_first") + " also "
                    + WaitSentinel.forWaitId("wait_second");
            assertEquals("wait_first",
                    WaitSentinel.parseWaitId(reply).orElseThrow());
        }

        @Test
        void blankWaitIdRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> WaitSentinel.forWaitId(""));
            assertThrows(NullPointerException.class,
                    () -> WaitSentinel.forWaitId(null));
        }

        @Test
        void noSentinelReturnsEmpty() {
            assertTrue(WaitSentinel.parseWaitId(null).isEmpty());
            assertTrue(WaitSentinel.parseWaitId("plain text reply").isEmpty());
            assertTrue(WaitSentinel.parseWaitId("").isEmpty());
            assertFalse(WaitSentinel.indicatesPause("nothing here"));
        }

        @Test
        void malformedSentinelIgnored() {
            // Wrong prefix or wait-id grammar
            assertTrue(WaitSentinel.parseWaitId("WAITING_FOR_SIGNAL:bad-id-format").isEmpty());
            assertTrue(WaitSentinel.parseWaitId("waiting_for_signal:wait_xyz").isEmpty(),
                    "case-sensitive — lowercase prefix doesn't match");
        }
    }

    // =================================================================
    // PausedAgent record
    // =================================================================
    @Nested
    @DisplayName("PausedAgent record")
    class AgentRecord {

        @Test
        void buildsCleanly() {
            PausedAgent a = new PausedAgent(
                    "wait_x", "k", "r", "you are helpful",
                    List.of(TypedUserMessage.now("hi")),
                    List.of("calculator"),
                    Map.of("tenant", "acme"),
                    Instant.now(),
                    Instant.now().plus(Duration.ofHours(1)));
            assertEquals("wait_x", a.id());
            assertEquals(1, a.history().size());
            assertEquals(1, a.toolCallbackIds().size());
            assertTrue(a.expiresAtOpt().isPresent());
            assertFalse(a.hasExpired());
        }

        @Test
        void nullCollectionsBecomeEmpty() {
            PausedAgent a = new PausedAgent(
                    "wait_x", "k", "r", "sys",
                    null, null, null,
                    Instant.now(), null);
            assertTrue(a.history().isEmpty());
            assertTrue(a.toolCallbackIds().isEmpty());
            assertTrue(a.metadata().isEmpty());
            assertTrue(a.expiresAtOpt().isEmpty());
        }

        @Test
        void historyAndToolIdsAreImmutable() {
            PausedAgent a = new PausedAgent(
                    "wait_x", "k", "r", "sys",
                    new ArrayList<>(List.of(TypedUserMessage.now("hi"))),
                    new ArrayList<>(List.of("calc")),
                    new java.util.HashMap<>(Map.of("k", "v")),
                    Instant.now(), null);
            assertThrows(UnsupportedOperationException.class,
                    () -> a.history().clear());
            assertThrows(UnsupportedOperationException.class,
                    () -> a.toolCallbackIds().add("more"));
        }

        @Test
        void blankIdRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> new PausedAgent("", "k", "r", "sys",
                            null, null, null, Instant.now(), null));
        }

        @Test
        void hasExpiredCheckUsesNow() {
            PausedAgent past = new PausedAgent(
                    "wait_x", "k", "r", "sys", null, null, null,
                    Instant.now().minus(Duration.ofHours(2)),
                    Instant.now().minus(Duration.ofHours(1)));
            PausedAgent future = new PausedAgent(
                    "wait_y", "k", "r", "sys", null, null, null,
                    Instant.now(),
                    Instant.now().plus(Duration.ofHours(1)));
            assertTrue(past.hasExpired());
            assertFalse(future.hasExpired());
        }
    }

    // =================================================================
    // InMemoryPausedAgentStore
    // =================================================================
    @Nested
    @DisplayName("InMemoryPausedAgentStore")
    class Store {

        InMemoryPausedAgentStore store;

        @BeforeEach
        void setUp() { store = new InMemoryPausedAgentStore(); }

        private PausedAgent agent(String id) {
            return new PausedAgent(id, "k", "r", "sys",
                    null, null, null, Instant.now(),
                    Instant.now().plus(Duration.ofHours(1)));
        }

        @Test
        void emptyStoreReports() {
            assertEquals(0, store.size());
            assertTrue(store.all().isEmpty());
            assertTrue(store.load("nope").isEmpty());
            assertTrue(store.load(null).isEmpty());
            assertFalse(store.contains("nope"));
        }

        @Test
        void saveAndLoad() {
            PausedAgent a = agent("wait_a");
            store.save(a);
            assertEquals(1, store.size());
            assertSame(a, store.load("wait_a").orElseThrow());
            assertTrue(store.contains("wait_a"));
        }

        @Test
        void saveReplacesExisting() {
            store.save(agent("wait_a"));
            PausedAgent newer = agent("wait_a");
            store.save(newer);
            assertSame(newer, store.load("wait_a").orElseThrow());
            assertEquals(1, store.size());
        }

        @Test
        void deletePreservesIdempotency() {
            store.save(agent("wait_a"));
            assertTrue(store.delete("wait_a"));
            assertFalse(store.delete("wait_a"));
            assertFalse(store.delete(null));
        }

        @Test
        void allReturnsInsertionOrder() {
            store.save(agent("c"));
            store.save(agent("a"));
            store.save(agent("b"));
            List<String> ids = store.all().stream().map(PausedAgent::id).toList();
            assertEquals(List.of("c", "a", "b"), ids);
        }

        @Test
        void allIsImmutableSnapshot() {
            store.save(agent("a"));
            assertThrows(UnsupportedOperationException.class,
                    () -> store.all().clear());
        }

        @Test
        void deleteExpiredRemovesOnlyExpired() {
            // expired: pausedAt 2h ago, expiresAt 1h ago
            PausedAgent expired = new PausedAgent(
                    "wait_old", "k", "r", "sys", null, null, null,
                    Instant.now().minus(Duration.ofHours(2)),
                    Instant.now().minus(Duration.ofHours(1)));
            // fresh: just paused, expires in an hour
            PausedAgent fresh = new PausedAgent(
                    "wait_new", "k", "r", "sys", null, null, null,
                    Instant.now(),
                    Instant.now().plus(Duration.ofHours(1)));

            store.save(expired);
            store.save(fresh);
            int removed = store.deleteExpired();
            assertEquals(1, removed);
            assertFalse(store.contains("wait_old"));
            assertTrue(store.contains("wait_new"));
        }
    }

    // =================================================================
    // CheckpointingWaitForSignalTool
    // =================================================================
    @Nested
    @DisplayName("CheckpointingWaitForSignalTool")
    class Tool {

        SignalDispatcher dispatcher;

        @BeforeEach
        void setUp() { dispatcher = new SignalDispatcher(); }

        @AfterEach
        void tearDown() { dispatcher.close(); }

        @Test
        void returnsSentinelInsteadOfBlocking() {
            CheckpointingWaitForSignalTool.WaitInput input =
                    new CheckpointingWaitForSignalTool.WaitInput();
            input.signalKey = "k";
            input.reason = "r";
            input.ttlSeconds = 60L;

            String result = CheckpointingWaitForSignalTool.handle(
                    dispatcher, Duration.ofHours(24), input);
            assertTrue(result.contains(WaitSentinel.PREFIX), result);
            assertTrue(WaitSentinel.indicatesPause(result));

            // The wait was registered
            String waitId = WaitSentinel.parseWaitId(result).orElseThrow();
            assertTrue(dispatcher.getWait(waitId).isPresent());
        }

        @Test
        void doesNotBlock() {
            CheckpointingWaitForSignalTool.WaitInput input =
                    new CheckpointingWaitForSignalTool.WaitInput();
            input.signalKey = "k";
            input.reason = "r";

            // Even with a long default TTL, the call should return immediately
            long t0 = System.currentTimeMillis();
            CheckpointingWaitForSignalTool.handle(dispatcher, Duration.ofHours(24), input);
            long elapsed = System.currentTimeMillis() - t0;
            assertTrue(elapsed < 500, "checkpointing tool must not block; took " + elapsed + "ms");
        }

        @Test
        void blankSignalKeyRejected() {
            CheckpointingWaitForSignalTool.WaitInput input =
                    new CheckpointingWaitForSignalTool.WaitInput();
            input.signalKey = "  ";
            String result = CheckpointingWaitForSignalTool.handle(
                    dispatcher, Duration.ofHours(24), input);
            assertTrue(result.startsWith("Error:"));
        }

        @Test
        void replyTextNudgesLlmToForwardSentinel() {
            CheckpointingWaitForSignalTool.WaitInput input =
                    new CheckpointingWaitForSignalTool.WaitInput();
            input.signalKey = "k";
            input.reason = "r";

            String result = CheckpointingWaitForSignalTool.handle(
                    dispatcher, Duration.ofHours(24), input);
            assertTrue(result.contains("MUST"),
                    "result must instruct the LLM to forward the sentinel: " + result);
        }
    }

    // =================================================================
    // PauseDetector
    // =================================================================
    @Nested
    @DisplayName("PauseDetector")
    class Detector {

        SignalDispatcher dispatcher;
        InMemoryPausedAgentStore store;
        PauseDetector detector;

        @BeforeEach
        void setUp() {
            dispatcher = new SignalDispatcher();
            store = new InMemoryPausedAgentStore();
            detector = new PauseDetector(dispatcher, store);
        }

        @AfterEach
        void tearDown() { dispatcher.close(); }

        @Test
        void noSentinelReturnsEmpty() {
            TypedMessageHistory history = new TypedMessageHistory();
            history.add(TypedUserMessage.now("hello"));

            Optional<PausedAgent> r = detector.detectAndPersist(
                    "Just a regular reply", history,
                    "system prompt", List.of("calc"), Map.of());
            assertTrue(r.isEmpty());
            assertEquals(0, store.size());
        }

        @Test
        void sentinelButNoMatchingWaitReturnsEmpty() {
            // Sentinel is well-formed but the dispatcher knows nothing about it.
            // Don't persist — could be hallucination.
            TypedMessageHistory history = new TypedMessageHistory();
            String fakeSentinel = WaitSentinel.forWaitId("wait_phantom");
            Optional<PausedAgent> r = detector.detectAndPersist(
                    "Reply with " + fakeSentinel, history,
                    "sys", List.of(), Map.of());
            assertTrue(r.isEmpty());
            assertEquals(0, store.size());
        }

        @Test
        void detectsAndPersists() {
            // Register a real wait so the detector finds it
            WaitForSignalRequest wait = dispatcher.registerWait("k", "r", null);

            TypedMessageHistory history = new TypedMessageHistory();
            history.add(TypedSystemMessage.now("you are helpful"));
            history.add(TypedUserMessage.now("kick off deploy"));

            String reply = "I will pause until the deploy completes. "
                    + WaitSentinel.forWaitId(wait.id());
            Optional<PausedAgent> r = detector.detectAndPersist(
                    reply, history,
                    "you are helpful",
                    List.of("deploy_tool", "monitor"),
                    Map.of("tenant", "acme"));

            assertTrue(r.isPresent());
            PausedAgent paused = r.get();
            assertEquals(wait.id(), paused.id());
            assertEquals("k", paused.signalKey());
            assertEquals("you are helpful", paused.systemPrompt());
            assertEquals(2, paused.toolCallbackIds().size());
            assertEquals("acme", paused.metadata().get("tenant"));

            // History now includes the assistant's reply
            assertEquals(3, paused.history().size());
            TypedMessage last = paused.history().get(paused.history().size() - 1);
            assertInstanceOf(TypedAssistantMessage.class, last);
            assertEquals(reply, ((TypedAssistantMessage) last).content());

            // Persisted
            assertEquals(1, store.size());
            assertSame(paused, store.load(wait.id()).orElseThrow());
        }

        @Test
        void rePersistOverwrites() {
            // Idempotent: detector persists under wait id; second call overwrites
            WaitForSignalRequest wait = dispatcher.registerWait("k", "r", null);

            TypedMessageHistory history = new TypedMessageHistory();
            String reply = "first attempt " + WaitSentinel.forWaitId(wait.id());
            detector.detectAndPersist(reply, history, "sys", List.of(), Map.of());

            String reply2 = "retry " + WaitSentinel.forWaitId(wait.id());
            detector.detectAndPersist(reply2, history, "sys", List.of(), Map.of());

            assertEquals(1, store.size());
            PausedAgent stored = store.load(wait.id()).orElseThrow();
            // last reply wins
            String storedReplyContent = ((TypedAssistantMessage)
                    stored.history().get(stored.history().size() - 1)).content();
            assertEquals(reply2, storedReplyContent);
        }

        @Test
        void detectMethodWithoutPersist() {
            assertEquals(Optional.of("wait_xyz"),
                    detector.detect("text " + WaitSentinel.forWaitId("wait_xyz")));
            assertEquals(Optional.empty(), detector.detect("plain"));
        }
    }

    // =================================================================
    // SignalDispatcher.addCompletionListener — wired for resume
    // =================================================================
    @Nested
    @DisplayName("SignalDispatcher.addCompletionListener")
    class CompletionListener {

        @Test
        void firesOnDeliver() {
            SignalDispatcher dispatcher = new SignalDispatcher();
            try {
                AtomicReference<WaitForSignalRequest> seen = new AtomicReference<>();
                dispatcher.addCompletionListener(seen::set);

                WaitForSignalRequest wait = dispatcher.registerWait("k", "r", null);
                assertTrue(dispatcher.deliver("k", "payload"));

                assertNotNull(seen.get());
                assertEquals(wait.id(), seen.get().id());
                // The snapshot the listener received reflects the COMPLETED status
                assertEquals(ai.intelliswarm.swarmai.agent.wait.WaitStatus.COMPLETED,
                        seen.get().status());
            } finally {
                dispatcher.close();
            }
        }

        @Test
        void doesNotFireOnUnmatchedDeliver() {
            SignalDispatcher dispatcher = new SignalDispatcher();
            try {
                AtomicInteger calls = new AtomicInteger();
                dispatcher.addCompletionListener(w -> calls.incrementAndGet());

                assertFalse(dispatcher.deliver("nobody-listening", "p"));
                assertEquals(0, calls.get());
            } finally {
                dispatcher.close();
            }
        }

        @Test
        void throwingListenerDoesNotBreakDeliver() {
            SignalDispatcher dispatcher = new SignalDispatcher();
            try {
                AtomicInteger goodCalls = new AtomicInteger();
                dispatcher.addCompletionListener(w -> { throw new RuntimeException("boom"); });
                dispatcher.addCompletionListener(w -> goodCalls.incrementAndGet());

                dispatcher.registerWait("k", "r", null);
                assertDoesNotThrow(() -> dispatcher.deliver("k", "p"));
                assertEquals(1, goodCalls.get());
            } finally {
                dispatcher.close();
            }
        }

        @Test
        void removeStopsCallbacks() {
            SignalDispatcher dispatcher = new SignalDispatcher();
            try {
                AtomicInteger calls = new AtomicInteger();
                java.util.function.Consumer<WaitForSignalRequest> listener
                        = w -> calls.incrementAndGet();
                dispatcher.addCompletionListener(listener);
                dispatcher.registerWait("k", "r", null);
                dispatcher.deliver("k", "p");
                assertEquals(1, calls.get());

                dispatcher.removeCompletionListener(listener);
                dispatcher.registerWait("k", "r", null);
                dispatcher.deliver("k", "p");
                assertEquals(1, calls.get(), "removed listener doesn't fire");
            } finally {
                dispatcher.close();
            }
        }
    }
}
