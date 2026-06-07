package ai.intelliswarm.swarmai.state.reminder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** SystemReminderChannel + Renderer unit tests. */
class SystemReminderChannelTest {

    @Nested
    @DisplayName("SystemReminder record")
    class ReminderRecord {

        @Test
        void infoFactoryAssignsIdAndTimestamp() {
            SystemReminder r = SystemReminder.info("task-list", "you have 2 pending");
            assertNotNull(r.id());
            assertEquals("task-list", r.source());
            assertEquals("you have 2 pending", r.message());
            assertEquals(SystemReminder.Severity.INFO, r.severity());
            assertNotNull(r.postedAt());
            assertTrue(r.postedAt().isBefore(Instant.now().plusSeconds(1)));
        }

        @Test
        void warnFactorySetsSeverity() {
            SystemReminder r = SystemReminder.warn("drift", "budget at 4/5");
            assertEquals(SystemReminder.Severity.WARN, r.severity());
        }

        @Test
        void blankSourceRejected() {
            assertThrows(IllegalArgumentException.class, () -> SystemReminder.info("", "ok"));
            assertThrows(IllegalArgumentException.class, () -> SystemReminder.info("   ", "ok"));
        }

        @Test
        void blankMessageRejected() {
            assertThrows(IllegalArgumentException.class, () -> SystemReminder.info("src", ""));
            assertThrows(IllegalArgumentException.class, () -> SystemReminder.info("src", "  "));
        }

        @Test
        void nullSeverityDefaultsToInfo() {
            SystemReminder r = new SystemReminder("id", "src", "msg", null, Instant.now());
            assertEquals(SystemReminder.Severity.INFO, r.severity());
        }

        @Test
        void nullPostedAtDefaultsToNow() {
            SystemReminder r = new SystemReminder("id", "src", "msg",
                    SystemReminder.Severity.INFO, null);
            assertNotNull(r.postedAt());
        }
    }

    @Nested
    @DisplayName("post / drain semantics")
    class PostDrain {

        @Test
        void freshChannelHasNoPending() {
            SystemReminderChannel ch = new SystemReminderChannel();
            assertEquals(0, ch.pendingCount());
            assertTrue(ch.drain().isEmpty());
            assertTrue(ch.peek().isEmpty());
        }

        @Test
        void postThenDrainReturnsInOrder() {
            SystemReminderChannel ch = new SystemReminderChannel();
            ch.post("src1", "first");
            ch.post("src2", "second");
            ch.postWarn("src3", "third");

            assertEquals(3, ch.pendingCount());
            List<SystemReminder> drained = ch.drain();
            assertEquals(3, drained.size());
            assertEquals("first", drained.get(0).message());
            assertEquals("second", drained.get(1).message());
            assertEquals("third", drained.get(2).message());
            assertEquals(SystemReminder.Severity.WARN, drained.get(2).severity());
        }

        @Test
        void drainClearsPending() {
            SystemReminderChannel ch = new SystemReminderChannel();
            ch.post("src", "hi");
            assertEquals(1, ch.pendingCount());

            ch.drain();
            assertEquals(0, ch.pendingCount());
            assertTrue(ch.drain().isEmpty(), "second drain returns empty");
        }

        @Test
        void peekDoesNotConsume() {
            SystemReminderChannel ch = new SystemReminderChannel();
            ch.post("src", "hi");
            assertEquals(1, ch.peek().size());
            assertEquals(1, ch.peek().size(), "second peek still sees the same item");
            assertEquals(1, ch.pendingCount());
        }

        @Test
        void drainResultIsImmutable() {
            SystemReminderChannel ch = new SystemReminderChannel();
            ch.post("src", "hi");
            List<SystemReminder> drained = ch.drain();
            assertThrows(UnsupportedOperationException.class,
                    () -> drained.add(SystemReminder.info("x", "y")));
        }

