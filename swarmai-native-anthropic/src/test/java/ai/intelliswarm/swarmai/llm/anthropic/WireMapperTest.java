package ai.intelliswarm.swarmai.llm.anthropic;

import ai.intelliswarm.swarmai.agent.llm.CacheBreakpoint;
import ai.intelliswarm.swarmai.agent.llm.CachePolicy;
import ai.intelliswarm.swarmai.agent.llm.CacheTier;
import ai.intelliswarm.swarmai.agent.llm.DocumentBlock;
import ai.intelliswarm.swarmai.agent.llm.ThinkingBudget;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.DocumentBlockParam;
import com.anthropic.models.messages.ToolUnion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WireMapperTest {

    // ---------- Cache control ----------

    @Test
    void fiveMinuteTierMapsToTtl5m() {
        CacheControlEphemeral cc = WireMapper.toCacheControl(CacheTier.FIVE_MINUTES);
        assertThat(cc.ttl()).hasValue(CacheControlEphemeral.Ttl.TTL_5M);
    }

    @Test
    void oneHourTierMapsToTtl1h() {
        CacheControlEphemeral cc = WireMapper.toCacheControl(CacheTier.ONE_HOUR);
        assertThat(cc.ttl()).hasValue(CacheControlEphemeral.Ttl.TTL_1H);
    }

    // ---------- Slot resolution ----------

    @Test
    void messagesIndexResolvedNumerically() {
        assertThat(WireMapper.resolveMessageIndex("messages:2", 5)).isEqualTo(2);
    }

    @Test
    void messagesLastResolvedToLastIndex() {
        assertThat(WireMapper.resolveMessageIndex("messages:last", 5)).isEqualTo(4);
    }

    @Test
    void nonMessagesSlotResolvesNegative() {
        assertThat(WireMapper.resolveMessageIndex("tools", 5)).isEqualTo(-1);
        assertThat(WireMapper.resolveMessageIndex("system", 5)).isEqualTo(-1);
        assertThat(WireMapper.resolveMessageIndex("documents", 5)).isEqualTo(-1);
    }

    @Test
    void messagesIndexOutOfRangeRejected() {
        assertThatThrownBy(() -> WireMapper.resolveMessageIndex("messages:99", 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("out of range");
    }

    @Test
    void invalidMessagesSlotRejected() {
        assertThatThrownBy(() -> WireMapper.resolveMessageIndex("messages:abc", 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- cacheFor lookup ----------

    @Test
    void cacheForReturnsMatchingTier() {
        CachePolicy policy = CachePolicy.builder()
                .breakpoint(CacheBreakpoint.oneHour("system"))
                .breakpoint(CacheBreakpoint.fiveMinutes("tools"))
                .build();
        Optional<CacheControlEphemeral> sysCache = WireMapper.cacheFor(policy, "system");
        Optional<CacheControlEphemeral> toolsCache = WireMapper.cacheFor(policy, "tools");
        assertThat(sysCache).isPresent();
        assertThat(sysCache.get().ttl()).hasValue(CacheControlEphemeral.Ttl.TTL_1H);
        assertThat(toolsCache).isPresent();
        assertThat(toolsCache.get().ttl()).hasValue(CacheControlEphemeral.Ttl.TTL_5M);
    }

    @Test
    void cacheForReturnsEmptyForMissingSlot() {
        CachePolicy policy = CachePolicy.builder()
                .breakpoint(CacheBreakpoint.oneHour("system"))
                .build();
        assertThat(WireMapper.cacheFor(policy, "documents")).isEmpty();
    }

    // ---------- Documents ----------

    @Test
    void pdfBytesDocumentMapsToBase64Source() {
        DocumentBlock.Pdf pdf = DocumentBlock.Pdf.ofBytes("doc1", "10-K", new byte[]{1, 2, 3, 4}, true);
        DocumentBlockParam param = WireMapper.toDocumentBlockParam(pdf, Optional.empty());
        assertThat(param.title()).hasValue("10-K");
        assertThat(param.citations()).isPresent();
        assertThat(param.citations().get().enabled()).hasValue(true);
    }

    @Test
    void pdfUrlDocumentMapsToUrlSource() {
        DocumentBlock.Pdf pdf = DocumentBlock.Pdf.ofUrl("doc1", "10-K", "https://x.example/10k.pdf", false);
        DocumentBlockParam param = WireMapper.toDocumentBlockParam(pdf, Optional.empty());
        assertThat(param.title()).hasValue("10-K");
        // citations not requested → field is absent
        assertThat(param.citations()).isEmpty();
    }

    @Test
    void documentWithCacheAttachesCacheControl() {
        DocumentBlock.PlainText doc = new DocumentBlock.PlainText("d1", "memo", "body text here", true);
        CacheControlEphemeral cc = WireMapper.toCacheControl(CacheTier.ONE_HOUR);
        DocumentBlockParam param = WireMapper.toDocumentBlockParam(doc, Optional.of(cc));
        assertThat(param.cacheControl()).isPresent();
    }

    // ---------- Tools ----------

    @Test
    void toolSpecMapsToToolUnionWithSchema() {
        AnthropicToolSpec spec = AnthropicToolSpec.of(
                "calculator",
                "Evaluate an arithmetic expression",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "expression", Map.of("type", "string", "description", "expression to evaluate")
                        ),
                        "required", List.of("expression")));
        ToolUnion union = WireMapper.toToolUnion(spec, Optional.empty());
        assertThat(union.tool()).isPresent();
        var tool = union.tool().get();
        assertThat(tool.name()).isEqualTo("calculator");
        assertThat(tool.description()).hasValue("Evaluate an arithmetic expression");
        assertThat(tool.inputSchema().required()).hasValue(List.of("expression"));
    }

    @Test
    void toolWithCacheControlAttaches() {
        AnthropicToolSpec spec = AnthropicToolSpec.of(
                "calc", "calc", Map.of("type", "object"));
        CacheControlEphemeral cc = WireMapper.toCacheControl(CacheTier.ONE_HOUR);
        ToolUnion union = WireMapper.toToolUnion(spec, Optional.of(cc));
        assertThat(union.tool().get().cacheControl()).isPresent();
    }

    // ---------- Thinking ----------

    @Test
    void disabledThinkingMapsToEmpty() {
        assertThat(WireMapper.toThinkingConfig(ThinkingBudget.DISABLED)).isEmpty();
    }

    @Test
    void enabledThinkingMapsToConfig() {
        var cfg = WireMapper.toThinkingConfig(ThinkingBudget.enabled(4096));
        assertThat(cfg).isPresent();
        assertThat(cfg.get().enabled()).isPresent();
        assertThat(cfg.get().enabled().get().budgetTokens()).isEqualTo(4096L);
    }
}
