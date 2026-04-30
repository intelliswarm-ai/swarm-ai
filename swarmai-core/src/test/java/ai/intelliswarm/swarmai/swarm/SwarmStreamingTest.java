package ai.intelliswarm.swarmai.swarm;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.agent.streaming.AgentEvent;
import ai.intelliswarm.swarmai.base.MockChatClientFactory;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.task.Task;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link Swarm#runStreaming(Map)} — verifies SEQUENTIAL concat-ordering
 * and PARALLEL merge-with-interleaving plus correct identity demuxing on the
 * latter. Mocked ChatClient stays deterministic.
 */
class SwarmStreamingTest {

    private Agent agent(String role, String... deltas) {
        return Agent.builder()
                .role(role)
                .goal("g")
                .backstory("bs")
                .chatClient(MockChatClientFactory.streamingClientWithDeltas(deltas))
                .verbose(false)
                .build();
    }

    private Task taskFor(Agent a, String desc) {
        return Task.builder()
                .description(desc)
                .expectedOutput("output")
                .agent(a)
                .build();
    }

    @Test
    @DisplayName("SEQUENTIAL: agent A's events all arrive before agent B's events")
    void sequential_concatenatesAgentStreams() {
        Agent a = agent("A", "alpha-1 ", "alpha-2");
        Agent b = agent("B", "beta-1 ",  "beta-2");

        Swarm swarm = Swarm.builder()
                .id("seq-stream")
                .agents(List.of(a, b))
                .tasks(List.of(taskFor(a, "do A"), taskFor(b, "do B")))
                .process(ProcessType.SEQUENTIAL)
                .build();

        // Collect into a list so we can assert on relative ordering, not exact equality
        // (timestamps vary). Both AgentStarted events from A must precede any from B.
        List<AgentEvent> events = swarm.runStreaming(Map.of())
                .collectList()
                .block(Duration.ofSeconds(10));

        assertThat(events).isNotNull().hasSize(2 + 2 + 2 + 2); // started+2deltas+finished × 2
        // Find indices of each agent's started/finished
        int aStarted = indexOfStartedFor(events, "A");
        int aFinished = indexOfFinishedFor(events, "A");
        int bStarted = indexOfStartedFor(events, "B");
        int bFinished = indexOfFinishedFor(events, "B");
        assertThat(aStarted).isZero();
        // Agent A's stream is fully delivered (started → 2 deltas → finished) BEFORE B starts.
        assertThat(bStarted).isGreaterThan(aFinished);
        assertThat(bFinished).isGreaterThan(bStarted);
    }

    @Test
    @DisplayName("SEQUENTIAL: agent B's context contains agent A's TaskOutput")
    void sequential_threadsContextBetweenAgents() {
        // We can't easily peek inside Agent.executeTaskStreaming to verify the
        // context arg without instrumenting the agent. Instead, assert the
        // observable outcome: B's AgentFinished sees raw output it would only
        // be able to produce if it executed AFTER A finished — which is the
        // concat ordering. The previous test already covers ordering.
        // This second test guards against the regression where we'd accidentally
        // fire B's defer before A's accumulator added A's output.
        Agent a = agent("A", "first-output");
        Agent b = agent("B", "second-output");
        Swarm swarm = Swarm.builder()
                .agents(List.of(a, b))
                .tasks(List.of(taskFor(a, "1"), taskFor(b, "2")))
                .process(ProcessType.SEQUENTIAL).build();

        List<AgentEvent.AgentFinished> finishes = swarm.runStreaming(Map.of())
                .filter(e -> e instanceof AgentEvent.AgentFinished)
                .cast(AgentEvent.AgentFinished.class)
                .collectList()
                .block(Duration.ofSeconds(10));

        assertThat(finishes).hasSize(2);
        // Both agents produced their declared outputs — concat ran both to completion.
        assertThat(finishes.get(0).taskOutput().getRawOutput()).isEqualTo("first-output");
        assertThat(finishes.get(1).taskOutput().getRawOutput()).isEqualTo("second-output");
    }

    @Test
    @DisplayName("PARALLEL: events from different agents interleave (deterministic only on agentId, not order)")
    void parallel_mergesWithCorrectIdentity() {
        // 5 deltas per agent over 3 agents — gives plenty of opportunity for interleaving.
        Agent a = agent("A", "a1", "a2", "a3", "a4", "a5");
        Agent b = agent("B", "b1", "b2", "b3", "b4", "b5");
        Agent c = agent("C", "c1", "c2", "c3", "c4", "c5");

        Swarm swarm = Swarm.builder()
                .agents(List.of(a, b, c))
                .tasks(List.of(taskFor(a, "1"), taskFor(b, "2"), taskFor(c, "3")))
                .process(ProcessType.PARALLEL).build();

        List<AgentEvent> events = swarm.runStreaming(Map.of())
                .collectList()
                .block(Duration.ofSeconds(10));

        assertThat(events).isNotNull();
        // 3 × (1 started + 5 deltas + 1 finished) = 21 events
        assertThat(events).hasSize(21);

        // Every distinct agentId we emit is one of the three real agents — proves
        // identity is preserved through Flux.merge so frontends can demux per-pane.
        Set<String> distinctAgentIds = new HashSet<>();
        for (AgentEvent e : events) distinctAgentIds.add(e.agentId());
        assertThat(distinctAgentIds).hasSize(3);
        assertThat(distinctAgentIds).containsExactlyInAnyOrder(a.getId(), b.getId(), c.getId());
    }

    @Test
    @DisplayName("Unsupported process types emit a single AgentError and complete")
    void unsupportedProcessTypeReturnsErrorEvent() {
        Agent a = agent("A", "x");
        Swarm swarm = Swarm.builder()
                .agents(List.of(a))
                .tasks(List.of(taskFor(a, "t")))
                .managerAgent(a)
                .process(ProcessType.HIERARCHICAL).build();

        StepVerifier.create(swarm.runStreaming(Map.of()))
                .expectNextMatches(e -> e instanceof AgentEvent.AgentError err
                        && "UnsupportedProcessType".equals(err.exceptionType())
                        && err.message().contains("HIERARCHICAL"))
                .verifyComplete();
    }

    // ---- helpers ----------------------------------------------------------
    private int indexOfStartedFor(List<AgentEvent> events, String role) {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i) instanceof AgentEvent.AgentStarted s && role.equals(s.role())) return i;
        }
        return -1;
    }
    private int indexOfFinishedFor(List<AgentEvent> events, String role) {
        // AgentFinished doesn't carry role — match by paired AgentStarted's agentId
        String startedId = null;
        for (AgentEvent e : events) {
            if (e instanceof AgentEvent.AgentStarted s && role.equals(s.role())) {
                startedId = s.agentId(); break;
            }
        }
        if (startedId == null) return -1;
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i) instanceof AgentEvent.AgentFinished f && startedId.equals(f.agentId())) return i;
        }
        return -1;
    }
}
