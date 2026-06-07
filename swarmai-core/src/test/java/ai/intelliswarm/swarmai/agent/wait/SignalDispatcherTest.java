package ai.intelliswarm.swarmai.agent.wait;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for the WaitForSignal primitive (1.0.21+). */
class SignalDispatcherTest {

    // =================================================================
    // Signal record
    // =================================================================
    @Nested
    @DisplayName("Signal record")
    class SignalRecord {

        @Test
        void ofFactory() {
            Signal s = Signal.of("k", "p");
            assertEquals("k", s.key());
            assertEquals("p", s.payload());
            assertNotNull(s.deliveredAt());
        }

        @Test
        void ofEmptyPayload() {
            Signal s = Signal.of("k");
            assertEquals("", s.payload());
        }

        @Test
        void blankKeyRejected() {
            assertThrows(IllegalArgumentException.class, () -> Signal.of("", "p"));
            assertThrows(IllegalArgumentException.class, () -> Signal.of("  ", "p"));
        }

        @Test
        void nullsRejected() {
            assertThrows(NullPointerException.class, () -> new Signal(null, "p", Instant.now()));
            assertThrows(NullPointerException.class, () -> new Signal("k", null, Instant.now()));
        }

        @Test
        void nullDeliveredAtDefaultsToNow() {
            Signal s = new Signal("k", "p", null);
            assertNotNull(s.deliveredAt());
        }
    }

    // =================================================================
    // WaitStatus enum
    // =================================================================
    @Nested
    @DisplayName("WaitStatus")
    class StatusEnum {

        @Test
        void terminalClassification() {
            assertFalse(WaitStatus.PENDING.isTerminal());
            assertTrue(WaitStatus.COMPLETED.isTerminal());
            assertTrue(WaitStatus.CANCELLED.isTerminal());
            assertTrue(WaitStatus.EXPIRED.isTerminal());
        }
    }

    // =================================================================
    // Register + deliver
    // =================================================================
    @Nested
    @DisplayName("registerWait + deliver")
    class RegisterDeliver {

        @Test
        void registerProducesUniqueIds() {
            SignalDispatcher d = new SignalDispatcher();
            WaitForSignalRequest a = d.registerWait("k", "first", null);
            WaitForSignalRequest b = d.registerWait("k", "second", null);
            assertNotEquals(a.id(), b.id());
            assertEquals(WaitStatus.PENDING, a.status());
            assertEquals(WaitStatus.PENDING, b.status());
        }

        @Test
        void blankKeyRejected() {
            SignalDispatcher d = new SignalDispatcher();
            assertThrows(IllegalArgumentException.class,
                    () -> d.registerWait("", "reason", null));
            assertThrows(IllegalArgumentException.class,
                    () -> d.registerWait("k", "", null));
        }

        @Test
        void nullTtlMeansNoTimeout() {
            SignalDispatcher d = new SignalDispatcher();
            WaitForSignalRequest w = d.registerWait("k", "r", null);
            assertTrue(w.expiresAtOpt().isEmpty());
        }

        @Test
        void nonNullTtlSetsExpiry() {
            SignalDispatcher d = new SignalDispatcher();
            WaitForSignalRequest w = d.registerWait("k", "r", Duration.ofSeconds(60));
            assertTrue(w.expiresAtOpt().isPresent());
        }

        @Test
        void deliverWithNoWaitDropsAndReportsZero() {
            SignalDispatcher d = new SignalDispatcher();
            assertFalse(d.deliver("nope", "payload"));
            assertEquals(1, d.droppedSignals());
        }

