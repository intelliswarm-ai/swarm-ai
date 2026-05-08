package ai.intelliswarm.swarmai.agent.background;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for the background-task primitive (1.0.19+). */
class BackgroundTaskTest {

    /** A trivial runner that immediately marks tasks as either succeeded or failed. */
    private static BackgroundTaskRunner immediateRunner(String result) {
        return task -> {
            task.markRunning();
            task.completeSuccess(result);
        };
    }

    /** A runner that uses a CountDownLatch to control completion timing in tests. */
    private static BackgroundTaskRunner gatedRunner(CountDownLatch gate, String result) {
        return task -> {
            Thread t = new Thread(() -> {
                task.markRunning();
                try {
                    gate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!task.isTerminal()) {
                    task.completeSuccess(result);
                }
            });
            t.setDaemon(true);
            t.start();
        };
    }

    // =================================================================
    // BackgroundTaskStatus enum
    // =================================================================
    @Nested
    @DisplayName("BackgroundTaskStatus")
    class StatusEnum {

        @Test
        void terminalClassification() {
            assertFalse(BackgroundTaskStatus.PENDING.isTerminal());
            assertFalse(BackgroundTaskStatus.RUNNING.isTerminal());
            assertTrue(BackgroundTaskStatus.COMPLETED.isTerminal());
            assertTrue(BackgroundTaskStatus.FAILED.isTerminal());
            assertTrue(BackgroundTaskStatus.CANCELLED.isTerminal());
        }
    }

    // =================================================================
    // BackgroundTask record
    // =================================================================
    @Nested
    @DisplayName("BackgroundTask")
    class TaskRecord {

        @Test
        void freshTaskIsPending() {
            BackgroundTask t = new BackgroundTask("id", "do thing");
            assertEquals(BackgroundTaskStatus.PENDING, t.status());
            assertTrue(t.output().isEmpty());
            assertTrue(t.error().isEmpty());
            assertTrue(t.completedAt().isEmpty());
            assertFalse(t.isTerminal());
            assertNotNull(t.spawnedAt());
        }

        @Test
        void blankIdRejected() {
            assertThrows(IllegalArgumentException.class, () -> new BackgroundTask("", "p"));
        }

        @Test
        void blankPromptRejected() {
            assertThrows(IllegalArgumentException.class, () -> new BackgroundTask("id", ""));
        }

        @Test
        void completeSuccessSetsOutputAndStatus() {
            BackgroundTask t = new BackgroundTask("id", "p");
            t.markRunning();
            t.completeSuccess("hello");

            assertEquals(BackgroundTaskStatus.COMPLETED, t.status());
            assertEquals("hello", t.output().orElseThrow());
            assertTrue(t.completedAt().isPresent());
            assertTrue(t.isTerminal());
        }

        @Test
        void completeFailureSetsErrorAndStatus() {
            BackgroundTask t = new BackgroundTask("id", "p");
            t.markRunning();
            t.completeFailure("boom");

            assertEquals(BackgroundTaskStatus.FAILED, t.status());
            assertEquals("boom", t.error().orElseThrow());
            assertTrue(t.isTerminal());
        }

        @Test
        void cancelOnPendingMarksCancelled() {
            BackgroundTask t = new BackgroundTask("id", "p");
            assertTrue(t.cancel());
            assertEquals(BackgroundTaskStatus.CANCELLED, t.status());
        }

        @Test
        void cancelOnTerminalIsNoOp() {
            BackgroundTask t = new BackgroundTask("id", "p");
            t.markRunning();
            t.completeSuccess("x");
            assertFalse(t.cancel());
            assertEquals(BackgroundTaskStatus.COMPLETED, t.status());
        }

        @Test
        void completeAfterCancelIsIgnored() {
            BackgroundTask t = new BackgroundTask("id", "p");
            t.cancel();
            t.completeSuccess("late");
            assertEquals(BackgroundTaskStatus.CANCELLED, t.status(),
                    "cancel must win over a late completion");
            assertTrue(t.output().isEmpty());
        }

        @Test
        void awaitTerminalReturnsImmediatelyIfDone() throws Exception {
            BackgroundTask t = new BackgroundTask("id", "p");
            t.markRunning();
            t.completeSuccess("x");
            assertEquals(BackgroundTaskStatus.COMPLETED,
                    t.awaitTerminal(Duration.ofMillis(10)));
        }

        @Test
        void awaitTerminalTimesOutIfNotDone() {
            BackgroundTask t = new BackgroundTask("id", "p");
            assertThrows(TimeoutException.class,
                    () -> t.awaitTerminal(Duration.ofMillis(50)));
            assertFalse(t.isTerminal());
        }

