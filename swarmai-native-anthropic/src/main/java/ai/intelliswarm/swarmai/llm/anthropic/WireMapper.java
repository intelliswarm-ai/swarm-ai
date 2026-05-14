package ai.intelliswarm.swarmai.llm.anthropic;

import ai.intelliswarm.swarmai.agent.llm.CacheBreakpoint;
import ai.intelliswarm.swarmai.agent.llm.CachePolicy;
import ai.intelliswarm.swarmai.agent.llm.CacheTier;
import ai.intelliswarm.swarmai.agent.llm.CacheUsage;
import ai.intelliswarm.swarmai.agent.llm.CitationLocation;
import ai.intelliswarm.swarmai.agent.llm.DocumentBlock;
import ai.intelliswarm.swarmai.agent.llm.InlineCitation;
import ai.intelliswarm.swarmai.agent.llm.ThinkingBudget;
import ai.intelliswarm.swarmai.agent.llm.TokenUsage;
import com.anthropic.core.JsonField;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Base64PdfSource;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.CitationCharLocation;
import com.anthropic.models.messages.CitationContentBlockLocation;
import com.anthropic.models.messages.CitationPageLocation;
import com.anthropic.models.messages.CitationsConfigParam;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.DocumentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.PlainTextSource;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.TextCitation;
import com.anthropic.models.messages.ThinkingConfigEnabled;
import com.anthropic.models.messages.ThinkingConfigParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.anthropic.models.messages.UrlPdfSource;
import com.anthropic.models.messages.Usage;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pure-function translation between SwarmAI substrate types
 * ({@link CachePolicy}, {@link ThinkingBudget}, {@link DocumentBlock},
 * {@link AnthropicContent}, …) and the Anthropic SDK's wire types
 * ({@link ContentBlockParam}, {@link DocumentBlockParam}, {@link ToolUnion}, …).
 *
 * <p>Package-private. Tests cover this surface directly without booting an
 * HTTP client.
 */
final class WireMapper {

    private WireMapper() {}

    // ---------- Cache control ----------

    static CacheControlEphemeral toCacheControl(CacheTier tier) {
        CacheControlEphemeral.Ttl sdkTtl = (tier == CacheTier.ONE_HOUR)
                ? CacheControlEphemeral.Ttl.TTL_1H
                : CacheControlEphemeral.Ttl.TTL_5M;
        return CacheControlEphemeral.builder().ttl(sdkTtl).build();
    }

    /**
     * Resolve which message index the {@code messages:N} or {@code messages:last}
     * slot points at.
     *
     * @param slot          breakpoint slot name
     * @param messagesCount total messages in the current request
     * @return the resolved index or {@code -1} if the slot doesn't reference messages
     */
    static int resolveMessageIndex(String slot, int messagesCount) {
        if (!slot.startsWith("messages:")) {
            return -1;
        }
        String suffix = slot.substring("messages:".length());
        if ("last".equals(suffix)) {
            return messagesCount - 1;
        }
        try {
            int n = Integer.parseInt(suffix);
            if (n < 0 || n >= messagesCount) {
                throw new IllegalArgumentException(
                        "messages slot index " + n + " out of range (count=" + messagesCount + ")");
            }
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid messages slot: " + slot);
        }
    }

    // ---------- Documents ----------

    static DocumentBlockParam toDocumentBlockParam(DocumentBlock doc, Optional<CacheControlEphemeral> cache) {
        DocumentBlockParam.Builder b = DocumentBlockParam.builder();
        if (doc.title() != null) {
            b.title(doc.title());
        }
        switch (doc) {
            case DocumentBlock.Pdf pdf -> {
                if (pdf.bytes() != null) {
                    String b64 = Base64.getEncoder().encodeToString(pdf.bytes());
                    b.source(Base64PdfSource.builder().data(b64).build());
                } else {
                    b.source(UrlPdfSource.builder().url(pdf.url()).build());
                }
            }
            case DocumentBlock.PlainText text ->
                    b.source(PlainTextSource.builder().data(text.text()).build());
            case DocumentBlock.Custom custom -> {
                // SDK ContentBlockSource: build text blocks for each content block
                List<TextBlockParam> blocks = new ArrayList<>();
                for (String content : custom.contentBlocks()) {
                    blocks.add(TextBlockParam.builder().text(content).build());
                }
                b.contentSourceOfBlockSource(
                        blocks.stream()
                                .map(tb -> com.anthropic.models.messages.ContentBlockSourceContent
                                        .ofText(tb))
                                .toList());
            }
        }
        if (doc.citationsEnabled()) {
            b.citations(CitationsConfigParam.builder().enabled(true).build());
        }
        cache.ifPresent(b::cacheControl);
        return b.build();
    }