        @Test
        void deliverMatchesPendingWait() throws Exception {
            SignalDispatcher d = new SignalDispatcher();
            WaitForSignalRequest w = d.registerWait("k", "r", null);

            // Run awaitSignal in a thread; deliver from main
            ExecutorService exec = Executors.newSingleThreadExecutor();
            try {
                AtomicReference<Signal> received = new AtomicReference<>();
                CountDownLatch awaiting = new CountDownLatch(1);
                exec.submit(() -> {
                    awaiting.countDown();
                    received.set(d.awaitSignal(w.id(), Duration.ofSeconds(2)));
                });
                assertTrue(awaiting.await(1, TimeUnit.SECONDS));
                Thread.sleep(50);  // let the awaitSignal call register on the future
                assertTrue(d.deliver("k", "the-payload"));
                exec.shutdown();
                assertTrue(exec.awaitTermination(2, TimeUnit.SECONDS));
                assertNotNull(received.get());
                assertEquals("the-payload", received.get().payload());
            } finally {
                exec.shutdownNow();
            }

            // Snapshot now reflects COMPLETED
            WaitForSignalRequest after = d.getWait(w.id()).orElseThrow();
            assertEquals(WaitStatus.COMPLETED, after.status());
            assertTrue(after.receivedOpt().isPresent());
        }

        @Test
        void firstInFirstOutMatching() throws Exception {
            SignalDispatcher d = new SignalDispatcher();
            WaitForSignalRequest first = d.registerWait("k", "first", null);
            WaitForSignalRequest second = d.registerWait("k", "second", null);

            // Start awaiting on both; each on their own thread
            ExecutorService exec = Executors.newFixedThreadPool(2);
            try {
                AtomicReference<Signal> got1 = new AtomicReference<>();
                AtomicReference<Signal> got2 = new AtomicReference<>();
                CountDownLatch ready = new CountDownLatch(2);
                exec.submit(() -> { ready.countDown(); got1.set(d.awaitSignal(first.id(), Duration.ofSeconds(2))); });
                exec.submit(() -> { ready.countDown(); got2.set(d.awaitSignal(second.id(), Duration.ofSeconds(2))); });
                assertTrue(ready.await(1, TimeUnit.SECONDS));
                Thread.sleep(50);
                d.deliver("k", "payload-1");
                d.deliver("k", "payload-2");
                exec.shutdown();
                assertTrue(exec.awaitTermination(3, TimeUnit.SECONDS));
                assertEquals("payload-1", got1.get().payload(), "first wait gets first signal");
                assertEquals("payload-2", got2.get().payload());
            } finally {
                exec.shutdownNow();
            }
        }

        @Test
        void deliverIsKeyScoped() throws Exception {
            SignalDispatcher d = new SignalDispatcher();
            WaitForSignalRequest w = d.registerWait("alpha", "r", null);

            // Signal for a different key — must NOT match
            assertFalse(d.deliver("beta", "wrong"));
            assertEquals(1, d.droppedSignals());

            // The wait is still PENDING
            assertEquals(WaitStatus.PENDING, d.getWait(w.id()).orElseThrow().status());

            // Cancel to clean up the test
            assertTrue(d.cancel(w.id()));
        }
    }

    // =================================================================
    // Cancel + expire
    // =================================================================
    @Nested
    @DisplayName("cancel + expire")
    class CancelExpire {

        @Test
        void cancelCompletesFutureWithCancelled() {
            SignalDispatcher d = new SignalDispatcher();
            WaitForSignalRequest w = d.registerWait("k", "r", null);

            ExecutorService exec = Executors.newSingleThreadExecutor();
            try {
                AtomicReference<Throwable> caught = new AtomicReference<>();
                CountDownLatch done = new CountDownLatch(1);
                exec.submit(() -> {
                    try {
                        d.awaitSignal(w.id(), Duration.ofSeconds(2));
                    } catch (Throwable t) {
                        caught.set(t);
                    } finally {
                        done.countDown();
                    }
                });
                // Give the awaitSignal call a moment to register
                Thread.sleep(50);
                assertTrue(d.cancel(w.id()));
                assertTrue(done.await(2, TimeUnit.SECONDS));
                assertInstanceOf(WaitForSignalException.Cancelled.class, caught.get());
            } catch (InterruptedException e) {
                fail("interrupted");
            } finally {
                exec.shutdownNow();
            }
            assertEquals(WaitStatus.CANCELLED,
                    d.getWait(w.id()).orElseThrow().status());
        }

