package ai.intelliswarm.swarmai.agent;

import ai.intelliswarm.swarmai.base.BaseSwarmTest;
import ai.intelliswarm.swarmai.base.MockChatClientFactory;
import ai.intelliswarm.swarmai.config.ModelContextConfig;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Conversation Compaction Tests")
class ConversationCompactionTest extends BaseSwarmTest {

    @Nested
    @DisplayName("CompactionConfig")
    class CompactionConfigTests {

        @Test
        @DisplayName("forModel creates config at 80% of context window")
        void forModel_createsConfigAt80Percent() {
            ModelContextConfig modelConfig = ModelContextConfig.withTokens(200_000);
            CompactionConfig config = CompactionConfig.forModel(modelConfig);

            assertEquals(160_000L, config.compactionThresholdTokens());
            assertEquals(4, config.preserveRecentTurns());
            assertTrue(config.enabled());
        }

        @Test
        @DisplayName("disabled creates config that never triggers")
        void disabled_neverTriggers() {
            CompactionConfig config = CompactionConfig.disabled();

            assertFalse(config.enabled());
            assertEquals(Long.MAX_VALUE, config.compactionThresholdTokens());
        }

        @Test
        @DisplayName("withThreshold creates custom threshold")
        void withThreshold_customThreshold() {
            CompactionConfig config = CompactionConfig.withThreshold(50_000);

            assertEquals(50_000L, config.compactionThresholdTokens());
            assertTrue(config.enabled());
        }

        @Test
        @DisplayName("of creates fully custom config")
        void of_fullyCustom() {
            CompactionConfig config = CompactionConfig.of(2, 10_000);

            assertEquals(2, config.preserveRecentTurns());
            assertEquals(10_000L, config.compactionThresholdTokens());
            assertTrue(config.enabled());
        }
    }

    @Nested
    @DisplayName("ConversationCompactor.shouldCompact()")
    class ShouldCompactTests {

        @Test
        @DisplayName("returns false when disabled")
        void disabled_returnsFalse() {
            AgentConversation conv = conversationWithTurns(10, 5000);
            assertFalse(ConversationCompactor.shouldCompact(conv, CompactionConfig.disabled()));
        }

        @Test
        @DisplayName("returns false when below threshold")
        void belowThreshold_returnsFalse() {
            AgentConversation conv = conversationWithTurns(6, 100);
            CompactionConfig config = CompactionConfig.withThreshold(10_000);
            assertFalse(ConversationCompactor.shouldCompact(conv, config));
        }

        @Test
        @DisplayName("returns false when not enough turns to compact")
        void tooFewTurns_returnsFalse() {
            AgentConversation conv = conversationWithTurns(3, 5000);
            CompactionConfig config = CompactionConfig.of(4, 100);
            assertFalse(ConversationCompactor.shouldCompact(conv, config));
        }

        @Test
        @DisplayName("returns true when threshold exceeded and enough turns")
        void thresholdExceeded_returnsTrue() {
            AgentConversation conv = conversationWithTurns(8, 2000);
            // 8 turns * (2000 + 1000) tokens = 24000 total
            CompactionConfig config = CompactionConfig.of(4, 10_000);
            assertTrue(ConversationCompactor.shouldCompact(conv, config));
        }
    }

    @Nested
    @DisplayName("ConversationCompactor.compact()")
    class CompactTests {

        @Test
        @DisplayName("compacts older turns and preserves recent ones")
        void compact_preservesRecentTurns() {
            AgentConversation conv = conversationWithTurns(8, 2000);
            CompactionConfig config = CompactionConfig.of(3, 100);

            CompactionResult result = ConversationCompactor.compact(conv, config);

            assertTrue(result.wasCompacted());
            assertEquals(5, result.removedTurnCount());  // 8 - 3 = 5 removed
            assertEquals(3, result.preservedTurns().size());
        }

        @Test
        @DisplayName("returns none when compaction not needed")
        void noCompaction_returnsNone() {
            AgentConversation conv = conversationWithTurns(3, 100);
            CompactionConfig config = CompactionConfig.of(4, 100_000);

            CompactionResult result = ConversationCompactor.compact(conv, config);

            assertFalse(result.wasCompacted());
            assertEquals(0, result.removedTurnCount());
        }

        @Test
        @DisplayName("summary contains structured tags")
        void compact_summaryHasStructuredTags() {
            AgentConversation conv = conversationWithTurns(6, 2000);
            CompactionConfig config = CompactionConfig.of(2, 100);

            CompactionResult result = ConversationCompactor.compact(conv, config);

            assertTrue(result.summary().startsWith("<summary>"));
            assertTrue(result.summary().endsWith("</summary>"));
            assertTrue(result.summary().contains("earlier reasoning turns summarized"));
            assertTrue(result.summary().contains("Key actions:"));
        }