        @Test
        void postFullReminderUsesIdProvided() {
            SystemReminderChannel ch = new SystemReminderChannel();
            SystemReminder r = new SystemReminder("explicit-id", "src", "msg",
                    SystemReminder.Severity.INFO, Instant.now());
            ch.post(r);

            List<SystemReminder> drained = ch.drain();
            assertEquals("explicit-id", drained.get(0).id());
        }

        @Test
        void postReminderRejectsNull() {
            SystemReminderChannel ch = new SystemReminderChannel();
            assertThrows(NullPointerException.class, () -> ch.post(null));
        }
    }

    @Nested
    @DisplayName("postOnce dedup")
    class PostOnce {

        @Test
        void firstPostOnceSucceeds() {
            SystemReminderChannel ch = new SystemReminderChannel();
            SystemReminder r = ch.postOnce("drift", "budget at 4/5",
                    SystemReminder.Severity.WARN);
            assertNotNull(r);
            assertEquals(SystemReminder.Severity.WARN, r.severity());
            assertEquals(1, ch.pendingCount());
        }

        @Test
        void duplicatePostOnceReturnsNull() {
            SystemReminderChannel ch = new SystemReminderChannel();
            assertNotNull(ch.postOnce("src", "msg", SystemReminder.Severity.INFO));
            assertNull(ch.postOnce("src", "msg", SystemReminder.Severity.INFO));
            assertEquals(1, ch.pendingCount());
        }

        @Test
        void differentSourceOrMessageEachPostsOnce() {
            SystemReminderChannel ch = new SystemReminderChannel();
            assertNotNull(ch.postOnce("src", "a", SystemReminder.Severity.INFO));
            assertNotNull(ch.postOnce("src", "b", SystemReminder.Severity.INFO),
                    "different message should post");
            assertNotNull(ch.postOnce("other", "a", SystemReminder.Severity.INFO),
                    "different source should post");
            assertEquals(3, ch.pendingCount());
        }

        @Test
        void onceDedupSurvivesDrain() {
            SystemReminderChannel ch = new SystemReminderChannel();
            assertNotNull(ch.postOnce("src", "msg", SystemReminder.Severity.INFO));
            ch.drain();
            assertNull(ch.postOnce("src", "msg", SystemReminder.Severity.INFO),
                    "even after drain, the same once-key must not re-post");
        }

        @Test
        void nullSeverityDefaultsToInfo() {
            SystemReminderChannel ch = new SystemReminderChannel();
            SystemReminder r = ch.postOnce("src", "msg", null);
            assertEquals(SystemReminder.Severity.INFO, r.severity());
        }
    }

    @Nested
    @DisplayName("listeners")
    class Listeners {

        @Test
        void listenerFiresOnPost() {
            SystemReminderChannel ch = new SystemReminderChannel();
            List<SystemReminder> seen = new ArrayList<>();
            ch.addListener(seen::add);

            ch.post("src", "hello");
            ch.postWarn("drift", "warn");

            assertEquals(2, seen.size());
            assertEquals("hello", seen.get(0).message());
            assertEquals(SystemReminder.Severity.WARN, seen.get(1).severity());
        }

        @Test
        void listenerDoesNotFireOnDrain() {
            SystemReminderChannel ch = new SystemReminderChannel();
            AtomicInteger calls = new AtomicInteger();
            ch.addListener(r -> calls.incrementAndGet());

            ch.post("src", "hi");
            assertEquals(1, calls.get());
            ch.drain();
            assertEquals(1, calls.get(), "drain must not fire listener again");
        }

        @Test
        void listenerFiresOnSuccessfulPostOnce() {
            SystemReminderChannel ch = new SystemReminderChannel();
            AtomicInteger calls = new AtomicInteger();
            ch.addListener(r -> calls.incrementAndGet());

            ch.postOnce("src", "msg", SystemReminder.Severity.INFO);
            ch.postOnce("src", "msg", SystemReminder.Severity.INFO); // dedup
            assertEquals(1, calls.get(), "only the first postOnce fires the listener");
        }