        @Test
        void cancelOnTerminalIsNoOp() {
            SignalDispatcher d = new SignalDispatcher();
            WaitForSignalRequest w = d.registerWait("k", "r", null);
            assertTrue(d.cancel(w.id()));
            assertFalse(d.cancel(w.id()), "second cancel returns false");
        }

        @Test
        void autoExpiryFiresWhenTtlElapses() throws Exception {
            SignalDispatcher d = new SignalDispatcher();
            WaitForSignalRequest w = d.registerWait("k", "r", Duration.ofMillis(50));
            // Auto-expiry runs on the dispatcher's scheduler; wait for it.
            Thread.sleep(150);
            assertEquals(WaitStatus.EXPIRED,
                    d.getWait(w.id()).orElseThrow().status(),
                    "auto-expiry should mark the wait EXPIRED without an explicit sweepExpired call");
            d.close();
        }

        @Test
        void manualSweepIsNoOpOnAlreadyExpiredWaits() throws Exception {
            // The manual sweepExpired remains for callers who want a fallback,
            // but it must not double-count waits already auto-expired.
            SignalDispatcher d = new SignalDispatcher();
            WaitForSignalRequest w = d.registerWait("k", "r", Duration.ofMillis(50));
            Thread.sleep(150);  // auto-expiry has fired
            int swept = d.sweepExpired();
            assertEquals(0, swept, "auto-expiry already swept; manual sweep finds nothing new");
            assertEquals(WaitStatus.EXPIRED,
                    d.getWait(w.id()).orElseThrow().status());
            d.close();
        }

        @Test
        void sweepDoesNotTouchActiveWaits() {
            SignalDispatcher d = new SignalDispatcher();
            WaitForSignalRequest w = d.registerWait("k", "r", Duration.ofMinutes(10));
            assertEquals(0, d.sweepExpired());
            assertEquals(WaitStatus.PENDING,
                    d.getWait(w.id()).orElseThrow().status());
        }

        @Test
        void awaitSignalTimeoutThrowsTimeout() {
            SignalDispatcher d = new SignalDispatcher();
            WaitForSignalRequest w = d.registerWait("k", "r", null);
            assertThrows(WaitForSignalException.Timeout.class,
                    () -> d.awaitSignal(w.id(), Duration.ofMillis(50)));
            // The wait remains PENDING — block-deadline elapsed, not the wait's TTL
            assertEquals(WaitStatus.PENDING,
                    d.getWait(w.id()).orElseThrow().status());
        }
    }

    // =================================================================
    // Queries
    // =================================================================
    @Nested
    @DisplayName("queries")
    class Queries {

        @Test
        void freshDispatcherIsEmpty() {
            SignalDispatcher d = new SignalDispatcher();
            assertEquals(0, d.pendingCount());
            assertEquals(0, d.droppedSignals());
            assertTrue(d.pendingWaits().isEmpty());
            assertTrue(d.allWaits().isEmpty());
            assertTrue(d.getWait("nope").isEmpty());
            assertTrue(d.getWait(null).isEmpty());
        }

        @Test
        void pendingCountReflectsLifecycle() {
            SignalDispatcher d = new SignalDispatcher();
            WaitForSignalRequest a = d.registerWait("k1", "r", null);
            d.registerWait("k2", "r", null);
            assertEquals(2, d.pendingCount());

            d.deliver("k1", "x");
            assertEquals(1, d.pendingCount());
            assertEquals(2, d.allWaits().size(), "completed waits stay in allWaits");
        }

        @Test
        void unmatchedSignalListenerFires() {
            SignalDispatcher d = new SignalDispatcher();
            List<Signal> seen = new ArrayList<>();
            d.addUnmatchedSignalListener(seen::add);

            d.deliver("nope-1", "p1");
            d.deliver("nope-2", "p2");
            assertEquals(2, seen.size());
            assertEquals("p1", seen.get(0).payload());
            assertEquals("p2", seen.get(1).payload());
        }