        @Test
        @DisplayName("tracks compacted token counts")
        void compact_tracksTokens() {
            AgentConversation conv = conversationWithTurns(6, 500);
            CompactionConfig config = CompactionConfig.of(2, 100);

            CompactionResult result = ConversationCompactor.compact(conv, config);

            // 4 turns compacted, each with 500 prompt + 250 completion
            assertEquals(4, result.removedTurnCount());
            assertEquals(2000L, result.compactedPromptTokens());   // 4 * 500
            assertEquals(1000L, result.compactedCompletionTokens()); // 4 * 250
        }
    }

    @Nested
    @DisplayName("ConversationCompactor.summarizeTurns()")
    class SummarizeTurnsTests {

        @Test
        @DisplayName("extracts tool names from responses")
        void extractsToolNames() {
            AgentConversation conv = new AgentConversation();
            conv.addTurn(new ConversationTurn(0, "I called web_search for data <CONTINUE>", 100, 50, now()));
            conv.addTurn(new ConversationTurn(1, "Executed calculator to compute values", 100, 50, now()));

            Set<String> tools = ConversationCompactor.extractToolNames(conv.getTurns());
            assertTrue(tools.contains("web_search"), "Should find 'web_search' via 'called' pattern");
            assertTrue(tools.contains("calculator"), "Should find 'calculator' via 'executed' pattern");
        }

        @Test
        @DisplayName("extracts file paths from responses")
        void extractsFilePaths() {
            AgentConversation conv = new AgentConversation();
            conv.addTurn(new ConversationTurn(0, "Reading /src/main/App.java and /test/AppTest.java", 100, 50, now()));

            Set<String> paths = ConversationCompactor.extractFilePaths(conv.getTurns());
            assertTrue(paths.contains("/src/main/App.java"));
            assertTrue(paths.contains("/test/AppTest.java"));
        }

        @Test
        @DisplayName("marks continued turns in timeline")
        void marksContinuedTurns() {
            List<ConversationTurn> turns = List.of(
                    new ConversationTurn(0, "Analyzing data <CONTINUE>", 100, 50, now()),
                    new ConversationTurn(1, "More analysis <CONTINUE>", 100, 50, now())
            );
            String summary = ConversationCompactor.summarizeTurns(turns);
            // Each continued turn should have [continued] marker
            assertTrue(summary.contains("[continued]"));
        }
    }

    @Nested
    @DisplayName("AgentConversation compaction state")
    class AgentConversationCompactionTests {

        @Test
        @DisplayName("applyCompaction updates state correctly")
        void applyCompaction_updatesState() {
            AgentConversation conv = conversationWithTurns(8, 500);
            CompactionConfig config = CompactionConfig.of(3, 100);

            CompactionResult result = ConversationCompactor.compact(conv, config);
            conv.applyCompaction(result);

            assertTrue(conv.hasBeenCompacted());
            assertEquals(5, conv.getCompactedTurnCount());
            assertEquals(3, conv.getActiveTurnCount());
            assertEquals(8, conv.getTurnCount()); // total = compacted + active
        }

        @Test
        @DisplayName("cumulative tokens include compacted turns")
        void cumulativeTokens_includeCompacted() {
            AgentConversation conv = conversationWithTurns(6, 500);

            long tokensBefore = conv.getCumulativeTotalTokens();

            CompactionConfig config = CompactionConfig.of(2, 100);
            CompactionResult result = ConversationCompactor.compact(conv, config);
            conv.applyCompaction(result);

            // Total tokens should be the same (compacted tokens tracked separately)
            assertEquals(tokensBefore, conv.getCumulativeTotalTokens());
        }

        @Test
        @DisplayName("toContextString includes compacted summary")
        void toContextString_includesCompactedSummary() {
            AgentConversation conv = conversationWithTurns(6, 500);
            CompactionConfig config = CompactionConfig.of(2, 100);

            CompactionResult result = ConversationCompactor.compact(conv, config);
            conv.applyCompaction(result);

            String ctx = conv.toContextString();
            assertTrue(ctx.contains("compacted"));
            assertTrue(ctx.contains("<summary>"));
            assertTrue(ctx.contains("Reasoning turn"));
        }

        @Test
        @DisplayName("multiple compactions accumulate correctly")
        void multipleCompactions_accumulate() {
            AgentConversation conv = conversationWithTurns(6, 500);
            CompactionConfig config = CompactionConfig.of(2, 100);

            // First compaction
            CompactionResult r1 = ConversationCompactor.compact(conv, config);
            conv.applyCompaction(r1);
            assertEquals(4, conv.getCompactedTurnCount());

            // Add more turns
            conv.addTurn(new ConversationTurn(6, "New turn 7 <CONTINUE>", 500, 250, now()));
            conv.addTurn(new ConversationTurn(7, "New turn 8 <CONTINUE>", 500, 250, now()));
            conv.addTurn(new ConversationTurn(8, "New turn 9", 500, 250, now()));

            // Second compaction
            CompactionResult r2 = ConversationCompactor.compact(conv, config);
            if (r2.wasCompacted()) {
                conv.applyCompaction(r2);
            }

            assertTrue(conv.hasBeenCompacted());
            assertTrue(conv.getCompactedTurnCount() >= 4);
        }
    }

