package ai.intelliswarm.swarmai.budget;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LlmPricingModel Tests")
class LlmPricingModelTest {

    @Nested
    @DisplayName("getPricing()")
    class GetPricingTests {

        @Test
        @DisplayName("returns correct pricing for gpt-4o-mini")
        void gpt4oMini_returnsCorrectPricing() {
            LlmPricingModel.LlmPricing pricing = LlmPricingModel.getPricing("gpt-4o-mini");
            assertEquals(0.15, pricing.inputPer1M());
            assertEquals(0.60, pricing.outputPer1M());
        }

        @Test
        @DisplayName("returns correct pricing for gpt-4o")
        void gpt4o_returnsCorrectPricing() {
            LlmPricingModel.LlmPricing pricing = LlmPricingModel.getPricing("gpt-4o");
            assertEquals(2.50, pricing.inputPer1M());
            assertEquals(10.00, pricing.outputPer1M());
        }

        @Test
        @DisplayName("returns correct pricing for gpt-4-turbo")
        void gpt4Turbo_returnsCorrectPricing() {
            LlmPricingModel.LlmPricing pricing = LlmPricingModel.getPricing("gpt-4-turbo");
            assertEquals(10.00, pricing.inputPer1M());
            assertEquals(30.00, pricing.outputPer1M());
        }

        @Test
        @DisplayName("returns correct pricing for gpt-4")
        void gpt4_returnsCorrectPricing() {
            LlmPricingModel.LlmPricing pricing = LlmPricingModel.getPricing("gpt-4");
            assertEquals(10.00, pricing.inputPer1M());
            assertEquals(30.00, pricing.outputPer1M());
        }

        @Test
        @DisplayName("returns correct pricing for gpt-3.5")
        void gpt35_returnsCorrectPricing() {
            LlmPricingModel.LlmPricing pricing = LlmPricingModel.getPricing("gpt-3.5-turbo");
            assertEquals(0.50, pricing.inputPer1M());
            assertEquals(1.50, pricing.outputPer1M());
        }

        @Test
        @DisplayName("returns correct pricing for claude-sonnet")
        void claudeSonnet_returnsCorrectPricing() {
            LlmPricingModel.LlmPricing pricing = LlmPricingModel.getPricing("claude-3-sonnet-20240229");
            assertEquals(3.00, pricing.inputPer1M());
            assertEquals(15.00, pricing.outputPer1M());
        }

        @Test
        @DisplayName("returns correct pricing for claude-haiku")
        void claudeHaiku_returnsCorrectPricing() {
            LlmPricingModel.LlmPricing pricing = LlmPricingModel.getPricing("claude-3-haiku-20240307");
            assertEquals(0.25, pricing.inputPer1M());
            assertEquals(1.25, pricing.outputPer1M());
        }

        @Test
        @DisplayName("returns correct pricing for claude-opus")
        void claudeOpus_returnsCorrectPricing() {
            LlmPricingModel.LlmPricing pricing = LlmPricingModel.getPricing("claude-3-opus-20240229");
            assertEquals(15.00, pricing.inputPer1M());
            assertEquals(75.00, pricing.outputPer1M());
        }

        @Test
        @DisplayName("returns zero cost for ollama models")
        void ollamaModels_returnZeroCost() {
            LlmPricingModel.LlmPricing pricing = LlmPricingModel.getPricing("ollama/llama3");
            assertEquals(0.00, pricing.inputPer1M());
            assertEquals(0.00, pricing.outputPer1M());
        }

        @Test
        @DisplayName("returns zero cost for llama models")
        void llamaModels_returnZeroCost() {
            LlmPricingModel.LlmPricing pricing = LlmPricingModel.getPricing("llama3.1:8b");
            assertEquals(0.00, pricing.inputPer1M());
            assertEquals(0.00, pricing.outputPer1M());
        }

        @Test
        @DisplayName("returns zero cost for mistral local models")
        void mistralModels_returnZeroCost() {
            LlmPricingModel.LlmPricing pricing = LlmPricingModel.getPricing("mistral:7b");
            assertEquals(0.00, pricing.inputPer1M());
            assertEquals(0.00, pricing.outputPer1M());
        }

        @Test
        @DisplayName("returns zero cost for unknown models (default free)")
        void unknownModels_returnDefaultFree() {
            LlmPricingModel.LlmPricing pricing = LlmPricingModel.getPricing("some-unknown-model");
            assertEquals(0.00, pricing.inputPer1M());
            assertEquals(0.00, pricing.outputPer1M());
        }

        @Test
        @DisplayName("returns zero cost for null model name")
        void nullModelName_returnsFree() {
            LlmPricingModel.LlmPricing pricing = LlmPricingModel.getPricing(null);
            assertEquals(0.00, pricing.inputPer1M());
            assertEquals(0.00, pricing.outputPer1M());
        }

        @Test
        @DisplayName("is case insensitive")
        void caseInsensitive_matchesCorrectly() {
            LlmPricingModel.LlmPricing pricing = LlmPricingModel.getPricing("GPT-4O-MINI");
            assertEquals(0.15, pricing.inputPer1M());
            assertEquals(0.60, pricing.outputPer1M());
        }

        @Test
        @DisplayName("gpt-4o-mini matches before gpt-4o")
        void gpt4oMini_matchesBeforeGpt4o() {
            // "gpt-4o-mini" contains "gpt-4o", so order matters
            LlmPricingModel.LlmPricing mini = LlmPricingModel.getPricing("gpt-4o-mini-2024-07-18");
            assertEquals(0.15, mini.inputPer1M());

            LlmPricingModel.LlmPricing full = LlmPricingModel.getPricing("gpt-4o-2024-08-06");
            assertEquals(2.50, full.inputPer1M());
        }
    }

    @Nested
    @DisplayName("calculateCost()")
    class CalculateCostTests {

        @Test
        @DisplayName("calculates cost correctly for gpt-4o")
        void gpt4o_calculatesCorrectCost() {
            // 1M prompt tokens at $2.50 + 500K completion tokens at $10.00
            double cost = LlmPricingModel.calculateCost(1_000_000, 500_000, "gpt-4o");
            assertEquals(7.50, cost, 0.001);
        }

        @Test
        @DisplayName("calculates cost correctly for claude-sonnet")
        void claudeSonnet_calculatesCorrectCost() {
            // 100K prompt at $3.00/1M + 50K completion at $15.00/1M
            double cost = LlmPricingModel.calculateCost(100_000, 50_000, "claude-3-sonnet");
            double expected = (100_000 / 1_000_000.0) * 3.00 + (50_000 / 1_000_000.0) * 15.00;
            assertEquals(expected, cost, 0.0001);
        }

        @Test
        @DisplayName("returns zero for zero tokens")
        void zeroTokens_returnsZeroCost() {
            double cost = LlmPricingModel.calculateCost(0, 0, "gpt-4o");
            assertEquals(0.0, cost);
        }

        @Test
        @DisplayName("returns zero for local models regardless of tokens")
        void localModels_returnZeroCost() {
            double cost = LlmPricingModel.calculateCost(1_000_000, 1_000_000, "ollama/llama3");
            assertEquals(0.0, cost);
        }

        @Test
        @DisplayName("handles small token counts accurately")
        void smallTokenCounts_calculatesAccurately() {
            // 1000 prompt tokens for gpt-4o-mini: (1000 / 1M) * 0.15 = 0.00015
            double cost = LlmPricingModel.calculateCost(1000, 0, "gpt-4o-mini");
            assertEquals(0.00015, cost, 0.000001);
        }
    }
}