    // ---------- Tools ----------

    /** Translate a server-side tool spec into the SDK's tagged union. */
    static ToolUnion toServerToolUnion(ServerToolSpec spec) {
        return switch (spec) {
            case ServerToolSpec.WebSearch ws -> {
                com.anthropic.models.messages.WebSearchTool20260209.Builder b =
                        com.anthropic.models.messages.WebSearchTool20260209.builder();
                if (!ws.allowedDomains().isEmpty()) b.allowedDomains(ws.allowedDomains());
                if (!ws.blockedDomains().isEmpty()) b.blockedDomains(ws.blockedDomains());
                if (ws.maxUses() != null) b.maxUses(ws.maxUses());
                yield ToolUnion.ofWebSearchTool20260209(b.build());
            }
            case ServerToolSpec.WebFetch wf -> {
                com.anthropic.models.messages.WebFetchTool20260209.Builder b =
                        com.anthropic.models.messages.WebFetchTool20260209.builder();
                if (!wf.allowedDomains().isEmpty()) b.allowedDomains(wf.allowedDomains());
                if (wf.maxUses() != null) b.maxUses(wf.maxUses());
                if (wf.maxContentTokens() != null) b.maxContentTokens(wf.maxContentTokens());
                yield ToolUnion.ofWebFetchTool20260209(b.build());
            }
            case ServerToolSpec.CodeExecution ce -> ToolUnion.ofCodeExecutionTool20260120(
                    com.anthropic.models.messages.CodeExecutionTool20260120.builder().build());
            case ServerToolSpec.Memory m -> ToolUnion.ofMemoryTool20250818(
                    com.anthropic.models.messages.MemoryTool20250818.builder().build());
        };
    }

    static ToolUnion toToolUnion(AnthropicToolSpec spec, Optional<CacheControlEphemeral> cache) {
        Tool.InputSchema schema = buildInputSchema(spec.inputSchema());
        Tool.Builder tb = Tool.builder()
                .name(spec.name())
                .description(spec.description())
                .inputSchema(schema);
        cache.ifPresent(tb::cacheControl);
        return ToolUnion.ofTool(tb.build());
    }

    static Tool.InputSchema buildInputSchema(Map<String, Object> schema) {
        // The SDK's InputSchema has a fixed "type: object" implicit; properties + required are typed.
        // We use putAdditionalProperty to pass through arbitrary JSON schema fields the user supplied
        // (e.g. nested objects, enums, anyOf) without constraining the shape.
        Tool.InputSchema.Builder b = Tool.InputSchema.builder();
        for (Map.Entry<String, Object> e : schema.entrySet()) {
            switch (e.getKey()) {
                case "type" -> { /* type is implicit "object" in the SDK */ }
                case "required" -> {
                    if (e.getValue() instanceof List<?> list) {
                        for (Object o : list) {
                            b.addRequired(String.valueOf(o));
                        }
                    }
                }
                case "properties" -> {
                    if (e.getValue() instanceof Map<?, ?> props) {
                        Tool.InputSchema.Properties.Builder pb = Tool.InputSchema.Properties.builder();
                        for (Map.Entry<?, ?> p : props.entrySet()) {
                            pb.putAdditionalProperty(String.valueOf(p.getKey()),
                                    JsonValue.from(p.getValue()));
                        }
                        b.properties(pb.build());
                    }
                }
                default -> b.putAdditionalProperty(e.getKey(), JsonValue.from(e.getValue()));
            }
        }
        return b.build();
    }

    // ---------- Content blocks (request side) ----------