        @Test
        void awaitTerminalReturnsCancelledStatus() throws Exception {
            BackgroundTask t = new BackgroundTask("id", "p");
            new Thread(() -> {
                try { Thread.sleep(20); } catch (InterruptedException ignored) {}
                t.cancel();
            }).start();
            assertEquals(BackgroundTaskStatus.CANCELLED,
                    t.awaitTerminal(Duration.ofSeconds(2)));
        }
    }

    // =================================================================
    // BackgroundTaskRegistry
    // =================================================================
    @Nested
    @DisplayName("BackgroundTaskRegistry")
    class Registry {

        @Test
        void spawnReturnsTaskWithUniqueId() {
            BackgroundTaskRegistry reg = new BackgroundTaskRegistry(immediateRunner("r"));
            BackgroundTask a = reg.spawn("first");
            BackgroundTask b = reg.spawn("second");
            assertNotEquals(a.id(), b.id());
            assertEquals("first", a.prompt());
            assertEquals("second", b.prompt());
        }

        @Test
        void blankPromptRejected() {
            BackgroundTaskRegistry reg = new BackgroundTaskRegistry(task -> {});
            assertThrows(IllegalArgumentException.class, () -> reg.spawn(""));
            assertThrows(IllegalArgumentException.class, () -> reg.spawn("  "));
        }

        @Test
        void getReturnsSpawnedTask() {
            BackgroundTaskRegistry reg = new BackgroundTaskRegistry(immediateRunner("r"));
            BackgroundTask t = reg.spawn("p");
            assertSame(t, reg.get(t.id()).orElseThrow());
        }

        @Test
        void getReturnsEmptyForUnknownId() {
            BackgroundTaskRegistry reg = new BackgroundTaskRegistry(task -> {});
            assertTrue(reg.get("nope").isEmpty());
            assertTrue(reg.get(null).isEmpty());
        }

        @Test
        void allReturnsSpawnOrder() {
            BackgroundTaskRegistry reg = new BackgroundTaskRegistry(task -> {});
            BackgroundTask a = reg.spawn("a");
            BackgroundTask b = reg.spawn("b");
            BackgroundTask c = reg.spawn("c");
            List<BackgroundTask> all = reg.all();
            assertEquals(3, all.size());
            assertEquals(a.id(), all.get(0).id());
            assertEquals(b.id(), all.get(1).id());
            assertEquals(c.id(), all.get(2).id());
        }

        @Test
        void activeAndCompletedSplitsBySection() {
            BackgroundTaskRegistry reg = new BackgroundTaskRegistry(immediateRunner("done"));
            BackgroundTask done = reg.spawn("p1");
            // Let immediate runner finish before spawning the second
            // (immediateRunner completes synchronously inside runAsync)
            // For active test: switch to a never-finishing runner
            BackgroundTaskRegistry reg2 = new BackgroundTaskRegistry(task -> {});
            BackgroundTask running = reg2.spawn("p2");

            assertEquals(0, reg.active().size());
            assertEquals(1, reg.completed().size());
            assertEquals(1, reg2.active().size());
            assertEquals(0, reg2.completed().size());
            assertSame(done, reg.completed().get(0));
            assertSame(running, reg2.active().get(0));
        }

        @Test
        void allReturnsImmutableSnapshot() {
            BackgroundTaskRegistry reg = new BackgroundTaskRegistry(task -> {});
            reg.spawn("a");
            assertThrows(UnsupportedOperationException.class, () -> reg.all().clear());
        }

        @Test
        void completionListenerFiresOnSuccess() throws InterruptedException {
            BackgroundTaskRegistry reg = new BackgroundTaskRegistry(immediateRunner("done"));
            CountDownLatch fired = new CountDownLatch(1);
            List<BackgroundTask> seen = new ArrayList<>();
            reg.addCompletionListener(t -> {
                seen.add(t);
                fired.countDown();
            });
            BackgroundTask t = reg.spawn("p");
            assertTrue(fired.await(2, TimeUnit.SECONDS), "listener should fire within 2s");
            assertSame(t, seen.get(0));
        }

        @Test
        void completionListenerFiresOnCancel() throws InterruptedException {
            CountDownLatch gate = new CountDownLatch(1);  // never opens
            BackgroundTaskRegistry reg = new BackgroundTaskRegistry(gatedRunner(gate, "x"));
            CountDownLatch fired = new CountDownLatch(1);
            reg.addCompletionListener(t -> fired.countDown());

            BackgroundTask t = reg.spawn("p");
            // Give the gated runner time to mark RUNNING
            Thread.sleep(50);
            t.cancel();
            assertTrue(fired.await(2, TimeUnit.SECONDS),
                    "listener should fire on cancel within 2s");
        }

