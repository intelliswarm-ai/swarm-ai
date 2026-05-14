package ai.intelliswarm.swarmai.agent.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CachePolicyTest {

    @Test
    void disabledPolicyHasNoBreakpoints() {
        CachePolicy policy = CachePolicy.disabled();
        assertThat(policy.isEnabled()).isFalse();
        assertThat(policy.breakpoints()).isEmpty();
    }

    @Test
    void builderAcceptsUpToFourBreakpoints() {
        CachePolicy policy = CachePolicy.builder()
                .breakpoint(CacheBreakpoint.oneHour("tools"))
                .breakpoint(CacheBreakpoint.oneHour("system"))
                .breakpoint(CacheBreakpoint.fiveMinutes("documents"))
                .breakpoint(CacheBreakpoint.fiveMinutes("messages:last"))
                .build();
        assertThat(policy.breakpoints()).hasSize(4);
        assertThat(policy.isEnabled()).isTrue();
    }

    @Test
    void builderRejectsMoreThanFourBreakpoints() {
        CachePolicy.Builder builder = CachePolicy.builder()
                .breakpoints(List.of(
                        CacheBreakpoint.oneHour("a"),
                        CacheBreakpoint.oneHour("b"),
                        CacheBreakpoint.oneHour("c"),
                        CacheBreakpoint.oneHour("d"),
                        CacheBreakpoint.oneHour("e")));
        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 4");
    }

    @Test
    void builderRejectsDuplicateSlots() {
        CachePolicy.Builder builder = CachePolicy.builder()
                .breakpoint(CacheBreakpoint.oneHour("system"))
                .breakpoint(CacheBreakpoint.fiveMinutes("system"));
        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate cache slot");
    }

    @Test
    void forSlotReturnsBreakpoint() {
        CachePolicy policy = CachePolicy.builder()
                .breakpoint(CacheBreakpoint.oneHour("system"))
                .build();
        assertThat(policy.forSlot("system")).hasValueSatisfying(bp ->
                assertThat(bp.tier()).isEqualTo(CacheTier.ONE_HOUR));
        assertThat(policy.forSlot("unknown")).isEmpty();
    }

    @Test
    void thinkingBudgetEnforcesMinimum() {
        assertThatThrownBy(() -> ThinkingBudget.enabled(512))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 1024");
    }

    @Test
    void thinkingBudgetDisabledRequiresZeroTokens() {
        assertThatThrownBy(() -> new ThinkingBudget(ThinkingMode.DISABLED, 1024, ThinkingDisplay.SUMMARIZED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void thinkingBudgetInterleavedConvenienceFactory() {
        ThinkingBudget budget = ThinkingBudget.interleaved(8192);
        assertThat(budget.mode()).isEqualTo(ThinkingMode.INTERLEAVED);
        assertThat(budget.budgetTokens()).isEqualTo(8192);
        assertThat(budget.isEnabled()).isTrue();
    }

    @Test
    void cacheUsageSavingsPercent() {
        CacheUsage usage = new CacheUsage(900, 100, 0);
        // 900 cached of 1000 total → 90% × 0.9 weight = 81%
        assertThat(usage.savingsPercent(1000)).isCloseTo(81.0, org.assertj.core.data.Offset.offset(0.5));
    }

    @Test
    void cacheUsageZeroTotalReturnsZero() {
        assertThat(CacheUsage.NONE.savingsPercent(0)).isEqualTo(0.0);
    }

    @Test
    void citationLocationPageEnforcesOneBased() {
        assertThatThrownBy(() -> new CitationLocation.PageLocation(0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CitationLocation.PageLocation(5, 3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void documentBlockPdfRejectsBothBytesAndUrl() {
        assertThatThrownBy(() -> new DocumentBlock.Pdf("doc1", "10-K", new byte[]{1, 2}, "https://x", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one of bytes or url");
        assertThatThrownBy(() -> new DocumentBlock.Pdf("doc1", "10-K", null, null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void documentBlockCustomRejectsEmpty() {
        assertThatThrownBy(() -> new DocumentBlock.Custom("doc", "t", List.of(), false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tokenUsageEffectiveCalculation() {
        TokenUsage usage = new TokenUsage(
                1000, // 1000 input @ 1× = 1000
                0,
                new CacheUsage(2000, 500, 0), // 2000 cache read @ 0.1× = 200, 500 cache create 5m @ 1.25× = 625
                0, 0);
        // 1000 + 625 + 0 + 200 = 1825
        assertThat(usage.effectiveInputTokens()).isEqualTo(1825);
    }
}
