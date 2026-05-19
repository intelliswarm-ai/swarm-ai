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
 * @since 1.0.25
 */
@PublicApi(since = "1.0.25")
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
        // Spring AI's ChatModel.stream returns one ChatResponse per chunk.
        // We accumulate text deltas (emit each to the callback) and capture
        // the final assistant message (with tool calls + usage) from the last chunk.
        StringBuilder text = new StringBuilder();
        ChatResponse[] last = new ChatResponse[1];
        Flux<ChatResponse> stream = chatModel.stream(prompt);
        stream.toIterable().forEach(chunk -> {
            last[0] = chunk;
            AssistantMessage am = chunk.getResult() != null ? chunk.getResult().getOutput() : null;
            if (am != null && am.getText() != null && !am.getText().isEmpty()) {
                textDelta.accept(am.getText());
                text.append(am.getText());
            }
        });
        if (last[0] == null) {
            return new LlmResponse(null, req.model(),
                    LlmStopReason.END_TURN,
                    "", List.of(), List.of(),
                    new TokenUsage(0, 0, CacheUsage.NONE, 0, 0));
        }
        return toLlmResponse(last[0], text.toString());
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
        Generation gen = resp.getResult();
        AssistantMessage msg = gen.getOutput();
        // When streaming, the FINAL chunk's message may be empty (text already emitted)
        // — prefer the accumulated streamedText if we have it.
        String text;
        if (streamedText != null && !streamedText.isEmpty()) {
            text = streamedText;
        } else {
            text = msg.getText() == null ? "" : msg.getText();
        }

        List<LlmToolCall> calls = new ArrayList<>();
        if (msg.hasToolCalls()) {
            for (AssistantMessage.ToolCall c : msg.getToolCalls()) {
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

        LlmStopReason stop = calls.isEmpty()
                ? LlmStopReason.END_TURN
                : LlmStopReason.TOOL_USE;

        long input = 0, output = 0;
        var usage = resp.getMetadata() != null ? resp.getMetadata().getUsage() : null;
        if (usage != null) {
            Integer pt = usage.getPromptTokens();
            Integer ct = usage.getCompletionTokens();
            if (pt != null) input = pt;
            if (ct != null) output = ct;
        }

        return new LlmResponse(
                null,
                resp.getMetadata() != null ? resp.getMetadata().getModel() : null,
                stop,
                text,
                calls,
                List.of(),
                new TokenUsage(input, output, CacheUsage.NONE, 0, 0));
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