        @Test
        void throwingListenerDoesntBreakOthers() throws InterruptedException {
            BackgroundTaskRegistry reg = new BackgroundTaskRegistry(immediateRunner("done"));
            AtomicInteger goodCalls = new AtomicInteger();
            CountDownLatch fired = new CountDownLatch(1);

            reg.addCompletionListener(t -> { throw new RuntimeException("boom"); });
            reg.addCompletionListener(t -> {
                goodCalls.incrementAndGet();
                fired.countDown();
            });

            reg.spawn("p");
            assertTrue(fired.await(2, TimeUnit.SECONDS));
            assertEquals(1, goodCalls.get());
        }

        @Test
        void removeListenerStops() throws InterruptedException {
            BackgroundTaskRegistry reg = new BackgroundTaskRegistry(immediateRunner("done"));
            AtomicInteger count = new AtomicInteger();
            java.util.function.Consumer<BackgroundTask> listener = t -> count.incrementAndGet();
            reg.addCompletionListener(listener);
            reg.removeCompletionListener(listener);

            reg.spawn("p");
            // Let the watcher thread settle
            Thread.sleep(150);
            assertEquals(0, count.get(), "removed listener should not fire");
        }
    }

    // =================================================================
    // BackgroundTaskTools — agent-callable surface
    // =================================================================
    @Nested
    @DisplayName("BackgroundTaskTools")
    class Tools {

        @Test
        void spawnReturnsIdForValidPrompt() {
            BackgroundTaskRegistry reg = new BackgroundTaskRegistry(immediateRunner("r"));
            BackgroundTaskTools.SpawnInput in = new BackgroundTaskTools.SpawnInput();
            in.prompt = "do thing";

            String out = BackgroundTaskTools.handleSpawn(reg, in);
            assertTrue(out.startsWith("Spawned task bg_"), out);
            assertTrue(out.contains("background_output"));
            assertEquals(1, reg.size());
        }

        @Test
        void spawnRejectsBlankPrompt() {
            BackgroundTaskRegistry reg = new BackgroundTaskRegistry(immediateRunner("r"));
            BackgroundTaskTools.SpawnInput in = new BackgroundTaskTools.SpawnInput();
            in.prompt = "  ";
            assertTrue(BackgroundTaskTools.handleSpawn(reg, in).startsWith("Error:"));
        }

        @Test
        void listEmptyRegistry() {
            BackgroundTaskRegistry reg = new BackgroundTaskRegistry(task -> {});
            assertEquals("No tasks yet.", BackgroundTaskTools.handleList(reg, null));
        }

        @Test
        void listShowsEachTask() {
            BackgroundTaskRegistry reg = new BackgroundTaskRegistry(immediateRunner("r"));
            reg.spawn("a");
            reg.spawn("b");
            String out = BackgroundTaskTools.handleList(reg, new BackgroundTaskTools.ListInput());
            assertTrue(out.contains("2 task(s)"));
            assertTrue(out.contains("COMPLETED"),
                    "immediate runner should leave tasks COMPLETED: " + out);
        }

        @Test
        void listFiltersByStatus() {
            BackgroundTaskRegistry reg = new BackgroundTaskRegistry(immediateRunner("r"));
            reg.spawn("a");
            reg.spawn("b");
            BackgroundTaskTools.ListInput in = new BackgroundTaskTools.ListInput();
            in.status = "completed";  // case-insensitive
            String out = BackgroundTaskTools.handleList(reg, in);
            assertTrue(out.contains("2 task(s)"));
        }

        @Test
        void listInvalidStatusReturnsError() {
            BackgroundTaskRegistry reg = new BackgroundTaskRegistry(task -> {});
            reg.spawn("a");
            BackgroundTaskTools.ListInput in = new BackgroundTaskTools.ListInput();
            in.status = "BOGUS";
            String out = BackgroundTaskTools.handleList(reg, in);
            assertTrue(out.startsWith("Error:"), out);
        }

        @Test
        void outputForCompletedTask() {
            BackgroundTaskRegistry reg = new BackgroundTaskRegistry(immediateRunner("the answer"));
            BackgroundTask t = reg.spawn("p");

            BackgroundTaskTools.IdInput in = new BackgroundTaskTools.IdInput();
            in.id = t.id();
            String out = BackgroundTaskTools.handleOutput(reg, in);
            assertTrue(out.startsWith("Status: COMPLETED"));
            assertTrue(out.contains("the answer"));
        }

        @Test
        void outputForRunningTask() {
            BackgroundTaskRegistry reg = new BackgroundTaskRegistry(task -> task.markRunning());
            BackgroundTask t = reg.spawn("p");

            BackgroundTaskTools.IdInput in = new BackgroundTaskTools.IdInput();
            in.id = t.id();
            String out = BackgroundTaskTools.handleOutput(reg, in);
            assertTrue(out.startsWith("Status: RUNNING"));
            assertTrue(out.contains("poll again"));
        }

