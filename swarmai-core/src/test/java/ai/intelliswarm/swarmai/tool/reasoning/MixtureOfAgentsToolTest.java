package ai.intelliswarm.swarmai.tool.reasoning;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MixtureOfAgentsToolTest {

    @Test
    void fansOutToEveryReferenceAndAggregates() {
        AtomicInteger calls = new AtomicInteger();
        MixtureOfAgentsTool tool = new MixtureOfAgentsTool(
                List.of(
                        new MixtureOfAgentsTool.Reference("claude-opus",
                                prompt -> { calls.incrementAndGet(); return "Claude: " + prompt; }),
                        new MixtureOfAgentsTool.Reference("gpt-4",
                                prompt -> { calls.incrementAndGet(); return "GPT: " + prompt; }),
                        new MixtureOfAgentsTool.Reference("gemini",
                                prompt -> { calls.incrementAndGet(); return "Gemini: " + prompt; })),
                input -> "SYNTHESIS of " + input.referenceResponses().size() + " responses",
                Executors.newFixedThreadPool(3));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.execute(Map.of("prompt", "test"));
        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("synthesis")).isEqualTo("SYNTHESIS of 3 responses");
        assertThat(result.get("referenceCount")).isEqualTo(3);
        assertThat(calls.get()).isEqualTo(3);

        @SuppressWarnings("unchecked")
        List<Map<String, String>> refs = (List<Map<String, String>>) result.get("references");
        assertThat(refs).hasSize(3);
        List<String> labels = refs.stream().map(r -> r.get("label")).collect(Collectors.toList());
        assertThat(labels).containsExactlyInAnyOrder("claude-opus", "gpt-4", "gemini");
    }

    @Test
    void aggregatorReceivesAllResponses() {
        var captured = new java.util.concurrent.atomic.AtomicReference<MixtureOfAgentsTool.AggregatorInput>();
        MixtureOfAgentsTool tool = new MixtureOfAgentsTool(
                List.of(
                        new MixtureOfAgentsTool.Reference("a", p -> "answer-a"),
                        new MixtureOfAgentsTool.Reference("b", p -> "answer-b")),
                input -> { captured.set(input); return "synthesis"; });

        tool.execute(Map.of("prompt", "p"));
        assertThat(captured.get().prompt()).isEqualTo("p");
        assertThat(captured.get().referenceResponses()).hasSize(2);
        assertThat(captured.get().referenceResponses().stream().map(Map.Entry::getValue))
                .containsExactlyInAnyOrder("answer-a", "answer-b");
    }

    @Test
    void referenceErrorsAreCapturedNotPropagated() {
        MixtureOfAgentsTool tool = new MixtureOfAgentsTool(
                List.of(
                        new MixtureOfAgentsTool.Reference("good", p -> "ok"),
                        new MixtureOfAgentsTool.Reference("bad",
                                p -> { throw new RuntimeException("boom"); })),
                input -> {
                    long failed = input.referenceResponses().stream()
                            .filter(e -> e.getValue().contains("[ERROR]")).count();
                    return failed + " references failed";
                });

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.execute(Map.of("prompt", "p"));
        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("synthesis")).isEqualTo("1 references failed");
    }

    @Test
    void aggregatorErrorBubblesAsErrorResponse() {
        MixtureOfAgentsTool tool = new MixtureOfAgentsTool(
                List.of(new MixtureOfAgentsTool.Reference("a", p -> "ok")),
                input -> { throw new RuntimeException("aggregator down"); });
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.execute(Map.of("prompt", "p"));
        assertThat(result.get("ok")).isEqualTo(false);
        assertThat(result.get("error").toString()).contains("aggregator down");
    }

    @Test
    void emptyReferencesRejected() {
        assertThatThrownBy(() -> new MixtureOfAgentsTool(
                List.of(), input -> "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingPromptReturnsError() {
        MixtureOfAgentsTool tool = new MixtureOfAgentsTool(
                List.of(new MixtureOfAgentsTool.Reference("a", p -> "ok")),
                input -> "synth");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.execute(Map.of());
        assertThat(result.get("ok")).isEqualTo(false);
    }
}