    static ContentBlockParam toContentBlockParam(AnthropicContent content, Optional<CacheControlEphemeral> cache) {
        return switch (content) {
            case AnthropicContent.Text t -> {
                TextBlockParam.Builder b = TextBlockParam.builder().text(t.text());
                cache.ifPresent(b::cacheControl);
                yield ContentBlockParam.ofText(b.build());
            }
            case AnthropicContent.ToolUse tu -> {
                ToolUseBlockParam.Builder b = ToolUseBlockParam.builder()
                        .id(tu.id())
                        .name(tu.name())
                        .input(buildToolUseInput(tu.input()));
                cache.ifPresent(b::cacheControl);
                yield ContentBlockParam.ofToolUse(b.build());
            }
            case AnthropicContent.ToolResult tr -> {
                ToolResultBlockParam.Builder b = ToolResultBlockParam.builder()
                        .toolUseId(tr.toolUseId())
                        .content(tr.content())
                        .isError(tr.isError());
                cache.ifPresent(b::cacheControl);
                yield ContentBlockParam.ofToolResult(b.build());
            }
            case AnthropicContent.Thinking th -> {
                // Echoing thinking blocks back to the model on multi-turn continuity:
                // the signature is required by the SDK; we drop blocks lacking one.
                if (th.signature() == null || th.signature().isEmpty()) {
                    throw new IllegalArgumentException(
                            "Cannot replay thinking block without signature — use ThinkingDisplay.FULL");
                }
                yield ContentBlockParam.ofThinking(
                        com.anthropic.models.messages.ThinkingBlockParam.builder()
                                .thinking(th.text())
                                .signature(th.signature())
                                .build());
            }
            case AnthropicContent.Image img -> {
                ImageBlockParam.Builder b = ImageBlockParam.builder();
                if (img.url() != null) {
                    b.source(com.anthropic.models.messages.UrlImageSource.builder().url(img.url()).build());
                } else {
                    com.anthropic.models.messages.Base64ImageSource.MediaType mt = mediaType(img.mediaType());
                    b.source(com.anthropic.models.messages.Base64ImageSource.builder()
                            .data(img.base64Data())
                            .mediaType(mt)
                            .build());
                }
                cache.ifPresent(b::cacheControl);
                yield ContentBlockParam.ofImage(b.build());
            }
        };
    }

    private static com.anthropic.models.messages.Base64ImageSource.MediaType mediaType(String mt) {
        return switch (mt) {
            case "image/jpeg" -> com.anthropic.models.messages.Base64ImageSource.MediaType.IMAGE_JPEG;
            case "image/png" -> com.anthropic.models.messages.Base64ImageSource.MediaType.IMAGE_PNG;
            case "image/gif" -> com.anthropic.models.messages.Base64ImageSource.MediaType.IMAGE_GIF;
            case "image/webp" -> com.anthropic.models.messages.Base64ImageSource.MediaType.IMAGE_WEBP;
            default -> throw new IllegalArgumentException("Unsupported image media type: " + mt);
        };
    }

    private static ToolUseBlockParam.Input buildToolUseInput(Map<String, Object> input) {
        ToolUseBlockParam.Input.Builder b = ToolUseBlockParam.Input.builder();
        for (Map.Entry<String, Object> e : input.entrySet()) {
            b.putAdditionalProperty(e.getKey(), JsonValue.from(e.getValue()));
        }
        return b.build();
    }

    // ---------- Messages ----------

    static MessageParam toMessageParam(
            AnthropicMessage m,
            List<DocumentBlock> documentsToPrepend,
            Optional<CacheControlEphemeral> lastDocCache,
            Optional<CacheControlEphemeral> messageCache) {
        MessageParam.Role role = (m.role() == AnthropicRole.USER)
                ? MessageParam.Role.USER : MessageParam.Role.ASSISTANT;

        List<ContentBlockParam> blocks = new ArrayList<>();

        // Documents are attached to the first user message per Anthropic convention.
        // The caller passes a non-empty list here only for the first user turn.
        if (!documentsToPrepend.isEmpty()) {
            for (int i = 0; i < documentsToPrepend.size(); i++) {
                DocumentBlock doc = documentsToPrepend.get(i);
                Optional<CacheControlEphemeral> cc =
                        (i == documentsToPrepend.size() - 1) ? lastDocCache : Optional.empty();
                blocks.add(ContentBlockParam.ofDocument(toDocumentBlockParam(doc, cc)));
            }
        }

        List<AnthropicContent> contents = m.content();
        for (int i = 0; i < contents.size(); i++) {
            Optional<CacheControlEphemeral> cc =
                    (i == contents.size() - 1) ? messageCache : Optional.empty();
            blocks.add(toContentBlockParam(contents.get(i), cc));
        }

        return MessageParam.builder().role(role).contentOfBlockParams(blocks).build();
    }