        @Test
        void removingListenerStopsCallbacks() {
            SignalDispatcher d = new SignalDispatcher();
            AtomicInteger calls = new AtomicInteger();
            java.util.function.Consumer<Signal> listener = s -> calls.incrementAndGet();
            d.addUnmatchedSignalListener(listener);
            d.deliver("nope", "p");
            d.removeUnmatchedSignalListener(listener);
            d.deliver("nope-2", "p");
            assertEquals(1, calls.get());
        }

        @Test
        void throwingListenerDoesntBreakDeliver() {
            SignalDispatcher d = new SignalDispatcher();
            AtomicInteger goodCalls = new AtomicInteger();
            d.addUnmatchedSignalListener(s -> { throw new RuntimeException("boom"); });
            d.addUnmatchedSignalListener(s -> goodCalls.incrementAndGet());
            assertDoesNotThrow(() -> d.deliver("nope", "p"));
            assertEquals(1, goodCalls.get());
        }

        @Test
        void unknownAwaitThrowsUnknownWait() {
            SignalDispatcher d = new SignalDispatcher();
            assertThrows(WaitForSignalException.UnknownWait.class,
                    () -> d.awaitSignal("does-not-exist", Duration.ofSeconds(1)));
        }

        @Test
        void pendingWaitsImmutableSnapshot() {
            SignalDispatcher d = new SignalDispatcher();
            d.registerWait("k", "r", null);
            assertThrows(UnsupportedOperationException.class,
                    () -> d.pendingWaits().clear());
        }
    }

    // =================================================================
    // WaitForSignalTool — agent-callable bridge
    // =================================================================
    @Nested
    @DisplayName("WaitForSignalTool")
    class ToolBridge {

        @Test
        void successfulSignalDeliveredToTool() throws Exception {
            SignalDispatcher d = new SignalDispatcher();
            WaitForSignalTool.WaitInput input = new WaitForSignalTool.WaitInput();
            input.signalKey = "deploy.ready";
            input.reason = "waiting for canary deploy";
            input.timeoutSeconds = 5L;

            ExecutorService exec = Executors.newSingleThreadExecutor();
            try {
                java.util.concurrent.Future<String> future = exec.submit(() ->
                        WaitForSignalTool.handle(d, Duration.ofMinutes(30), input));

                // Wait for the wait to register, then deliver
                long deadline = System.currentTimeMillis() + 2000;
                while (d.pendingCount() == 0 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20);
                }
                assertEquals(1, d.pendingCount());
                assertTrue(d.deliver("deploy.ready", "deploy ok at canary cell-1"));

                String result = future.get(5, TimeUnit.SECONDS);
                assertEquals("Signal received: deploy ok at canary cell-1", result);
            } finally {
                exec.shutdownNow();
            }
        }

        @Test
        void timeoutReportedAsErrorString() {
            SignalDispatcher d = new SignalDispatcher();
            WaitForSignalTool.WaitInput input = new WaitForSignalTool.WaitInput();
            input.signalKey = "k";
            input.reason = "r";
            input.timeoutSeconds = 1L;  // very short

            String result = WaitForSignalTool.handle(d, Duration.ofMinutes(30), input);
            assertTrue(result.startsWith("Error: timeout"), result);
            assertTrue(result.contains("expired after TTL")
                    || result.contains("wait expired"), result);
        }

