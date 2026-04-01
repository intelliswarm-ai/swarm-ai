package ai.intelliswarm.swarmai.agent;

import ai.intelliswarm.swarmai.base.BaseSwarmTest;
import ai.intelliswarm.swarmai.base.MockChatClientFactory;
import ai.intelliswarm.swarmai.base.TestFixtures;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Reactive Agent Loop Tests")
class ReactiveAgentLoopTest extends BaseSwarmTest {

    @Nested
    @DisplayName("Single-shot backward compatibility")
    class SingleShotTests {

        @Test
        @DisplayName("defaults to single-shot when maxTurns is not set")
        void executeTask_withoutMaxTurns_usesSingleShot() {
            Agent agent = createAgent();
            Task task = createTask(agent);

            TaskOutput output = agent.executeTask(task, Collections.emptyList());

            assertNotNull(output);
            assertTrue(output.isSuccessful());
            // No "turns" metadata when running single-shot
            assertNull(output.getMetadata().get("turns"));
        }

        @Test
        @DisplayName("single-shot when maxTurns is 1")
        void executeTask_withMaxTurnsOne_usesSingleShot() {
            Agent agent = Agent.builder()
                    .role("Test Agent")
                    .goal("Test goal")
                    .backstory("Test backstory")
                    .chatClient(mockChatClient)
                    .maxTurns(1)
                    .build();

            Task task = createTask(agent);
            TaskOutput output = agent.executeTask(task, Collections.emptyList());

            assertNotNull(output);
            assertTrue(output.isSuccessful());
            assertNull(output.getMetadata().get("turns"));
        }
    }

    @Nested
    @DisplayName("Reactive multi-turn execution")
    class ReactiveTests {

        @Test
        @DisplayName("completes in one turn when no CONTINUE marker")
        void reactive_noContinueMarker_completesInOneTurn() {
            ChatClient client = MockChatClientFactory.withResponse("Final answer here <DONE>");
            Agent agent = Agent.builder()
                    .role("Reactive Agent")
                    .goal("Test multi-turn")
                    .backstory("Test backstory")
                    .chatClient(client)
                    .maxTurns(5)
                    .build();

            Task task = createTask(agent);
            TaskOutput output = agent.executeTask(task, Collections.emptyList());

            assertNotNull(output);
            assertTrue(output.isSuccessful());
            assertEquals(1, output.getMetadata().get("turns"));
            assertFalse(output.getRawOutput().contains("<DONE>"));
        }

        @Test
        @DisplayName("continues for multiple turns when CONTINUE marker present")
        void reactive_withContinueMarker_runsMultipleTurns() {
            ChatClient client = MockChatClientFactory.withResponses(
                    "Analyzing data... <CONTINUE>",
                    "Found patterns, running more tools... <CONTINUE>",
                    "Final comprehensive answer <DONE>"
            );
            Agent agent = Agent.builder()
                    .role("Reactive Agent")
                    .goal("Multi-turn analysis")
                    .backstory("Test backstory")
                    .chatClient(client)
                    .maxTurns(5)
                    .build();

            Task task = createTask(agent);
            TaskOutput output = agent.executeTask(task, Collections.emptyList());

            assertNotNull(output);
            assertTrue(output.isSuccessful());
            assertEquals(3, output.getMetadata().get("turns"));
            assertEquals("Final comprehensive answer", output.getRawOutput());
        }

        @Test
        @DisplayName("stops at maxTurns even if CONTINUE is present")
        void reactive_hitsMaxTurns_stops() {
            ChatClient client = MockChatClientFactory.withResponses(
                    "Turn 1 <CONTINUE>",
                    "Turn 2 <CONTINUE>",
                    "Turn 3 <CONTINUE>"  // would continue but maxTurns = 3
            );
            Agent agent = Agent.builder()
                    .role("Reactive Agent")
                    .goal("Bounded execution")
                    .backstory("Test backstory")
                    .chatClient(client)
                    .maxTurns(3)
                    .build();

            Task task = createTask(agent);
            TaskOutput output = agent.executeTask(task, Collections.emptyList());

            assertNotNull(output);
            assertEquals(3, output.getMetadata().get("turns"));
        }