    // ---------- Thinking ----------

    static Optional<ThinkingConfigParam> toThinkingConfig(ThinkingBudget budget) {
        if (!budget.isEnabled()) {
            return Optional.empty();
        }
        return switch (budget.mode()) {
            case DISABLED -> Optional.empty();
            case ENABLED, INTERLEAVED -> {
                ThinkingConfigEnabled enabled = ThinkingConfigEnabled.builder()
                        .budgetTokens(budget.budgetTokens())
                        .build();
                yield Optional.of(ThinkingConfigParam.ofEnabled(enabled));
            }
            case ADAPTIVE -> Optional.of(ThinkingConfigParam.ofAdaptive(
                    com.anthropic.models.messages.ThinkingConfigAdaptive.builder().build()));
        };
    }

    /**
     * For ADAPTIVE thinking, the model also wants {@code output_config.effort}
     * to size its reasoning ceiling. Returns the SDK's {@code OutputConfig}
     * with the effort enum populated, or {@code Optional.empty()} for other
     * modes (which use {@code thinking.budget_tokens} instead).
     */
    static Optional<com.anthropic.models.messages.OutputConfig> toOutputConfig(ThinkingBudget budget) {
        if (budget.mode() != ai.intelliswarm.swarmai.agent.llm.ThinkingMode.ADAPTIVE) {
            return Optional.empty();
        }
        com.anthropic.models.messages.OutputConfig.Effort sdkEffort = switch (budget.effort()) {
            case LOW    -> com.anthropic.models.messages.OutputConfig.Effort.LOW;
            case MEDIUM -> com.anthropic.models.messages.OutputConfig.Effort.MEDIUM;
            case HIGH   -> com.anthropic.models.messages.OutputConfig.Effort.HIGH;
            case XHIGH  -> com.anthropic.models.messages.OutputConfig.Effort.XHIGH;
            case MAX    -> com.anthropic.models.messages.OutputConfig.Effort.MAX;
        };
        return Optional.of(com.anthropic.models.messages.OutputConfig.builder()
                .effort(sdkEffort)
                .build());
    }

    // ---------- Response parsing ----------

    static List<AnthropicContent> fromContentBlocks(List<ContentBlock> blocks, List<DocumentBlock> requestDocs) {
        List<AnthropicContent> out = new ArrayList<>(blocks.size());
        for (ContentBlock cb : blocks) {
            if (cb.isText()) {
                com.anthropic.models.messages.TextBlock tb = cb.asText();
                List<InlineCitation> cits = new ArrayList<>();
                tb.citations().ifPresent(list -> {
                    for (TextCitation tc : list) {
                        InlineCitation ic = fromTextCitation(tc, requestDocs);
                        if (ic != null) {
                            cits.add(ic);
                        }
                    }
                });
                out.add(new AnthropicContent.Text(tb.text(), cits));
            } else if (cb.isToolUse()) {
                com.anthropic.models.messages.ToolUseBlock tu = cb.asToolUse();
                Map<String, Object> input = toolUseInputToMap(tu._input());
                out.add(new AnthropicContent.ToolUse(tu.id(), tu.name(), input));
            } else if (cb.isThinking()) {
                com.anthropic.models.messages.ThinkingBlock th = cb.asThinking();
                out.add(new AnthropicContent.Thinking(th.thinking(), th.signature()));
            }
            // Server-tool blocks (web_search results, code_execution outputs, etc.) are
            // intentionally not surfaced in Phase 1 — they'll get their own variants in
            // Phase 9 when the server-tool adapters land.
        }
        return out;
    }

