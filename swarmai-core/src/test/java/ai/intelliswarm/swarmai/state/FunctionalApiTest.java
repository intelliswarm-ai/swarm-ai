package ai.intelliswarm.swarmai.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Functional Graph API Tests")
class FunctionalApiTest {

    @Nested
    @DisplayName("NodeAction")
    class NodeActionTests {

        @Test
        @DisplayName("can be implemented as lambda returning partial state")
        void lambdaReturnsPartialState() throws Exception {
            NodeAction<AgentState> action = state -> {
                String topic = state.valueOrDefault("topic", "default");
                return Map.of("result", "researched: " + topic);
            };

            AgentState state = AgentState.of(Map.of("topic", "AI"));
            Map<String, Object> result = action.apply(state);

            assertEquals("researched: AI", result.get("result"));
        }

        @Test
        @DisplayName("receives current state and can read values")
        void readsStateValues() throws Exception {
            NodeAction<AgentState> counter = state -> {
                long count = state.valueOrDefault("count", 0L);
                return Map.of("count", count + 1);
            };

            Map<String, Object> result = counter.apply(AgentState.of(Map.of("count", 5L)));
            assertEquals(6L, result.get("count"));
        }
    }

    @Nested
    @DisplayName("EdgeAction")
    class EdgeActionTests {

        @Test
        @DisplayName("routes based on state")
        void routesBasedOnState() throws Exception {
            EdgeAction<AgentState> router = state -> {
                boolean done = state.valueOrDefault("quality_ok", false);
                return done ? SwarmGraph.END : "research";
            };

            assertEquals("research", router.apply(AgentState.of(Map.of("quality_ok", false))));
            assertEquals(SwarmGraph.END, router.apply(AgentState.of(Map.of("quality_ok", true))));
        }
    }

    @Nested
    @DisplayName("SwarmGraph functional API")
    class SwarmGraphFunctionalTests {

        @Test
        @DisplayName("registers nodes via addNode")
        void registersNodes() {
            SwarmGraph graph = SwarmGraph.create()
                    .addNode("step1", state -> Map.of("done", true))
                    .addNode("step2", state -> Map.of("result", "complete"));

            assertTrue(graph.hasFunctionalNodes());
            assertEquals(2, graph.getNodeActions().size());
            assertTrue(graph.getNodeActions().containsKey("step1"));
            assertTrue(graph.getNodeActions().containsKey("step2"));
        }

        @Test
        @DisplayName("registers edges via addEdge")
        void registersEdges() {
            SwarmGraph graph = SwarmGraph.create()
                    .addNode("a", state -> Map.of())
                    .addNode("b", state -> Map.of())
                    .addEdge(SwarmGraph.START, "a")
                    .addEdge("a", "b")
                    .addEdge("b", SwarmGraph.END);

            assertEquals(3, graph.getEdges().size());
        }

        @Test
        @DisplayName("registers conditional edges")
        void registersConditionalEdges() {
            SwarmGraph graph = SwarmGraph.create()
                    .addNode("check", state -> Map.of())
                    .addConditionalEdge("check", state -> SwarmGraph.END);

            assertEquals(1, graph.getConditionalEdges().size());
            assertTrue(graph.getConditionalEdges().containsKey("check"));
        }

        @Test
        @DisplayName("rejects reserved node IDs")
        void rejectsReservedIds() {
            assertThrows(IllegalArgumentException.class, () ->
                    SwarmGraph.create().addNode("__reserved", state -> Map.of()));
        }

        @Test
        @DisplayName("rejects null node ID")
        void rejectsNullNodeId() {
            assertThrows(NullPointerException.class, () ->
                    SwarmGraph.create().addNode(null, state -> Map.of()));
        }

        @Test
        @DisplayName("rejects null action")
        void rejectsNullAction() {
            assertThrows(NullPointerException.class, () ->
                    SwarmGraph.create().addNode("test", null));
        }

        @Test
        @DisplayName("START and END constants are defined")
        void constantsDefined() {
            assertEquals("__START__", SwarmGraph.START);
            assertEquals("__END__", SwarmGraph.END);
        }

        @Test
        @DisplayName("functional and traditional APIs can coexist")
        void coexistsWithTraditionalApi() {
            var agent = ai.intelliswarm.swarmai.agent.Agent.builder()
                    .role("worker").goal("test").backstory("test")
                    .chatClient(org.mockito.Mockito.mock(
                            org.springframework.ai.chat.client.ChatClient.class))
                    .build();

            SwarmGraph graph = SwarmGraph.create()
                    .addAgent(agent)
                    .addTask(ai.intelliswarm.swarmai.task.Task.builder()
                            .description("traditional task")
                            .agent(agent).build())
                    .addNode("functional-step", state -> Map.of("extra", true));

            assertTrue(graph.hasFunctionalNodes());
            assertEquals(1, graph.agents().size());
            assertEquals(1, graph.tasks().size());
        }

        @Test
        @DisplayName("getNodeActions returns unmodifiable map")
        void unmodifiableNodeActions() {
            SwarmGraph graph = SwarmGraph.create()
                    .addNode("a", state -> Map.of());

            assertThrows(UnsupportedOperationException.class, () ->
                    graph.getNodeActions().put("b", state -> Map.of()));
        }
    }
}