    @Nested
    @DisplayName("Agent reactive loop with auto-compaction")
    class AgentAutoCompactionTests {

        @Test
        @DisplayName("compaction triggers during reactive execution")
        void reactiveLoop_triggersCompaction() {
            // Create a client that returns CONTINUE for 7 turns, then DONE
            String[] responses = new String[8];
            for (int i = 0; i < 7; i++) {
                responses[i] = "Working on step " + (i + 1) + " <CONTINUE>";
            }
            responses[7] = "Final result <DONE>";

            ChatClient client = MockChatClientFactory.withResponses(responses);
            Agent agent = Agent.builder()
                    .role("Compacting Agent")
                    .goal("Test compaction")
                    .backstory("Test agent")
                    .chatClient(client)
                    .maxTurns(10)
                    .compactionConfig(CompactionConfig.of(3, 500))
                    .build();

            Task task = createTask(agent);
            TaskOutput output = agent.executeTask(task, Collections.emptyList());

            assertNotNull(output);
            assertTrue(output.isSuccessful());
            assertEquals("Final result", output.getRawOutput());
            assertEquals(8, output.getMetadata().get("turns"));
            // Compaction should have occurred
            assertNotNull(output.getMetadata().get("compactedTurns"));
            assertTrue((int) output.getMetadata().get("compactedTurns") > 0);
        }

        @Test
        @DisplayName("compaction disabled does not compact")
        void compactionDisabled_noCompaction() {
            String[] responses = {"Step 1 <CONTINUE>", "Step 2 <CONTINUE>", "Done <DONE>"};
            ChatClient client = MockChatClientFactory.withResponses(responses);

            Agent agent = Agent.builder()
                    .role("No Compact Agent")
                    .goal("Test no compaction")
                    .backstory("Test agent")
                    .chatClient(client)
                    .maxTurns(5)
                    .compactionConfig(CompactionConfig.disabled())
                    .build();

            Task task = createTask(agent);
            TaskOutput output = agent.executeTask(task, Collections.emptyList());

            assertNotNull(output);
            assertEquals(3, output.getMetadata().get("turns"));
            assertNull(output.getMetadata().get("compactedTurns"));
        }

        @Test
        @DisplayName("withAdditionalTools preserves compaction config")
        void withAdditionalTools_preservesCompaction() {
            CompactionConfig config = CompactionConfig.of(2, 50_000);
            Agent agent = Agent.builder()
                    .role("Agent")
                    .goal("Test")
                    .backstory("Test")
                    .chatClient(mockChatClient)
                    .maxTurns(5)
                    .compactionConfig(config)
                    .build();

            Agent expanded = agent.withAdditionalTools(List.of());
            assertEquals(config, expanded.getCompactionConfig());
        }

        @Test
        @DisplayName("default compaction config is model-aware")
        void defaultConfig_isModelAware() {
            Agent agent = Agent.builder()
                    .role("Agent")
                    .goal("Test")
                    .backstory("Test")
                    .chatClient(mockChatClient)
                    .modelName("gpt-4o")
                    .build();

            CompactionConfig config = agent.getCompactionConfig();
            assertNotNull(config);
            assertTrue(config.enabled());
            // gpt-4o = 128K context → 80% = 102400
            assertEquals(102_400L, config.compactionThresholdTokens());
        }
    }

    @Nested
    @DisplayName("CompactionResult")
    class CompactionResultTests {

        @Test
        @DisplayName("none() returns no-op result")
        void none_returnsNoOp() {
            CompactionResult result = CompactionResult.none();
            assertFalse(result.wasCompacted());
            assertEquals(0, result.removedTurnCount());
            assertEquals("", result.summary());
            assertTrue(result.preservedTurns().isEmpty());
        }

        @Test
        @DisplayName("wasCompacted() is true when turns removed")
        void wasCompacted_trueWhenRemoved() {
            CompactionResult result = new CompactionResult("summary", 3, 100, 50, List.of());
            assertTrue(result.wasCompacted());
        }
    }

    // --- Helpers ---

    private AgentConversation conversationWithTurns(int count, long promptTokensPerTurn) {
        AgentConversation conv = new AgentConversation();
        for (int i = 0; i < count; i++) {
            conv.addTurn(new ConversationTurn(
                    i,
                    "Response for turn " + (i + 1) + (i < count - 1 ? " <CONTINUE>" : " <DONE>"),
                    promptTokensPerTurn,
                    promptTokensPerTurn / 2,
                    System.currentTimeMillis()));
        }
        return conv;
    }

    private long now() {
        return System.currentTimeMillis();
    }
}
