package ai.intelliswarm.swarmai.agent.llm;

import ai.intelliswarm.swarmai.api.PublicApi;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Provider-neutral {@link LlmClient} backed by a Spring AI {@link ChatModel}.
 * Lets agents run on OpenAI, Azure, Ollama, Bedrock, Vertex, or Spring AI's
 * Anthropic adapter — anything with a Spring AI starter on the classpath.
 *
 * <p>Trade-off: substrate features that Spring AI doesn't expose at the
 * ChatModel level (prompt caching, extended thinking, native citations) are
 * silently dropped. For full feature parity on Anthropic specifically, use
 * the native {@code AnthropicLlmClient} from {@code swarmai-native-anthropic}.
 *
 * @since 1.0.26
 */
@PublicApi(since = "1.0.26")
public class SpringAiLlmClient implements StreamingLlmClient {

    private static final Set<Feature> SUPPORTED = EnumSet.of(
            Feature.STRICT_TOOL_USE, Feature.STREAMING);

    private final ChatModel chatModel;
    private final ObjectMapper json = new ObjectMapper();

    public SpringAiLlmClient(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override public String name() { return "spring-ai"; }
    @Override public Set<Feature> supportedFeatures() { return SUPPORTED; }

    @Override
    public LlmResponse sendStreaming(LlmRequest req, Consumer<String> textDelta) {
        Prompt prompt = buildPrompt(req);
        // Spring AI's ChatModel.stream returns one ChatResponse per chunk. We
        // emit text deltas live AND accumulate any tool_use calls that arrive
        // across chunks — they may appear before the final chunk and would be
        // lost if we only inspected the last one (see toLlmResponse for the
        // matching multi-Generation handling on the non-streaming path).
        StringBuilder text = new StringBuilder();
        // Keyed by tool-call id so a tool_use surfaced across several chunks is
        // recorded once. Insertion order is preserved (LinkedHashMap) so the
        // agent sees calls in the order the model emitted them.
        java.util.LinkedHashMap<String, LlmToolCall> callsById = new java.util.LinkedHashMap<>();
        ChatResponse[] last = new ChatResponse[1];
        Flux<ChatResponse> stream = chatModel.stream(prompt);
        stream.toIterable().forEach(chunk -> {
            last[0] = chunk;
            List<Generation> gens = chunk.getResults() != null && !chunk.getResults().isEmpty()
                    ? chunk.getResults()
                    : (chunk.getResult() != null ? List.of(chunk.getResult()) : List.of());
            for (Generation g : gens) {
                AssistantMessage am = g.getOutput();
                if (am == null) continue;
                String tx = am.getText();
                if (tx != null && !tx.isEmpty()) {
                    textDelta.accept(tx);
                    text.append(tx);
                }
                if (am.hasToolCalls()) {
                    for (AssistantMessage.ToolCall c : am.getToolCalls()) {
                        Map<String, Object> args;
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> parsed = json.readValue(c.arguments(), Map.class);
                            args = parsed;
                        } catch (Exception e) {
                            args = Map.of();
                        }
                        String id = c.id() != null ? c.id() : UUID.randomUUID().toString();
                        LlmToolCall existing = callsById.get(id);
                        // First time → record. Recurring id → keep the richer
                        // version: streaming may surface the same tool_use with
                        // empty args first and the parsed args in a later chunk.
                        if (existing == null
                                || (existing.arguments().isEmpty() && !args.isEmpty())) {
                            callsById.put(id, new LlmToolCall(id, c.name(), args));
                        }
                    }
                }
            }
        });
        if (last[0] == null) {
            return new LlmResponse(null, req.model(),
                    LlmStopReason.END_TURN,
                    "", List.of(), List.of(),
                    new TokenUsage(0, 0, CacheUsage.NONE, 0, 0));
        }
        LlmResponse base = toLlmResponse(last[0], text.toString());
        // callsById holds every tool_use from every chunk; base only carries
        // the LAST chunk's. Whenever we accumulated anything, that superset is
        // the authoritative list — using base.toolCalls() alone would drop
        // calls emitted in earlier chunks.
        if (!callsById.isEmpty()) {
            return new LlmResponse(base.requestId(), base.modelId(),
                    LlmStopReason.TOOL_USE, base.text(),
                    new ArrayList<>(callsById.values()),
                    base.citations(), base.tokenUsage());
        }
        return base;
    }