        @Test
        void removingListenerStopsFutureCallbacks() {
            SystemReminderChannel ch = new SystemReminderChannel();
            AtomicInteger calls = new AtomicInteger();
            java.util.function.Consumer<SystemReminder> listener = r -> calls.incrementAndGet();
            ch.addListener(listener);

            ch.post("src", "first");
            ch.removeListener(listener);
            ch.post("src", "second");

            assertEquals(1, calls.get());
        }

        @Test
        void throwingListenerDoesNotBreakPost() {
            SystemReminderChannel ch = new SystemReminderChannel();
            AtomicInteger goodCalls = new AtomicInteger();
            ch.addListener(r -> { throw new RuntimeException("boom"); });
            ch.addListener(r -> goodCalls.incrementAndGet());

            assertDoesNotThrow(() -> ch.post("src", "msg"));
            assertEquals(1, goodCalls.get(), "second listener still ran");
            assertEquals(1, ch.pendingCount(), "post still committed despite throwing listener");
        }
    }

    @Nested
    @DisplayName("thread safety")
    class ThreadSafety {

        @Test
        void parallelPostsAndDrainsAccountForAllReminders() throws Exception {
            SystemReminderChannel ch = new SystemReminderChannel();
            int producers = 4;
            int perProducer = 250;
            int totalExpected = producers * perProducer;

            ExecutorService exec = Executors.newFixedThreadPool(producers + 1);
            CountDownLatch start = new CountDownLatch(1);
            List<SystemReminder> drained = new ArrayList<>();
            Object drainedLock = new Object();

            // Producers
            for (int i = 0; i < producers; i++) {
                final int producerId = i;
                exec.submit(() -> {
                    try {
                        start.await();
                        for (int j = 0; j < perProducer; j++) {
                            ch.post("p" + producerId, "msg-" + j);
                        }
                    } catch (InterruptedException ignored) {}
                });
            }
            // Drainer — keeps draining until total drained == expected
            exec.submit(() -> {
                try {
                    start.await();
                    while (true) {
                        List<SystemReminder> got = ch.drain();
                        synchronized (drainedLock) {
                            drained.addAll(got);
                            if (drained.size() >= totalExpected) return;
                        }
                        Thread.sleep(1);
                    }
                } catch (InterruptedException ignored) {}
            });

            start.countDown();
            exec.shutdown();
            assertTrue(exec.awaitTermination(10, TimeUnit.SECONDS),
                    "all producers + drainer finished");

            assertEquals(totalExpected, drained.size(),
                    "every reminder appears exactly once across drains");
            // No duplicates by id
            long distinctIds = drained.stream().map(SystemReminder::id).distinct().count();
            assertEquals(totalExpected, distinctIds);
        }
    }

    @Nested
    @DisplayName("SystemReminderRenderer")
    class Renderer {

        @Test
        void emptyListReturnsEmptyString() {
            SystemReminderRenderer renderer = new SystemReminderRenderer();
            assertEquals("", renderer.render(List.of()));
            assertEquals("", renderer.renderEach(List.of()));
        }

        @Test
        void singleInfoReminderRendersBlock() {
            SystemReminderRenderer renderer = new SystemReminderRenderer();
            String out = renderer.render(List.of(SystemReminder.info("task-list",
                    "you have 2 pending tasks")));
            assertEquals("""
                    <system-reminder>
                    [task-list] you have 2 pending tasks
                    </system-reminder>""", out);
        }

        @Test
        void warnSeverityIsTaggedExplicitly() {
            SystemReminderRenderer renderer = new SystemReminderRenderer();
            String out = renderer.render(List.of(SystemReminder.warn("drift",
                    "budget at 4/5")));
            assertTrue(out.contains("[drift WARN]"),
                    "WARN reminders are explicitly tagged: " + out);
        }

        @Test
        void multipleRemindersSharedBlock() {
            SystemReminderRenderer renderer = new SystemReminderRenderer();
            List<SystemReminder> reminders = List.of(
                    SystemReminder.info("task-list", "2 in-progress"),
                    SystemReminder.warn("drift", "4/5 used"));
            String out = renderer.render(reminders);

            // Single open + close tag, both reminders inside
            assertEquals(1, countOccurrences(out, "<system-reminder>"));
            assertEquals(1, countOccurrences(out, "</system-reminder>"));
            assertTrue(out.contains("[task-list] 2 in-progress"));
            assertTrue(out.contains("[drift WARN] 4/5 used"));
        }