        @Test
        void outputForFailedTask() {
            BackgroundTaskRegistry reg = new BackgroundTaskRegistry(task -> {
                task.markRunning();
                task.completeFailure("disk full");
            });
            BackgroundTask t = reg.spawn("p");

            BackgroundTaskTools.IdInput in = new BackgroundTaskTools.IdInput();
            in.id = t.id();
            String out = BackgroundTaskTools.handleOutput(reg, in);
            assertTrue(out.startsWith("Status: FAILED"));
            assertTrue(out.contains("disk full"));
        }

        @Test
        void outputForUnknownIdReturnsError() {
            BackgroundTaskRegistry reg = new BackgroundTaskRegistry(task -> {});
            BackgroundTaskTools.IdInput in = new BackgroundTaskTools.IdInput();
            in.id = "bg_nonexistent";
            String out = BackgroundTaskTools.handleOutput(reg, in);
            assertTrue(out.startsWith("Error: no task with id"));
        }

        @Test
        void stopForActiveTask() {
            BackgroundTaskRegistry reg = new BackgroundTaskRegistry(task -> task.markRunning());
            BackgroundTask t = reg.spawn("p");

            BackgroundTaskTools.IdInput in = new BackgroundTaskTools.IdInput();
            in.id = t.id();
            String out = BackgroundTaskTools.handleStop(reg, in);
            assertTrue(out.startsWith("Cancelled task"));
            assertEquals(BackgroundTaskStatus.CANCELLED, t.status());
        }

        @Test
        void stopForTerminalTaskReportsAlreadyTerminal() {
            BackgroundTaskRegistry reg = new BackgroundTaskRegistry(immediateRunner("r"));
            BackgroundTask t = reg.spawn("p");

            BackgroundTaskTools.IdInput in = new BackgroundTaskTools.IdInput();
            in.id = t.id();
            String out = BackgroundTaskTools.handleStop(reg, in);
            assertTrue(out.startsWith("Already terminal"));
            assertTrue(out.contains("COMPLETED"));
        }

        @Test
        void stopBlankIdRejected() {
            BackgroundTaskRegistry reg = new BackgroundTaskRegistry(task -> {});
            BackgroundTaskTools.IdInput in = new BackgroundTaskTools.IdInput();
            in.id = "  ";
            assertTrue(BackgroundTaskTools.handleStop(reg, in).startsWith("Error:"));
        }

        @Test
        void allFactoryReturnsFourCallbacks() {
            BackgroundTaskRegistry reg = new BackgroundTaskRegistry(task -> {});
            assertEquals(4, BackgroundTaskTools.all(reg).size());
            // names match expected
            List<String> names = BackgroundTaskTools.all(reg).stream()
                    .map(cb -> cb.getToolDefinition().name())
                    .toList();
            assertTrue(names.contains("background_spawn"));
            assertTrue(names.contains("background_list"));
            assertTrue(names.contains("background_output"));
            assertTrue(names.contains("background_stop"));
        }
    }

    // =================================================================
    // End-to-end: spawn → poll → retrieve
    // =================================================================
    @Nested
    @DisplayName("end-to-end")
    class EndToEnd {

        @Test
        void multiTaskFlow() throws Exception {
            BackgroundTaskRegistry reg = new BackgroundTaskRegistry(immediateRunner("done"));

            // Spawn 3 tasks
            BackgroundTask t1 = reg.spawn("p1");
            BackgroundTask t2 = reg.spawn("p2");
            BackgroundTask t3 = reg.spawn("p3");

            // Wait for each to terminate
            for (BackgroundTask t : List.of(t1, t2, t3)) {
                assertEquals(BackgroundTaskStatus.COMPLETED,
                        t.awaitTerminal(Duration.ofSeconds(2)));
            }

            assertEquals(3, reg.completed().size());
            assertEquals(0, reg.active().size());
            // each task's output is the runner's result
            assertEquals("done", t1.output().orElseThrow());
        }

        @Test
        void cancelBeforeRunnerPicksUpStillCancels() throws InterruptedException {
            CountDownLatch gate = new CountDownLatch(1); // never opens
            BackgroundTaskRegistry reg = new BackgroundTaskRegistry(gatedRunner(gate, "x"));
            BackgroundTask t = reg.spawn("p");

            // Wait for runner to mark RUNNING
            Thread.sleep(100);
            assertTrue(t.cancel());
            assertEquals(BackgroundTaskStatus.CANCELLED, t.status());
            // runner thread is blocked but task is terminal
        }
    }
}