    @Override
    public LlmResponse send(LlmRequest req) {
        Prompt prompt = buildPrompt(req);
        ChatResponse resp = chatModel.call(prompt);
        return toLlmResponse(resp, null);
    }

    private Prompt buildPrompt(LlmRequest req) {
        List<Message> messages = new ArrayList<>();
        if (req.system() != null && !req.system().isBlank()) {
            messages.add(new SystemMessage(req.system()));
        }
        for (LlmMessage m : req.messages()) {
            messages.add(toSpringMessage(m));
        }

        List<ToolCallback> callbacks = new ArrayList<>();
        for (BaseTool t : req.tools()) {
            callbacks.add(new BaseToolCallback(t, json));
        }

        if (callbacks.isEmpty()) {
            var optsBuilder = org.springframework.ai.chat.prompt.ChatOptions.builder()
                    .model(req.model())
                    .maxTokens(req.maxOutputTokens());
            if (req.temperature() != null) optsBuilder.temperature(req.temperature());
            return new Prompt(messages, optsBuilder.build());
        }
        return new Prompt(messages,
                org.springframework.ai.model.tool.ToolCallingChatOptions.builder()
                        .model(req.model())
                        .maxTokens(req.maxOutputTokens())
                        .temperature(req.temperature())
                        .toolCallbacks(callbacks)
                        .internalToolExecutionEnabled(false)
                        .build());
    }

    private Message toSpringMessage(LlmMessage m) {
        return switch (m.role()) {
            case SYSTEM -> new SystemMessage(m.content());
            case USER -> new UserMessage(m.content());
            case ASSISTANT -> {
                if (m.toolCalls().isEmpty()) {
                    yield new AssistantMessage(m.content());
                }
                List<AssistantMessage.ToolCall> calls = new ArrayList<>();
                for (LlmToolCall c : m.toolCalls()) {
                    String args;
                    try { args = json.writeValueAsString(c.arguments()); }
                    catch (JsonProcessingException e) { args = "{}"; }
                    calls.add(new AssistantMessage.ToolCall(c.id(), "function", c.toolName(), args));
                }
                yield new AssistantMessage(m.content(), Map.of(), calls);
            }
            case TOOL -> new ToolResponseMessage(
                    List.of(new ToolResponseMessage.ToolResponse(m.toolUseId(), "tool", m.content())));
        };
    }

    private LlmResponse toLlmResponse(ChatResponse resp, String streamedText) {
        // Spring AI's AnthropicChatModel returns ONE Generation per content block
        // in the Anthropic response. When the model produces both a text block AND
        // a tool_use block (the common case for a thinking tool-call), there are
        // two Generations — only iterating resp.getResult() drops the tool_use.
        // Iterate all generations and accumulate text + tool calls from each.
        List<Generation> generations = resp.getResults() != null && !resp.getResults().isEmpty()
                ? resp.getResults()
                : (resp.getResult() != null ? List.of(resp.getResult()) : List.of());

        StringBuilder textAccum = new StringBuilder();
        List<LlmToolCall> calls = new ArrayList<>();
        for (Generation g : generations) {
            AssistantMessage gm = g.getOutput();
            if (gm == null) continue;
            String gText = gm.getText();
            if (gText != null && !gText.isEmpty()) {
                if (textAccum.length() > 0) textAccum.append('\n');
                textAccum.append(gText);
            }
            if (gm.hasToolCalls()) {
                for (AssistantMessage.ToolCall c : gm.getToolCalls()) {
                    Map<String, Object> args;
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> parsed = json.readValue(c.arguments(), Map.class);
                        args = parsed;
                    } catch (Exception e) {
                        args = Map.of();
                    }
                    calls.add(new LlmToolCall(
                            c.id() != null ? c.id() : UUID.randomUUID().toString(),
                            c.name(), args));
                }
            }
        }

