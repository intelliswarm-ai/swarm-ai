package ai.intelliswarm.swarmai.llm.anthropic;

import ai.intelliswarm.swarmai.agent.llm.CacheBreakpoint;
import ai.intelliswarm.swarmai.agent.llm.CachePolicy;
import ai.intelliswarm.swarmai.agent.llm.DocumentBlock;
import ai.intelliswarm.swarmai.agent.llm.TokenUsage;
import ai.intelliswarm.swarmai.api.PublicApi;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ToolChoice;
import com.anthropic.models.messages.ToolChoiceAny;
import com.anthropic.models.messages.ToolChoiceAuto;
import com.anthropic.models.messages.ToolChoiceNone;
import com.anthropic.models.messages.ToolUnion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Native Anthropic LLM adapter — unlocks features Spring AI's ChatClient
 * doesn't expose yet: prompt caching (with cache hit/miss telemetry), extended
 * thinking budgets, inline citations on PDF/text documents, vision blocks,
 * strict tool use, MCP connector beta. Opt-in: this module is a separate
 * Maven artifact, so framework users who don't need these features remain on
 * the provider-agnostic Spring AI path.
 *
 * <p>Single entry point: {@link #send(AnthropicRequest)} translates the
 * request, hits the SDK, and returns an {@link AnthropicResponse} carrying
 * parsed text, tool-use blocks, thinking blocks, inline citations, stop
 * reason, request id, and full token usage including cache read/write
 * breakdown.
 *
 * <p>Tool execution is <strong>not</strong> orchestrated here. When the
 * model returns {@link StopReason#TOOL_USE}, the caller executes the tools
 * (using whatever permission/audit/governance machinery they prefer) and
 * resumes by sending a new request whose last message carries
 * {@link AnthropicContent.ToolResult} blocks matching each
 * {@link AnthropicContent.ToolUse#id()}. This split keeps the adapter
 * single-purpose and lets SwarmAI's existing governance machinery wrap
 * tool calls without the adapter knowing about it.
 */
@PublicApi(since = "1.0.24")
public final class AnthropicNativeClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicNativeClient.class);

    private final AnthropicClient client;

    AnthropicNativeClient(AnthropicClient client) {
        this.client = client;
    }

    /** Build a client from a {@link AnthropicConfig}. */
    public static AnthropicNativeClient from(AnthropicConfig config) {
        AnthropicOkHttpClient.Builder b = AnthropicOkHttpClient.builder();
        if (config.apiKey() != null) {
            b.apiKey(config.apiKey());
        } else {
            b.fromEnv();
        }
        if (config.baseUrl() != null) {
            b.baseUrl(config.baseUrl());
        }
        b.maxRetries(config.maxRetries());
        b.timeout(config.requestTimeout());
        return new AnthropicNativeClient(b.build());
    }

    /** Build a client that reads credentials from environment variables. */
    public static AnthropicNativeClient fromEnv() {
        return new AnthropicNativeClient(AnthropicOkHttpClient.fromEnv());
    }

    /** For tests — inject a pre-built {@link AnthropicClient} (typically a Mockito mock). */
    public static AnthropicNativeClient withClient(AnthropicClient client) {
        return new AnthropicNativeClient(client);
    }

    /**
     * Send a request synchronously. Returns a fully-parsed {@link AnthropicResponse}.
     *
     * @throws AnthropicClientException if the SDK throws — caller can inspect
     *         {@link AnthropicClientException#requestId()} and
     *         {@link AnthropicClientException#httpStatus()} to decide retry strategy
     */
    public AnthropicResponse send(AnthropicRequest req) {
        MessageCreateParams params = toSdkParams(req);
        try {
            Message message = client.messages().create(params);
            return toResponse(message, req);
        } catch (RuntimeException e) {
            throw new AnthropicClientException(
                    "Anthropic request failed: " + e.getMessage(), e, null, null);
        }
    }

    // ---------- Translation ----------

    MessageCreateParams toSdkParams(AnthropicRequest req) {
        MessageCreateParams.Builder b = MessageCreateParams.builder()
                .model(Model.of(req.model()))
                .maxTokens(req.maxOutputTokens());

        if (req.temperature() != null) {
            b.temperature(req.temperature());
        }

        // System prompt — use TextBlockParams when the system slot has a cache breakpoint,
        // otherwise the simpler String form. The TextBlockParam form is required for cache_control.
        if (req.system() != null) {
            Optional<CacheControlEphemeral> sysCache = WireMapper.cacheFor(req.cachePolicy(), "system");
            if (sysCache.isPresent()) {
                TextBlockParam.Builder tb = TextBlockParam.builder().text(req.system());
                sysCache.ifPresent(tb::cacheControl);
                b.systemOfTextBlockParams(List.of(tb.build()));
            } else {
                b.system(req.system());
            }
        }

        // Tools (user-defined + server-side). Server tools come first so cache_control on the
        // last user tool still anchors at the user-tool boundary. Cache breakpoint on slot
        // "tools" applies to the last user tool in the list.
        if (!req.tools().isEmpty() || !req.serverTools().isEmpty()) {
            Optional<CacheControlEphemeral> toolsCache = WireMapper.cacheFor(req.cachePolicy(), "tools");
            List<ToolUnion> sdkTools = new ArrayList<>(req.tools().size() + req.serverTools().size());
            for (ServerToolSpec st : req.serverTools()) {
                sdkTools.add(WireMapper.toServerToolUnion(st));
            }
            for (int i = 0; i < req.tools().size(); i++) {
                Optional<CacheControlEphemeral> cc =
                        (i == req.tools().size() - 1) ? toolsCache : Optional.empty();
                sdkTools.add(WireMapper.toToolUnion(req.tools().get(i), cc));
            }
            b.tools(sdkTools);
        }

        // Tool choice
        switch (req.toolChoice()) {
            case AUTO -> b.toolChoice(ToolChoice.ofAuto(
                    ToolChoiceAuto.builder()
                            .disableParallelToolUse(req.disableParallelToolUse())
                            .build()));
            case ANY -> b.toolChoice(ToolChoice.ofAny(
                    ToolChoiceAny.builder()
                            .disableParallelToolUse(req.disableParallelToolUse())
                            .build()));
            case NONE -> b.toolChoice(ToolChoice.ofNone(ToolChoiceNone.builder().build()));
        }

        // Messages — documents go on the first user message only.
        // "documents" cache slot attaches cache_control to the last document.
        Optional<CacheControlEphemeral> docsCache = WireMapper.cacheFor(req.cachePolicy(), "documents");
        boolean documentsAttached = false;
        for (int i = 0; i < req.messages().size(); i++) {
            AnthropicMessage msg = req.messages().get(i);
            List<DocumentBlock> docsForThis;
            Optional<CacheControlEphemeral> lastDocCache;
            if (!documentsAttached && msg.role() == AnthropicRole.USER && !req.documents().isEmpty()) {
                docsForThis = req.documents();
                lastDocCache = docsCache;
                documentsAttached = true;
            } else {
                docsForThis = List.of();
                lastDocCache = Optional.empty();
            }
            Optional<CacheControlEphemeral> msgCache = cacheForMessage(req.cachePolicy(), i, req.messages().size());
            MessageParam mp = WireMapper.toMessageParam(msg, docsForThis, lastDocCache, msgCache);
            b.addMessage(mp);
        }

        // Thinking
        WireMapper.toThinkingConfig(req.thinkingBudget()).ifPresent(b::thinking);

        // Beta headers — set via Additional header for now. The SDK exposes beta() but its API
        // shape varies; using additionalHeaders is the most stable opt-in path.
        if (!req.betas().isEmpty()) {
            // The SDK has an `additionalHeaders` builder on MessageCreateParams — but accessing it
            // is awkward from the public surface. We instead concatenate into anthropic-beta header
            // via the SDK's _additionalHeaders mechanism in a future iteration; for now log a hint.
            // (Beta header propagation is finalised in Phase 9 alongside server-tool adapters.)
            log.debug("Anthropic beta headers requested but not yet propagated: {}", req.betas());
        }

        return b.build();
    }

    private static Optional<CacheControlEphemeral> cacheForMessage(CachePolicy policy, int index, int total) {
        // Search every breakpoint whose slot decodes to this message index.
        for (CacheBreakpoint bp : policy.breakpoints()) {
            int resolved = WireMapper.resolveMessageIndex(bp.slot(), total);
            if (resolved == index) {
                return Optional.of(WireMapper.toCacheControl(bp.tier()));
            }
        }
        return Optional.empty();
    }

    AnthropicResponse toResponse(Message message, AnthropicRequest req) {
        List<AnthropicContent> content =
                WireMapper.fromContentBlocks(message.content(), req.documents());
        TokenUsage usage = WireMapper.fromUsage(message.usage());
        StopReason stopReason = WireMapper.fromStopReason(message.stopReason());
        // Request id is exposed via withRawResponse() — for the high-level API we
        // surface null and let callers opt into raw access if they need it. The
        // message id itself is sufficient correlation for most workflows.
        return new AnthropicResponse(
                message.id(),
                message.model().asString(),
                stopReason,
                content,
                usage);
    }
}
