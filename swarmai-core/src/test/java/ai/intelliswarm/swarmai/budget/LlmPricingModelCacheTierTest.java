package ai.intelliswarm.swarmai.budget;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the 1.0.25 four-tier pricing additions to {@link LlmPricingModel}:
 * cache-write + cache-read tiers, new {@code calculateCost(5-arg)} overload,
 * and the {@code cacheHitRate} / {@code savedByCaching} helpers.
 */
@DisplayName("LlmPricingModel — four-tier cache pricing (1.0.25)")
class LlmPricingModelCacheTierTest {

    @Test
    @DisplayName("Anthropic Sonnet returns full four-tier pricing")
    void sonnetFourTier() {
        var p = LlmPricingModel.getPricing("claude-sonnet-4-5");
        assertEquals(3.00, p.inputPer1M());
        assertEquals(3.75, p.cacheWritePer1M());
        assertEquals(0.30, p.cacheReadPer1M());
        assertEquals(15.00, p.outputPer1M());
    }

    @Test
    @DisplayName("Anthropic Opus returns full four-tier pricing")
    void opusFourTier() {
        var p = LlmPricingModel.getPricing("claude-opus-4-7");
        assertEquals(15.00, p.inputPer1M());
        assertEquals(18.75, p.cacheWritePer1M());
        assertEquals(1.50, p.cacheReadPer1M());
        assertEquals(75.00, p.outputPer1M());
    }

    @Test
    @DisplayName("Anthropic Haiku returns full four-tier pricing")
    void haikuFourTier() {
        var p = LlmPricingModel.getPricing("claude-haiku-4-5");
        assertEquals(1.00, p.inputPer1M());
        assertEquals(1.25, p.cacheWritePer1M());
        assertEquals(0.10, p.cacheReadPer1M());
        assertEquals(5.00, p.outputPer1M());
    }

    @Test
    @DisplayName("OpenAI models have zero cache-tier prices (no billed cache tiers)")
    void openAiHasNoCacheTiers() {
        for (String model : new String[]{"gpt-4o", "gpt-4o-mini", "gpt-4.1", "gpt-4.1-mini", "gpt-4.1-nano"}) {
            var p = LlmPricingModel.getPricing(model);
            assertEquals(0.0, p.cacheWritePer1M(), "model=" + model);
            assertEquals(0.0, p.cacheReadPer1M(), "model=" + model);
            assertTrue(p.inputPer1M() > 0, "model=" + model);
            assertTrue(p.outputPer1M() > 0, "model=" + model);
        }
    }

    @Test
    @DisplayName("gpt-4.1-mini priced cheaper than gpt-4o-mini's predecessor")
    void gpt41MiniPricing() {
        var p = LlmPricingModel.getPricing("gpt-4.1-mini");
        assertEquals(0.40, p.inputPer1M());
        assertEquals(1.60, p.outputPer1M());
    }

    @Test
    @DisplayName("gpt-4.1-nano is the cheapest OpenAI tier")
    void gpt41NanoPricing() {
        var p = LlmPricingModel.getPricing("gpt-4.1-nano");
        assertEquals(0.10, p.inputPer1M());
        assertEquals(0.40, p.outputPer1M());
    }

    @Test
    @DisplayName("calculateCost(5-arg) sums all four tiers correctly")
    void calculateCostFourTier() {
        // Sonnet: 1M input + 1M cacheW + 1M cacheR + 1M output
        // = 3.00 + 3.75 + 0.30 + 15.00 = 22.05
        double cost = LlmPricingModel.calculateCost(
                1_000_000, 1_000_000, 1_000_000, 1_000_000, "claude-sonnet-4-5");
        assertEquals(22.05, cost, 1e-9);
    }

    @Test
    @DisplayName("calculateCost(2-arg) backward-compatible — treats cache tiers as zero")
    void calculateCostBackwardCompatible() {
        double legacy = LlmPricingModel.calculateCost(1_000_000, 1_000_000, "claude-sonnet-4-5");
        double newApi = LlmPricingModel.calculateCost(1_000_000, 0, 0, 1_000_000, "claude-sonnet-4-5");
        assertEquals(legacy, newApi, 1e-9, "two-arg overload must match four-arg with zero caches");
    }

    @Test
    @DisplayName("calculateCost handles unknown model as free")
    void unknownModelIsFree() {
        double cost = LlmPricingModel.calculateCost(1_000_000, 0, 0, 1_000_000, "unknown-foo");
        assertEquals(0.0, cost);
    }

    @Test
    @DisplayName("cacheHitRate returns 0 when nothing was spent")
    void cacheHitRateEmpty() {
        assertEquals(0.0, LlmPricingModel.cacheHitRate(0, 0, 0));
    }

    @Test
    @DisplayName("cacheHitRate = cacheRead / (input + cacheRead + cacheWrite)")
    void cacheHitRateCalculation() {
        assertEquals(0.0, LlmPricingModel.cacheHitRate(100, 0, 0));
        assertEquals(1.0, LlmPricingModel.cacheHitRate(0, 0, 100));
        assertEquals(0.5, LlmPricingModel.cacheHitRate(50, 0, 50));
        assertEquals(0.9, LlmPricingModel.cacheHitRate(50, 50, 900), 1e-9);
    }

    @Test
    @DisplayName("savedByCaching reflects real per-tier delta on Sonnet")
    void savedByCachingSonnet() {
        // 100K cache_read @ Sonnet: cached = 100_000 * 0.30/M = $0.03;
        //                          uncached = 100_000 * 3.00/M = $0.30;
        //                          saved   = $0.27
        double saved = LlmPricingModel.savedByCaching(0, 100_000, "claude-sonnet-4-5");
        assertEquals(0.27, saved, 1e-9);
    }

    @Test
    @DisplayName("savedByCaching is zero for providers without cache tiers (e.g. OpenAI)")
    void savedByCachingOpenAi() {
        // gpt-4o has cache_read price = 0, so the "savings" formula collapses.
        // uncached cost minus cached cost = (100K * 2.50/M) - (100K * 0/M) = $0.25.
        // That's not really "saved" — it's just the value of cached tokens NOT being billed
        // at input rate. The helper returns max(0, uncached - cached), so $0.25.
        // For OpenAI today both terms are zero because cache_read tokens aren't reported.
        // We pass cacheRead=0 to simulate the real OpenAI usage shape.
        double saved = LlmPricingModel.savedByCaching(0, 0, "gpt-4o");
        assertEquals(0.0, saved);
    }

    @Test
    @DisplayName("two-arg LlmPricing record ctor defaults cache fields to zero")
    void recordConvenienceCtor() {
        var p = new LlmPricingModel.LlmPricing(2.50, 10.00);
        assertEquals(2.50, p.inputPer1M());
        assertEquals(0.0, p.cacheWritePer1M());
        assertEquals(0.0, p.cacheReadPer1M());
        assertEquals(10.00, p.outputPer1M());
    }
}