        // When streaming, the per-chunk message text may be empty (deltas already
        // emitted) — prefer the accumulated streamedText if we have it.
        String text;
        if (streamedText != null && !streamedText.isEmpty()) {
            text = streamedText;
        } else {
            text = textAccum.toString();
        }

        LlmStopReason stop = calls.isEmpty()
                ? LlmStopReason.END_TURN
                : LlmStopReason.TOOL_USE;

        long input = 0, output = 0;
        var usage = resp.getMetadata() != null ? resp.getMetadata().getUsage() : null;
        CacheUsage cacheUsage = CacheUsage.NONE;
        if (usage != null) {
            Integer pt = usage.getPromptTokens();
            Integer ct = usage.getCompletionTokens();
            if (pt != null) input = pt;
            if (ct != null) output = ct;
            cacheUsage = extractCacheUsage(usage.getNativeUsage());
        }

        return new LlmResponse(
                null,
                resp.getMetadata() != null ? resp.getMetadata().getModel() : null,
                stop,
                text,
                calls,
                List.of(),
                new TokenUsage(input, output, cacheUsage, 0, 0));
    }

    /**
     * Surface provider-side cache telemetry that {@link org.springframework.ai.chat.metadata.Usage}'s
     * generic interface hides — OpenAI's {@code promptTokensDetails.cachedTokens}
     * is the headline case. Reflective so swarmai-core doesn't need a
     * compile-time dep on spring-ai-openai (the only consumer that has the
     * shaped record today).
     */
    private static CacheUsage extractCacheUsage(Object nativeUsage) {
        if (nativeUsage == null) return CacheUsage.NONE;
        try {
            var method = nativeUsage.getClass().getMethod("promptTokensDetails");
            Object details = method.invoke(nativeUsage);
            if (details == null) return CacheUsage.NONE;
            var cachedMethod = details.getClass().getMethod("cachedTokens");
            Object cached = cachedMethod.invoke(details);
            if (cached instanceof Integer c && c > 0) {
                // OpenAI auto-cache: surface as cacheReadInputTokens. We
                // don't know whether it was a 5-min or 1-hour cache because
                // OpenAI's API doesn't distinguish, so leave creation slots at 0.
                return new CacheUsage(c.longValue(), 0L, 0L);
            }
        } catch (NoSuchMethodException ignored) {
            // Provider doesn't expose this shape — fine, return NONE.
        } catch (Exception e) {
            // Reflection went sideways; don't blow up the response over telemetry.
        }
        return CacheUsage.NONE;
    }

    /** Bridge a SwarmAI {@link BaseTool} to Spring AI's {@link ToolCallback}. */
    private static final class BaseToolCallback implements ToolCallback {
        private final BaseTool tool;
        private final ObjectMapper json;
        private final ToolDefinition def;

        BaseToolCallback(BaseTool tool, ObjectMapper json) {
            this.tool = tool;
            this.json = json;
            String schemaJson;
            try { schemaJson = json.writeValueAsString(tool.getParameterSchema()); }
            catch (Exception e) { schemaJson = "{\"type\":\"object\"}"; }
            this.def = ToolDefinition.builder()
                    .name(tool.getFunctionName())
                    .description(tool.getDescription())
                    .inputSchema(schemaJson)
                    .build();
        }

        @Override public ToolDefinition getToolDefinition() { return def; }
        @Override public ToolMetadata getToolMetadata() { return ToolMetadata.builder().build(); }

        @Override
        public String call(String toolInput) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> args = json.readValue(toolInput, Map.class);
                Object out = tool.execute(args);
                return out == null ? "" : out.toString();
            } catch (Exception e) {
                return "Error invoking " + tool.getFunctionName() + ": " + e.getMessage();
            }
        }
    }
}
