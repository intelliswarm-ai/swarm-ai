package ai.intelliswarm.swarmai.agent.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("StreamingLlmClient SPI (1.0.26)")
class StreamingLlmClientTest {

    @Test
    @DisplayName("fallback() emits entire response text once and returns the response")
    void fallbackEmitsFullTextOnce() {
        LlmResponse canned = new LlmResponse(
                null, "test-model", LlmStopReason.END_TURN,
                "hello world", List.of(), List.of(),
                new TokenUsage(10, 5, CacheUsage.NONE, 0, 0));

        LlmClient stub = new LlmClient() {
            @Override public String name() { return "stub"; }
            @Override public Set<Feature> supportedFeatures() { return Set.of(); }
            @Override public LlmResponse send(LlmRequest req) { return canned; }
        };

        List<String> chunks = new ArrayList<>();
        LlmResponse out = StreamingLlmClient.fallback(stub, simpleRequest(), chunks::add);

        assertSame(canned, out);
        assertEquals(List.of("hello world"), chunks);
    }

    @Test
    @DisplayName("fallback() emits nothing when response text is null or empty")
    void fallbackSwallowsEmptyText() {
        LlmResponse blank = new LlmResponse(
                null, "test-model", LlmStopReason.END_TURN,
                "", List.of(), List.of(),
                new TokenUsage(0, 0, CacheUsage.NONE, 0, 0));
        LlmClient stub = stubReturning(blank);

        List<String> chunks = new ArrayList<>();
        StreamingLlmClient.fallback(stub, simpleRequest(), chunks::add);
        assertEquals(List.of(), chunks);

        LlmResponse nullText = new LlmResponse(
                null, "test-model", LlmStopReason.END_TURN,
                null, List.of(), List.of(),
                new TokenUsage(0, 0, CacheUsage.NONE, 0, 0));
        chunks.clear();
        StreamingLlmClient.fallback(stubReturning(nullText), simpleRequest(), chunks::add);
        assertEquals(List.of(), chunks);
    }

    @Test
    @DisplayName("StreamingLlmClient extends LlmClient — non-streaming code can still use send()")
    void interfaceExtendsLlmClient() {
        StreamingLlmClient streaming = new StreamingLlmClient() {
            @Override public String name() { return "stream"; }
            @Override public Set<Feature> supportedFeatures() { return Set.of(Feature.STREAMING); }
            @Override public LlmResponse send(LlmRequest req) {
                return new LlmResponse(null, "m", LlmStopReason.END_TURN, "via send",
                        List.of(), List.of(),
                        new TokenUsage(0, 0, CacheUsage.NONE, 0, 0));
            }
            @Override public LlmResponse sendStreaming(LlmRequest req, Consumer<String> delta) {
                delta.accept("via stream");
                return send(req);
            }
        };
        assertInstanceOf(LlmClient.class, streaming);
        assertEquals("via send", streaming.send(simpleRequest()).text());

        List<String> sink = new ArrayList<>();
        assertEquals("via send", streaming.sendStreaming(simpleRequest(), sink::add).text());
        assertEquals(List.of("via stream"), sink);
    }

    private LlmClient stubReturning(LlmResponse r) {
        return new LlmClient() {
            @Override public String name() { return "stub"; }
            @Override public Set<Feature> supportedFeatures() { return Set.of(); }
            @Override public LlmResponse send(LlmRequest req) { return r; }
        };
    }

    private LlmRequest simpleRequest() {
        return LlmRequest.builder().model("m").messages(List.of(LlmMessage.user("hi"))).build();
    }
}