    static InlineCitation fromTextCitation(TextCitation tc, List<DocumentBlock> requestDocs) {
        if (tc.isCharLocation()) {
            CitationCharLocation c = tc.asCharLocation();
            String docId = resolveDocId((int) c.documentIndex(), requestDocs);
            return new InlineCitation(
                    docId,
                    c.documentTitle().orElse(null),
                    new CitationLocation.CharLocation((int) c.startCharIndex(), (int) c.endCharIndex()),
                    c.citedText());
        }
        if (tc.isPageLocation()) {
            CitationPageLocation c = tc.asPageLocation();
            String docId = resolveDocId((int) c.documentIndex(), requestDocs);
            return new InlineCitation(
                    docId,
                    c.documentTitle().orElse(null),
                    new CitationLocation.PageLocation((int) c.startPageNumber(), (int) c.endPageNumber()),
                    c.citedText());
        }
        if (tc.isContentBlockLocation()) {
            CitationContentBlockLocation c = tc.asContentBlockLocation();
            String docId = resolveDocId((int) c.documentIndex(), requestDocs);
            return new InlineCitation(
                    docId,
                    c.documentTitle().orElse(null),
                    new CitationLocation.ContentBlockLocation((int) c.startBlockIndex(), (int) c.endBlockIndex()),
                    c.citedText());
        }
        // web-search-result citations + search-result citations are skipped in Phase 1
        return null;
    }

    private static String resolveDocId(int sdkIndex, List<DocumentBlock> docs) {
        if (sdkIndex >= 0 && sdkIndex < docs.size()) {
            return docs.get(sdkIndex).id();
        }
        // Defensive fallback — the SDK's documentIndex points into the original
        // request's document array; if we can't map it, surface the raw index so
        // audit logs aren't broken.
        return "doc:" + sdkIndex;
    }

    private static Map<String, Object> toolUseInputToMap(JsonField<?> input) {
        Object raw = input.toString();
        // The SDK exposes the parsed input via Message.content() → ToolUseBlock — the
        // value is a JsonValue (untyped). For Phase 1 we serialize via the SDK's
        // JsonValue mapping, which the agent runtime can re-deserialize into typed args.
        if (input.isMissing()) {
            return Map.of();
        }
        try {
            Object jsonObj = input.asKnown().get();
            if (jsonObj instanceof Map<?, ?> mapVal) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) mapVal;
                return Map.copyOf(typed);
            }
            return Map.of("value", jsonObj);
        } catch (Exception ignore) {
            return Map.of("raw", raw);
        }
    }

    static TokenUsage fromUsage(Usage usage) {
        long cacheRead = usage.cacheReadInputTokens().orElse(0L);
        long cache5m = 0L;
        long cache1h = 0L;
        if (usage.cacheCreation().isPresent()) {
            cache5m = usage.cacheCreation().get().ephemeral5mInputTokens();
            cache1h = usage.cacheCreation().get().ephemeral1hInputTokens();
        } else {
            // Older responses report only the flat cache_creation_input_tokens
            cache5m = usage.cacheCreationInputTokens().orElse(0L);
        }
        long serverToolIn = 0L;
        long serverToolOut = 0L;
        if (usage.serverToolUse().isPresent()) {
            var stu = usage.serverToolUse().get();
            // ServerToolUsage exposes per-tool counts; we collapse to totals for Phase 1.
            // (Streaming + per-tool breakdown comes in Phase 9.)
            serverToolIn = 0;
            serverToolOut = 0;
            // Note: the SDK API for these counters varies across versions; defensive default.
        }
        return new TokenUsage(
                usage.inputTokens(),
                usage.outputTokens(),
                new CacheUsage(cacheRead, cache5m, cache1h),
                serverToolIn,
                serverToolOut);
    }

    static StopReason fromStopReason(Optional<com.anthropic.models.messages.StopReason> sdk) {
        if (sdk.isEmpty()) {
            return StopReason.UNKNOWN;
        }
        var known = sdk.get().known();
        return switch (known) {
            case END_TURN -> StopReason.END_TURN;
            case MAX_TOKENS -> StopReason.MAX_TOKENS;
            case STOP_SEQUENCE -> StopReason.STOP_SEQUENCE;
            case TOOL_USE -> StopReason.TOOL_USE;
            case PAUSE_TURN -> StopReason.PAUSE_TURN;
            case REFUSAL -> StopReason.REFUSAL;
            default -> StopReason.UNKNOWN;
        };
    }

    // ---------- Slot resolution for cache breakpoints ----------

    /** Find the breakpoint whose slot equals {@code slot}, if any. */
    static Optional<CacheBreakpoint> findBreakpoint(CachePolicy policy, String slot) {
        return policy.forSlot(slot);
    }

    /** Map a breakpoint to its CacheControlEphemeral, if present. */
    static Optional<CacheControlEphemeral> cacheFor(CachePolicy policy, String slot) {
        return findBreakpoint(policy, slot).map(bp -> toCacheControl(bp.tier()));
    }
}
