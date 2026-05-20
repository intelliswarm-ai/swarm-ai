package ai.intelliswarm.swarmai.budget;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the 1.0.26 cache-aware recordUsage path in {@link InMemoryBudgetTracker}.
 * Verifies that prompt-caching savings are reflected in the estimated cost and that
 * token counters still match what providers report on the wire.
 */
@DisplayName("InMemoryBudgetTracker — cache-aware recordUsage (1.0.26)")
class InMemoryBudgetTrackerCacheTierTest {

    private static final BudgetPolicy LOOSE = new BudgetPolicy(
            Long.MAX_VALUE, Double.MAX_VALUE, "claude-sonnet-4-5",
            BudgetPolicy.BudgetAction.WARN, 90.0);

    @Test
    @DisplayName("legacy 2-tier recordUsage still produces an uncached-cost estimate")
    void legacyRecordUsageStillWorks() {
        var tracker = new InMemoryBudgetTracker(LOOSE);
        tracker.recordUsage("wf", 1_000_000, 1_000_000, "claude-sonnet-4-5");
        var snap = tracker.getSnapshot("wf");

        // Sonnet 2-tier: 1M input @ $3 + 1M output @ $15 = $18
        assertEquals(18.0, snap.estimatedCostUsd(), 1e-9);
        assertEquals(1_000_000, snap.promptTokensUsed());
        assertEquals(1_000_000, snap.completionTokensUsed());
    }

    @Test
    @DisplayName("4-tier recordUsage charges cache reads at the discount rate")
    void cacheReadsBilledAtDiscount() {
        var tracker = new InMemoryBudgetTracker(LOOSE);
        // 100K uncached + 100K cache writes + 800K cache reads + 50K output on Sonnet
        // = 100K * $3/M + 100K * $3.75/M + 800K * $0.30/M + 50K * $15/M
        // = $0.30 + $0.375 + $0.24 + $0.75 = $1.665
        tracker.recordUsage("wf", 100_000, 100_000, 800_000, 50_000, "claude-sonnet-4-5");

        var snap = tracker.getSnapshot("wf");
        assertEquals(1.665, snap.estimatedCostUsd(), 1e-9);

        // Token counters reflect ALL input tokens — the providers report the sum
        // so maxTotalTokens budgets behave the same way users expect.
        assertEquals(1_000_000, snap.promptTokensUsed());
        assertEquals(50_000, snap.completionTokensUsed());
    }

    @Test
    @DisplayName("cache-aware path is materially cheaper than the all-uncached counterfactual")
    void cacheSavingsShowUpInCost() {
        var trackerCached   = new InMemoryBudgetTracker(LOOSE);
        var trackerUncached = new InMemoryBudgetTracker(LOOSE);

        // Same total token shape, different cache distribution.
        trackerCached.recordUsage("wf", 50_000, 50_000, 900_000, 100_000, "claude-sonnet-4-5");
        trackerUncached.recordUsage("wf", 1_000_000, 0L, 0L, 100_000, "claude-sonnet-4-5");

        double cached   = trackerCached.getSnapshot("wf").estimatedCostUsd();
        double uncached = trackerUncached.getSnapshot("wf").estimatedCostUsd();

        assertTrue(cached < uncached * 0.5,
                "expected caching to cut cost by more than half; cached=$" + cached + " uncached=$" + uncached);
    }

    @Test
    @DisplayName("default interface method forwards to legacy recordUsage")
    void defaultMethodForwardsForOtherImpls() {
        // Drive the default method via the interface against a recording stub.
        long[] capture = new long[3];
        String[] modelCapture = new String[1];
        BudgetTracker recorder = new BudgetTracker() {
            @Override
            public void recordUsage(String workflowId, long promptTokens, long completionTokens, String modelName) {
                capture[0] = promptTokens;
                capture[1] = completionTokens;
                modelCapture[0] = modelName;
            }
            @Override public BudgetSnapshot getSnapshot(String workflowId) { return null; }
            @Override public boolean isExceeded(String workflowId) { return false; }
            @Override public void setBudgetPolicy(String workflowId, BudgetPolicy policy) {}
            @Override public void reset(String workflowId) {}
        };

        recorder.recordUsage("wf", 100, 200, 300, 50, "claude-sonnet");
        assertEquals(600, capture[0], "default forwards summed input tokens");
        assertEquals(50, capture[1]);
        assertEquals("claude-sonnet", modelCapture[0]);
    }
}
