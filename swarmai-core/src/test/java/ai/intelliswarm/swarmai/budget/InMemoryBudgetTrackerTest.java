package ai.intelliswarm.swarmai.budget;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("InMemoryBudgetTracker Tests")
class InMemoryBudgetTrackerTest {

    private InMemoryBudgetTracker tracker;
    private BudgetPolicy defaultPolicy;

    @BeforeEach
    void setUp() {
        defaultPolicy = BudgetPolicy.builder()
                .maxTotalTokens(100_000)
                .maxCostUsd(1.0)
                .onExceeded(BudgetPolicy.BudgetAction.WARN)
                .warningThresholdPercent(80.0)
                .build();
        tracker = new InMemoryBudgetTracker(defaultPolicy);
    }

    @Nested
    @DisplayName("recordUsage()")
    class RecordUsageTests {

        @Test
        @DisplayName("accumulates prompt and completion tokens correctly")
        void accumulatesTokensCorrectly() {
            tracker.recordUsage("wf-1", 1000, 500, "gpt-4o-mini");
            tracker.recordUsage("wf-1", 2000, 1000, "gpt-4o-mini");

            BudgetSnapshot snapshot = tracker.getSnapshot("wf-1");
            assertEquals(3000, snapshot.promptTokensUsed());
            assertEquals(1500, snapshot.completionTokensUsed());
            assertEquals(4500, snapshot.totalTokensUsed());
        }

        @Test
        @DisplayName("accumulates cost correctly")
        void accumulatesCostCorrectly() {
            // gpt-4o: $2.50/1M input, $10.00/1M output
            tracker.recordUsage("wf-1", 100_000, 50_000, "gpt-4o");

            BudgetSnapshot snapshot = tracker.getSnapshot("wf-1");
            double expectedCost = (100_000 / 1_000_000.0) * 2.50 + (50_000 / 1_000_000.0) * 10.00;
            assertEquals(expectedCost, snapshot.estimatedCostUsd(), 0.0001);
        }

        @Test
        @DisplayName("tracks separate workflows independently")
        void tracksWorkflowsIndependently() {
            tracker.recordUsage("wf-1", 1000, 500, "gpt-4o-mini");
            tracker.recordUsage("wf-2", 2000, 1000, "gpt-4o-mini");

            BudgetSnapshot snap1 = tracker.getSnapshot("wf-1");
            BudgetSnapshot snap2 = tracker.getSnapshot("wf-2");

            assertEquals(1500, snap1.totalTokensUsed());
            assertEquals(3000, snap2.totalTokensUsed());
        }
    }

    @Nested
    @DisplayName("isExceeded()")
    class IsExceededTests {

        @Test
        @DisplayName("detects token budget exceeded")
        void detectsTokenBudgetExceeded() {
            // Policy: max 100K tokens
            tracker.recordUsage("wf-1", 60_000, 50_000, "ollama/llama3");

            assertTrue(tracker.isExceeded("wf-1"));
            BudgetSnapshot snapshot = tracker.getSnapshot("wf-1");
            assertTrue(snapshot.tokenBudgetExceeded());
        }

        @Test
        @DisplayName("detects cost budget exceeded")
        void detectsCostBudgetExceeded() {
            // Policy: max $1.00. gpt-4o: $2.50/1M input + $10.00/1M output
            // 500K prompt = $1.25 (already exceeds $1.00)
            tracker.recordUsage("wf-1", 500_000, 0, "gpt-4o");

            BudgetSnapshot snapshot = tracker.getSnapshot("wf-1");
            assertTrue(snapshot.costBudgetExceeded());
            assertTrue(snapshot.isExceeded());
        }

        @Test
        @DisplayName("returns false when under budget")
        void returnsFalseWhenUnderBudget() {
            tracker.recordUsage("wf-1", 100, 50, "gpt-4o-mini");

            assertFalse(tracker.isExceeded("wf-1"));
        }

        @Test
        @DisplayName("returns false for unknown workflow")
        void returnsFalseForUnknownWorkflow() {
            assertFalse(tracker.isExceeded("unknown-wf"));
        }
    }

    @Nested
    @DisplayName("HARD_STOP behavior")
    class HardStopTests {

        @Test
        @DisplayName("throws BudgetExceededException on token limit with HARD_STOP")
        void throwsOnTokenLimitHardStop() {
            BudgetPolicy hardPolicy = BudgetPolicy.builder()
                    .maxTotalTokens(1000)
                    .maxCostUsd(100.0)
                    .onExceeded(BudgetPolicy.BudgetAction.HARD_STOP)
                    .build();
            tracker.setBudgetPolicy("wf-1", hardPolicy);

            BudgetExceededException ex = assertThrows(BudgetExceededException.class, () ->
                    tracker.recordUsage("wf-1", 800, 300, "gpt-4o"));

            assertEquals("wf-1", ex.getWorkflowId());
            assertNotNull(ex.getSnapshot());
            assertTrue(ex.getSnapshot().tokenBudgetExceeded());
        }

