package ai.intelliswarm.swarmai.agent.wait;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for the wall-clock signal source (Phase 2 of pause-resume, 1.0.21+). */
@DisplayName("WallClockSignalSource")
class WallClockSignalSourceTest {

    private SignalDispatcher dispatcher;
    private WallClockSignalSource source;

    @BeforeEach
    void setUp() {
        dispatcher = new SignalDispatcher();
        source = new WallClockSignalSource(dispatcher);
    }

    @AfterEach
    void tearDown() {
        source.close();
        dispatcher.close();
    }

    @Test
    void scheduleAfterDeliversAtRoughlyTheRightTime() throws Exception {
        WaitForSignalRequest wait = dispatcher.registerWait("k", "r", Duration.ofSeconds(2));
        CountDownLatch received = new CountDownLatch(1);

        Thread waiter = new Thread(() -> {
            try {
                Signal s = dispatcher.awaitSignal(wait.id(), Duration.ofSeconds(3));
                if ("payload".equals(s.payload())) received.countDown();
            } catch (RuntimeException ignored) {}
        });
        waiter.setDaemon(true);
        waiter.start();

        long t0 = System.currentTimeMillis();
        source.scheduleAfter(Duration.ofMillis(150), "k", "payload");
        assertTrue(received.await(2, TimeUnit.SECONDS),
                "signal should arrive within 2s of the 150ms delay");
        long elapsed = System.currentTimeMillis() - t0;
        assertTrue(elapsed >= 100, "fire happened too quickly: " + elapsed + "ms");
        assertTrue(elapsed < 1500, "fire took too long: " + elapsed + "ms");
    }

    @Test
    void scheduleAtPastInstantFiresImmediately() throws Exception {
        WaitForSignalRequest wait = dispatcher.registerWait("k", "r", null);
        CountDownLatch received = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            try {
                dispatcher.awaitSignal(wait.id(), Duration.ofSeconds(1));
                received.countDown();
            } catch (RuntimeException ignored) {}
        });
        waiter.setDaemon(true);
        waiter.start();

        // 5 seconds in the past
        source.scheduleAt(Instant.now().minus(Duration.ofSeconds(5)), "k", "p");
        assertTrue(received.await(1, TimeUnit.SECONDS),
                "past instants should fire immediately");
    }

    @Test
    void scheduleAtFutureInstantFires() throws Exception {
        WaitForSignalRequest wait = dispatcher.registerWait("k", "r", Duration.ofSeconds(2));
        CountDownLatch received = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            try {
                dispatcher.awaitSignal(wait.id(), Duration.ofSeconds(2));
                received.countDown();
            } catch (RuntimeException ignored) {}
        });
        waiter.setDaemon(true);
        waiter.start();

        Instant fireAt = Instant.now().plus(Duration.ofMillis(100));
        WallClockSignalSource.ScheduledFire fire = source.scheduleAt(fireAt, "k", "p");
        assertNotNull(fire.id());
        assertEquals("k", fire.signalKey());
        assertEquals(fireAt, fire.firesAt());

        assertTrue(received.await(2, TimeUnit.SECONDS));
    }

    @Test
    void cancelBeforeFirePreventsDelivery() throws Exception {
        WaitForSignalRequest wait = dispatcher.registerWait("k", "r", null);
        WallClockSignalSource.ScheduledFire fire =
                source.scheduleAfter(Duration.ofMillis(500), "k", "p");
        assertEquals(1, source.activeFireCount());

        assertTrue(source.cancel(fire.id()), "cancel before fire returns true");
        assertEquals(0, source.activeFireCount());

        // Wait should still be PENDING after the would-have-fired-at instant
        Thread.sleep(700);
        assertEquals(WaitStatus.PENDING,
                dispatcher.getWait(wait.id()).orElseThrow().status());
        assertEquals(0, dispatcher.droppedSignals());

        dispatcher.cancel(wait.id());
    }

    @Test
    void cancelOfUnknownIdReturnsFalse() {
        assertFalse(source.cancel("does-not-exist"));
        assertFalse(source.cancel(null));
    }

    @Test
    void activeFiresIsImmutableSnapshot() {
        source.scheduleAfter(Duration.ofMinutes(10), "k", "p");
        List<WallClockSignalSource.ScheduledFire> fires = source.activeFires();
        assertEquals(1, fires.size());
        assertThrows(UnsupportedOperationException.class, () -> fires.add(null));
    }

    @Test
    void multipleSchedulesAreIndependent() throws Exception {
        WaitForSignalRequest a = dispatcher.registerWait("a", "r", Duration.ofSeconds(2));
        WaitForSignalRequest b = dispatcher.registerWait("b", "r", Duration.ofSeconds(2));

        CountDownLatch gotA = new CountDownLatch(1);
        CountDownLatch gotB = new CountDownLatch(1);
        Thread tA = new Thread(() -> {
            try { dispatcher.awaitSignal(a.id(), Duration.ofSeconds(3)); gotA.countDown(); }
            catch (RuntimeException ignored) {}
        });
        Thread tB = new Thread(() -> {
            try { dispatcher.awaitSignal(b.id(), Duration.ofSeconds(3)); gotB.countDown(); }
            catch (RuntimeException ignored) {}
        });
        tA.setDaemon(true); tB.setDaemon(true);
        tA.start(); tB.start();

        source.scheduleAfter(Duration.ofMillis(80), "a", "pa");
        source.scheduleAfter(Duration.ofMillis(80), "b", "pb");

        assertTrue(gotA.await(2, TimeUnit.SECONDS));
        assertTrue(gotB.await(2, TimeUnit.SECONDS));
    }

    @Test
    void closeShutsDownAndDisablesScheduling() {
        source.close();
        assertThrows(IllegalStateException.class,
                () -> source.scheduleAfter(Duration.ofSeconds(1), "k", "p"));
    }

    @Test
    void scheduleRejectsNullsAndBlankKey() {
        assertThrows(NullPointerException.class,
                () -> source.scheduleAt(null, "k", "p"));
        assertThrows(NullPointerException.class,
                () -> source.scheduleAt(Instant.now(), null, "p"));
        assertThrows(NullPointerException.class,
                () -> source.scheduleAt(Instant.now(), "k", null));
        assertThrows(IllegalArgumentException.class,
                () -> source.scheduleAt(Instant.now(), "  ", "p"));
    }
}