        @Test
        @DisplayName("accumulates token usage across turns")
        void reactive_accumulatesTokens() {
            ChatClient client = MockChatClientFactory.withResponses(
                    "Turn 1 <CONTINUE>",
                    "Turn 2 done"
            );
            Agent agent = Agent.builder()
                    .role("Reactive Agent")
                    .goal("Token tracking")
                    .backstory("Test backstory")
                    .chatClient(client)
                    .maxTurns(5)
                    .build();

            Task task = createTask(agent);
            TaskOutput output = agent.executeTask(task, Collections.emptyList());

            // MockChatClientFactory returns 100 prompt + 50 completion per call
            // 2 turns = 200 prompt, 100 completion
            assertNotNull(output.getPromptTokens());
            assertNotNull(output.getCompletionTokens());
            assertEquals(200L, output.getPromptTokens());
            assertEquals(100L, output.getCompletionTokens());
            assertEquals(300L, output.getTotalTokens());
        }

        @Test
        @DisplayName("executeTaskReactive can be called directly")
        void executeTaskReactive_directCall_works() {
            ChatClient client = MockChatClientFactory.withResponse("Direct reactive result");
            Agent agent = Agent.builder()
                    .role("Reactive Agent")
                    .goal("Direct call")
                    .backstory("Test backstory")
                    .chatClient(client)
                    .maxTurns(3)
                    .build();

            Task task = createTask(agent);
            TaskOutput output = agent.executeTaskReactive(task, Collections.emptyList());

            assertNotNull(output);
            assertTrue(output.isSuccessful());
            assertEquals(1, output.getMetadata().get("turns"));
        }

        @Test
        @DisplayName("strips markers from final output")
        void reactive_stripsMarkers_fromFinalOutput() {
            ChatClient client = MockChatClientFactory.withResponse("Clean answer <DONE>");
            Agent agent = Agent.builder()
                    .role("Reactive Agent")
                    .goal("Clean output")
                    .backstory("Test backstory")
                    .chatClient(client)
                    .maxTurns(3)
                    .build();

            Task task = createTask(agent);
            TaskOutput output = agent.executeTask(task, Collections.emptyList());

            assertEquals("Clean answer", output.getRawOutput());
            assertFalse(output.getRawOutput().contains("<DONE>"));
            assertFalse(output.getRawOutput().contains("<CONTINUE>"));
        }
    }

    @Nested
    @DisplayName("AgentConversation")
    class ConversationTests {

        @Test
        @DisplayName("tracks turns correctly")
        void conversation_tracksTurns() {
            AgentConversation conv = new AgentConversation();
            conv.addTurn(new ConversationTurn(0, "First response <CONTINUE>", 100, 50, System.currentTimeMillis()));
            conv.addTurn(new ConversationTurn(1, "Second response <DONE>", 120, 60, System.currentTimeMillis()));

            assertEquals(2, conv.getTurnCount());
            assertEquals("Second response", conv.getFinalResponse());
            assertTrue(conv.getCumulativePromptTokens() == 220);
            assertTrue(conv.getCumulativeCompletionTokens() == 110);
        }

        @Test
        @DisplayName("detects continuation signal")
        void conversation_detectsContinuation() {
            AgentConversation conv = new AgentConversation();
            conv.addTurn(new ConversationTurn(0, "Working... <CONTINUE>", 100, 50, System.currentTimeMillis()));
            assertTrue(conv.lastTurnRequestsContinuation());

            conv.addTurn(new ConversationTurn(1, "Done <DONE>", 100, 50, System.currentTimeMillis()));
            assertFalse(conv.lastTurnRequestsContinuation());
        }

        @Test
        @DisplayName("builds context string from turns")
        void conversation_buildsContextString() {
            AgentConversation conv = new AgentConversation();
            conv.addTurn(new ConversationTurn(0, "First analysis <CONTINUE>", 100, 50, System.currentTimeMillis()));
            conv.addTurn(new ConversationTurn(1, "Second analysis", 100, 50, System.currentTimeMillis()));

            String ctx = conv.toContextString();
            assertTrue(ctx.contains("Reasoning turn 1"));
            assertTrue(ctx.contains("First analysis"));
            assertTrue(ctx.contains("Reasoning turn 2"));
            assertTrue(ctx.contains("Second analysis"));
            assertFalse(ctx.contains("<CONTINUE>"));
        }

        @Test
        @DisplayName("empty conversation returns null final response")
        void conversation_empty_returnsNull() {
            AgentConversation conv = new AgentConversation();
            assertNull(conv.getFinalResponse());
            assertEquals(0, conv.getTurnCount());
            assertFalse(conv.lastTurnRequestsContinuation());
        }
    }
}