        @Test
        void renderEachWrapsEachReminderInOwnBlock() {
            SystemReminderRenderer renderer = new SystemReminderRenderer();
            List<SystemReminder> reminders = List.of(
                    SystemReminder.info("a", "first"),
                    SystemReminder.info("b", "second"),
                    SystemReminder.info("c", "third"));
            String out = renderer.renderEach(reminders);

            assertEquals(3, countOccurrences(out, "<system-reminder>"));
            assertEquals(3, countOccurrences(out, "</system-reminder>"));
            assertTrue(out.contains("[a] first"));
            assertTrue(out.contains("[b] second"));
            assertTrue(out.contains("[c] third"));
        }

        @Test
        void rendererPreservesPostOrder() {
            SystemReminderRenderer renderer = new SystemReminderRenderer();
            List<SystemReminder> reminders = IntStream.range(0, 5)
                    .mapToObj(i -> SystemReminder.info("src", "msg-" + i))
                    .collect(Collectors.toList());
            String out = renderer.render(reminders);

            int idx = -1;
            for (int i = 0; i < 5; i++) {
                int next = out.indexOf("msg-" + i);
                assertTrue(next > idx, "msg-" + i + " appears after msg-" + (i - 1));
                idx = next;
            }
        }
    }

    @Nested
    @DisplayName("SystemReminderPromptDecorator")
    class PromptDecorator {

        @Test
        void drainAndRenderReturnsEmptyWhenChannelIsEmpty() {
            SystemReminderChannel ch = new SystemReminderChannel();
            SystemReminderPromptDecorator d = new SystemReminderPromptDecorator(ch);
            assertEquals("", d.drainAndRender());
        }

        @Test
        void drainAndRenderConsumesChannel() {
            SystemReminderChannel ch = new SystemReminderChannel();
            ch.post("src", "msg");
            SystemReminderPromptDecorator d = new SystemReminderPromptDecorator(ch);

            String first = d.drainAndRender();
            assertTrue(first.contains("[src] msg"));
            assertEquals(0, ch.pendingCount());
            assertEquals("", d.drainAndRender(), "second drain returns empty");
        }

        @Test
        void prependLeavesUserMessageUnchangedWhenChannelEmpty() {
            SystemReminderChannel ch = new SystemReminderChannel();
            SystemReminderPromptDecorator d = new SystemReminderPromptDecorator(ch);
            assertEquals("hello user", d.prepend("hello user"));
        }

        @Test
        void prependPlacesBlockBeforeUserMessage() {
            SystemReminderChannel ch = new SystemReminderChannel();
            ch.post("task-list", "you have 2 in-progress");
            SystemReminderPromptDecorator d = new SystemReminderPromptDecorator(ch);

            String out = d.prepend("Continue working the plan.");
            int blockEnd = out.indexOf("</system-reminder>");
            int userStart = out.indexOf("Continue working the plan.");
            assertTrue(blockEnd > 0 && userStart > blockEnd,
                    "<system-reminder> block must appear before the user message");
            assertTrue(out.contains("[task-list] you have 2 in-progress"));
        }

        @Test
        void prependRejectsNullUserMessage() {
            SystemReminderChannel ch = new SystemReminderChannel();
            SystemReminderPromptDecorator d = new SystemReminderPromptDecorator(ch);
            assertThrows(NullPointerException.class, () -> d.prepend(null));
        }

        @Test
        void prependDrainsTheChannel() {
            SystemReminderChannel ch = new SystemReminderChannel();
            ch.post("src", "first");
            ch.post("src", "second");
            SystemReminderPromptDecorator d = new SystemReminderPromptDecorator(ch);

            d.prepend("user input");
            assertEquals(0, ch.pendingCount(),
                    "prepend must drain reminders so they don't repeat next call");
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