        @Test
        void cancelReportedAsErrorString() throws Exception {
            SignalDispatcher d = new SignalDispatcher();
            WaitForSignalTool.WaitInput input = new WaitForSignalTool.WaitInput();
            input.signalKey = "k";
            input.reason = "r";
            input.timeoutSeconds = 5L;

            ExecutorService exec = Executors.newSingleThreadExecutor();
            try {
                java.util.concurrent.Future<String> future = exec.submit(() ->
                        WaitForSignalTool.handle(d, Duration.ofMinutes(30), input));
                // Wait for the wait to register
                long deadline = System.currentTimeMillis() + 2000;
                while (d.pendingCount() == 0 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20);
                }
                String waitId = d.pendingWaits().get(0).id();
                assertTrue(d.cancel(waitId));

                String result = future.get(5, TimeUnit.SECONDS);
                assertTrue(result.startsWith("Error: cancelled"), result);
            } finally {
                exec.shutdownNow();
            }
        }

        @Test
        void blankSignalKeyRejected() {
            SignalDispatcher d = new SignalDispatcher();
            WaitForSignalTool.WaitInput input = new WaitForSignalTool.WaitInput();
            input.signalKey = "  ";
            String result = WaitForSignalTool.handle(d, Duration.ofMinutes(30), input);
            assertTrue(result.startsWith("Error:"), result);
        }

        @Test
        void nullInputRejected() {
            SignalDispatcher d = new SignalDispatcher();
            String result = WaitForSignalTool.handle(d, Duration.ofMinutes(30), null);
            assertTrue(result.startsWith("Error:"), result);
        }

        @Test
        void defaultTimeoutAppliedWhenInputOmitsIt() throws Exception {
            SignalDispatcher d = new SignalDispatcher();
            WaitForSignalTool.WaitInput input = new WaitForSignalTool.WaitInput();
            input.signalKey = "k";
            input.reason = "r";
            // timeoutSeconds is null — default should apply

            // Use a tiny default to keep the test fast
            ExecutorService exec = Executors.newSingleThreadExecutor();
            try {
                java.util.concurrent.Future<String> future = exec.submit(() ->
                        WaitForSignalTool.handle(d, Duration.ofMillis(80), input));
                String result = future.get(5, TimeUnit.SECONDS);
                assertTrue(result.startsWith("Error: timeout"), result);
            } finally {
                exec.shutdownNow();
            }
        }

        @Test
        void buildCallbackHasExpectedFunctionName() {
            SignalDispatcher d = new SignalDispatcher();
            assertEquals("wait_for_signal",
                    WaitForSignalTool.callback(d).getToolDefinition().name());
        }

        @Test
        void buildCallbackAcceptsCustomName() {
            SignalDispatcher d = new SignalDispatcher();
            assertEquals("await_event",
                    WaitForSignalTool.callback(d, "await_event").getToolDefinition().name());
        }
    }

    // =================================================================
    // Concurrency
    // =================================================================
    @Nested
    @DisplayName("concurrency")
    class Concurrency {

        @Test
        void parallelWaitsAllDelivered() throws Exception {
            SignalDispatcher d = new SignalDispatcher();
            int n = 30;

            // Register N waits, each with a unique key
            List<WaitForSignalRequest> waits = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                waits.add(d.registerWait("k-" + i, "r-" + i, null));
            }

            // Start a thread per wait, all blocking on awaitSignal.
            // MUST be one-thread-per-task: each awaitSignal call blocks, so a
            // bounded pool would deadlock (workers can't take more tasks).
            ExecutorService exec = Executors.newCachedThreadPool();
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch ready = new CountDownLatch(n);
            CountDownLatch done = new CountDownLatch(n);
            List<Signal> received = java.util.Collections.synchronizedList(new ArrayList<>());

            for (int i = 0; i < n; i++) {
                final int idx = i;
                exec.submit(() -> {
                    try {
                        start.await();
                        ready.countDown();
                        Signal s = d.awaitSignal(waits.get(idx).id(), Duration.ofSeconds(5));
                        received.add(s);
                    } catch (InterruptedException ignored) {
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            Thread.sleep(50);  // let awaiters register on futures

            // Fire signals from main
            for (int i = 0; i < n; i++) {
                assertTrue(d.deliver("k-" + i, "payload-" + i));
            }

            assertTrue(done.await(5, TimeUnit.SECONDS));
            exec.shutdownNow();

            assertEquals(n, received.size(), "every wait was woken up exactly once");
            // Every payload should have been received
            List<String> payloads = received.stream().map(Signal::payload).sorted().toList();
            for (int i = 0; i < n; i++) {
                assertTrue(payloads.contains("payload-" + i));
            }
        }
    }
}
