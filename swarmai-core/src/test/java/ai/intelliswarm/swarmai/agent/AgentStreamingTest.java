package ai.intelliswarm.swarmai.agent;

import ai.intelliswarm.swarmai.agent.streaming.AgentEvent;
import ai.intelliswarm.swarmai.base.MockChatClientFactory;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 streaming tests — verify {@link Agent#executeTaskStreaming(Task, java.util.List)}
 * emits the bracketed event sequence (Started → Deltas → Finished) and converts
 * upstream errors into terminal {@link AgentEvent.AgentError} events on the
 * success channel.
 *
 * <p>No real LLM is involved — Spring AI's {@code ChatClient.stream()} is mocked
 * via {@link MockChatClientFactory#streamingClientWithDeltas} so the test runs
 * deterministically in milliseconds.
 */
class AgentStreamingTest {

    private Agent build(ChatClient client) {
        return Agent.builder()
                .role("Test Streaming Agent")
                .goal("Stream a short prose response")
                .backstory("A deterministic test agent")
                .chatClient(client)
                .verbose(false)
                .temperature(0.0)
                .build();
    }

    private Task task(Agent agent) {
        return Task.builder()
                .description("Write a sentence")
                .expectedOutput("A sentence")
                .agent(agent)
                .build();
    }

    @Test
    @DisplayName("happy path: emits AgentStarted → 3× TextDelta → AgentFinished and completes")
    void emitsBracketedSequence() {
        ChatClient client = MockChatClientFactory.streamingClientWithDeltas("Hello, ", "streaming ", "world!");
        Agent agent = build(client);

        StepVerifier.create(agent.executeTaskStreaming(task(agent), Collections.emptyList()))
                .expectNextMatches(e -> e instanceof AgentEvent.AgentStarted s
                        && "Test Streaming Agent".equals(s.role()))
                .expectNextMatches(e -> e instanceof AgentEvent.TextDelta d
                        && "Hello, ".equals(d.text()))
                .expectNextMatches(e -> e instanceof AgentEvent.TextDelta d
                        && "streaming ".equals(d.text()))
                .expectNextMatches(e -> e instanceof AgentEvent.TextDelta d
                        && "world!".equals(d.text()))
                .expectNextMatches(e -> {
                    if (!(e instanceof AgentEvent.AgentFinished f)) return false;
                    TaskOutput out = f.taskOutput();
                    // Accumulated text from the deltas equals the joined string.
                    return out != null && "Hello, streaming world!".equals(out.getRawOutput());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("empty deltas are filtered: empty-string and null produce no TextDelta events")
    void filtersEmptyDeltas() {
        ChatClient client = MockChatClientFactory.streamingClientWithDeltas("", "actual ", "", "text");
        Agent agent = build(client);

        StepVerifier.create(agent.executeTaskStreaming(task(agent), Collections.emptyList()))
                .expectNextMatches(e -> e instanceof AgentEvent.AgentStarted)
                .expectNextMatches(e -> e instanceof AgentEvent.TextDelta d && "actual ".equals(d.text()))
                .expectNextMatches(e -> e instanceof AgentEvent.TextDelta d && "text".equals(d.text()))
                .expectNextMatches(e -> e instanceof AgentEvent.AgentFinished f
                        && "actual text".equals(f.taskOutput().getRawOutput()))
                .verifyComplete();
    }

    @Test
    @DisplayName("upstream error is converted to a terminal AgentError event — Flux completes normally")
    void errorBecomesTerminalEventNotOnError() {
        ChatClient client = MockChatClientFactory.streamingClientFailingAfter(
                new RuntimeException("LLM exploded"),
                "Partial ", "output ");
        Agent agent = build(client);

        StepVerifier.create(agent.executeTaskStreaming(task(agent), Collections.emptyList()))
                .expectNextMatches(e -> e instanceof AgentEvent.AgentStarted)
                .expectNextMatches(e -> e instanceof AgentEvent.TextDelta d && "Partial ".equals(d.text()))
                .expectNextMatches(e -> e instanceof AgentEvent.TextDelta d && "output ".equals(d.text()))
                .expectNextMatches(e -> {
                    if (!(e instanceof AgentEvent.AgentError err)) return false;
                    return "RuntimeException".equals(err.exceptionType())
                            && "LLM exploded".equals(err.message());
                })
                // Critical: NOT verifyError(...) — SSE consumers don't see onError.
                .verifyComplete();
    }

    @Test
    @DisplayName("AgentStarted event carries non-null agentId / role / taskDescription / monotonic timestamp")
    void agentStartedEventCarriesIdentity() {
        ChatClient client = MockChatClientFactory.streamingClientWithDeltas("hi");
        Agent agent = build(client);
        long before = System.currentTimeMillis();

        AgentEvent first = agent.executeTaskStreaming(task(agent), Collections.emptyList())
                .blockFirst(Duration.ofSeconds(5));

        assertThat(first).isInstanceOf(AgentEvent.AgentStarted.class);
        AgentEvent.AgentStarted started = (AgentEvent.AgentStarted) first;
        assertThat(started.agentId()).isNotBlank();
        assertThat(started.role()).isEqualTo("Test Streaming Agent");
        assertThat(started.taskDescription()).isEqualTo("Write a sentence");
        assertThat(started.timestamp()).isGreaterThanOrEqualTo(before);
    }
}