        @Test
        @DisplayName("throws BudgetExceededException on cost limit with HARD_STOP")
        void throwsOnCostLimitHardStop() {
            BudgetPolicy hardPolicy = BudgetPolicy.builder()
                    .maxTotalTokens(100_000_000)
                    .maxCostUsd(0.01)
                    .onExceeded(BudgetPolicy.BudgetAction.HARD_STOP)
                    .build();
            tracker.setBudgetPolicy("wf-1", hardPolicy);

            // gpt-4o: 100K tokens * $2.50/1M = $0.25 >> $0.01
            BudgetExceededException ex = assertThrows(BudgetExceededException.class, () ->
                    tracker.recordUsage("wf-1", 100_000, 0, "gpt-4o"));

            assertTrue(ex.getSnapshot().costBudgetExceeded());
        }

        @Test
        @DisplayName("does not throw in WARN mode when exceeded")
        void doesNotThrowInWarnMode() {
            // Default policy is WARN
            assertDoesNotThrow(() ->
                    tracker.recordUsage("wf-1", 200_000, 0, "gpt-4o"));
        }
    }

    @Nested
    @DisplayName("Warning threshold")
    class WarningThresholdTests {

        @Test
        @DisplayName("detects warning when utilization exceeds threshold")
        void detectsWarningAboveThreshold() {
            // Policy: max 100K tokens, warning at 80%
            // Use 85K tokens -> 85% utilization
            tracker.recordUsage("wf-1", 50_000, 35_000, "ollama/llama3");

            BudgetSnapshot snapshot = tracker.getSnapshot("wf-1");
            assertTrue(snapshot.isWarning(80.0));
            assertEquals(85.0, snapshot.tokenUtilizationPercent(), 0.1);
        }

        @Test
        @DisplayName("no warning when utilization is below threshold")
        void noWarningBelowThreshold() {
            // Use only 10K tokens -> 10% utilization
            tracker.recordUsage("wf-1", 5_000, 5_000, "ollama/llama3");

            BudgetSnapshot snapshot = tracker.getSnapshot("wf-1");
            assertFalse(snapshot.isWarning(80.0));
        }

        @Test
        @DisplayName("detects cost-based warning")
        void detectsCostBasedWarning() {
            // Policy: max $1.00, warning at 80% ($0.80)
            // gpt-4o: $2.50/1M input. 400K tokens = $1.00 -> 100%
            BudgetPolicy costPolicy = BudgetPolicy.builder()
                    .maxTotalTokens(100_000_000)
                    .maxCostUsd(1.0)
                    .warningThresholdPercent(80.0)
                    .build();
            tracker.setBudgetPolicy("wf-1", costPolicy);
            tracker.recordUsage("wf-1", 350_000, 0, "gpt-4o");

            BudgetSnapshot snapshot = tracker.getSnapshot("wf-1");
            // Cost: 350K * $2.50/1M = $0.875 -> 87.5%
            assertTrue(snapshot.isWarning(80.0));
        }
    }

    @Nested
    @DisplayName("reset()")
    class ResetTests {

        @Test
        @DisplayName("clears all usage counters")
        void clearsAllCounters() {
            tracker.recordUsage("wf-1", 10_000, 5_000, "gpt-4o");
            tracker.reset("wf-1");

            BudgetSnapshot snapshot = tracker.getSnapshot("wf-1");
            assertEquals(0, snapshot.promptTokensUsed());
            assertEquals(0, snapshot.completionTokensUsed());
            assertEquals(0, snapshot.totalTokensUsed());
            assertEquals(0.0, snapshot.estimatedCostUsd());
        }

        @Test
        @DisplayName("preserves policy after reset")
        void preservesPolicyAfterReset() {
            BudgetPolicy customPolicy = BudgetPolicy.builder()
                    .maxTotalTokens(5000)
                    .maxCostUsd(0.50)
                    .onExceeded(BudgetPolicy.BudgetAction.HARD_STOP)
                    .build();
            tracker.setBudgetPolicy("wf-1", customPolicy);
            tracker.recordUsage("wf-1", 1000, 500, "ollama/llama3");
            tracker.reset("wf-1");

            // Policy is still HARD_STOP with 5000 token limit
            assertThrows(BudgetExceededException.class, () ->
                    tracker.recordUsage("wf-1", 3000, 3000, "gpt-4o"));
        }

        @Test
        @DisplayName("is safe for unknown workflow")
        void safeForUnknownWorkflow() {
            assertDoesNotThrow(() -> tracker.reset("unknown-wf"));
        }
    }

    @Nested
    @DisplayName("getSnapshot()")
    class GetSnapshotTests {

        @Test
        @DisplayName("returns zero snapshot for new workflow")
        void returnsZeroSnapshotForNewWorkflow() {
            BudgetSnapshot snapshot = tracker.getSnapshot("new-wf");

            assertEquals(0, snapshot.promptTokensUsed());
            assertEquals(0, snapshot.completionTokensUsed());
            assertEquals(0, snapshot.totalTokensUsed());
            assertEquals(0.0, snapshot.estimatedCostUsd());
            assertEquals(0.0, snapshot.tokenUtilizationPercent());
            assertEquals(0.0, snapshot.costUtilizationPercent());
            assertFalse(snapshot.tokenBudgetExceeded());
            assertFalse(snapshot.costBudgetExceeded());
            assertFalse(snapshot.isExceeded());
        }

        @Test
        @DisplayName("computes utilization percentages correctly")
        void computesUtilizationCorrectly() {
            // Policy: max 100K tokens, max $1.00
            // Use 50K tokens with ollama (free) -> 50% token, 0% cost
            tracker.recordUsage("wf-1", 30_000, 20_000, "ollama/llama3");

            BudgetSnapshot snapshot = tracker.getSnapshot("wf-1");
            assertEquals(50.0, snapshot.tokenUtilizationPercent(), 0.1);
            assertEquals(0.0, snapshot.costUtilizationPercent(), 0.001);
        }
    }

    @Nested
    @DisplayName("setBudgetPolicy()")
    class SetBudgetPolicyTests {

        @Test
        @DisplayName("overrides default policy")
        void overridesDefaultPolicy() {
            BudgetPolicy customPolicy = BudgetPolicy.builder()
                    .maxTotalTokens(500)
                    .maxCostUsd(0.001)
                    .onExceeded(BudgetPolicy.BudgetAction.HARD_STOP)
                    .build();
            tracker.setBudgetPolicy("wf-1", customPolicy);

            assertThrows(BudgetExceededException.class, () ->
                    tracker.recordUsage("wf-1", 300, 300, "gpt-4o"));
        }
    }

    @Nested
    @DisplayName("Thread Safety")
    class ThreadSafetyTests {

        @Test
        @DisplayName("handles concurrent usage recording")
        void concurrentRecording_noErrors() throws InterruptedException {
            int threadCount = 10;
            int callsPerThread = 100;
            long tokensPerCall = 10;

            // Use a large enough budget to avoid exceptions
            BudgetPolicy largePolicy = BudgetPolicy.builder()
                    .maxTotalTokens(1_000_000_000)
                    .maxCostUsd(1_000_000.0)
                    .onExceeded(BudgetPolicy.BudgetAction.WARN)
                    .build();
            tracker.setBudgetPolicy("wf-concurrent", largePolicy);

            Thread[] threads = new Thread[threadCount];
            for (int t = 0; t < threadCount; t++) {
                threads[t] = new Thread(() -> {
                    for (int i = 0; i < callsPerThread; i++) {
                        tracker.recordUsage("wf-concurrent", tokensPerCall, tokensPerCall, "gpt-4o-mini");
                    }
                });
            }

            for (Thread thread : threads) {
                thread.start();
            }
            for (Thread thread : threads) {
                thread.join();
            }

            BudgetSnapshot snapshot = tracker.getSnapshot("wf-concurrent");
            long expectedPrompt = (long) threadCount * callsPerThread * tokensPerCall;
            long expectedCompletion = expectedPrompt;

            assertEquals(expectedPrompt, snapshot.promptTokensUsed());
            assertEquals(expectedCompletion, snapshot.completionTokensUsed());
            assertEquals(expectedPrompt + expectedCompletion, snapshot.totalTokensUsed());
            assertTrue(snapshot.estimatedCostUsd() > 0);
        }

        @Test
        @DisplayName("handles concurrent recording across different workflows")
        void concurrentRecordingAcrossWorkflows_noErrors() throws InterruptedException {
            int workflowCount = 5;
            int callsPerWorkflow = 200;

            BudgetPolicy largePolicy = BudgetPolicy.builder()
                    .maxTotalTokens(1_000_000_000)
                    .maxCostUsd(1_000_000.0)
                    .build();

            Thread[] threads = new Thread[workflowCount];
            for (int w = 0; w < workflowCount; w++) {
                final String wfId = "wf-" + w;
                tracker.setBudgetPolicy(wfId, largePolicy);
                threads[w] = new Thread(() -> {
                    for (int i = 0; i < callsPerWorkflow; i++) {
                        tracker.recordUsage(wfId, 100, 50, "gpt-4o-mini");
                    }
                });
            }

            for (Thread thread : threads) {
                thread.start();
            }
            for (Thread thread : threads) {
                thread.join();
            }

            for (int w = 0; w < workflowCount; w++) {
                BudgetSnapshot snapshot = tracker.getSnapshot("wf-" + w);
                assertEquals(callsPerWorkflow * 100L, snapshot.promptTokensUsed());
                assertEquals(callsPerWorkflow * 50L, snapshot.completionTokensUsed());
            }
        }
    }
}
