package ai.intelliswarm.swarmai.agent;

import ai.intelliswarm.swarmai.base.MockChatClientFactory;
import ai.intelliswarm.swarmai.task.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * The system prompt used to hardcode {@code /app/output/} as the place to write output files.
 * That path only exists inside the project's Docker images. On a host run the directory is not
 * creatable, so every file-writing agent burned its retry budget on
 * {@code AccessDeniedException: /app} — which made the `secure-ops` regression workflow
 * non-deterministically time out.
 *
 * <p>The directory is now resolved, not assumed.
 */
@DisplayName("Agent — output directory resolution")
class AgentOutputDirectoryTest {

    @AfterEach
    void clearProperty() {
        System.clearProperty("swarmai.output.dir");
    }

    private static Agent agent(String explicitDir) {
        Agent.Builder builder = Agent.builder()
                .role("Reporter")
                .goal("Write a report")
                .backstory("A reporter")
                .chatClient(MockChatClientFactory.withResponse("done"));
        if (explicitDir != null) {
            builder.outputDirectory(explicitDir);
        }
        return builder.build();
    }

    /** Captures the system prompt the agent actually sends. */
    private static String systemPromptOf(Agent agent, ChatClient chatClient) {
        Task task = Task.builder()
                .description("Write the report")
                .expectedOutput("A report")
                .agent(agent)
                .build();
        agent.executeTask(task, List.of());

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(chatClient.prompt(), atLeastOnce()).system(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("resolution order")
    class Resolution {

        @Test
        @DisplayName("an explicit builder value wins")
        void explicitBuilderValueWins() {
            System.setProperty("swarmai.output.dir", "/from-property");

            assertEquals("/explicit", agent("/explicit").resolveOutputDirectory());
        }

        @Test
        @DisplayName("the swarmai.output.dir property is honoured")
        void systemPropertyHonoured() {
            System.setProperty("swarmai.output.dir", "/custom/reports");

            assertEquals("/custom/reports", agent(null).resolveOutputDirectory());
        }

        @Test
        @DisplayName("falls back to ./output on a host where /app/output does not exist")
        void hostFallback() {
            // The whole point: on any machine where /app/output isn't a writable directory —
            // i.e. every non-container run — we must NOT name it.
            assumeNoContainerOutputDir();

            assertEquals(Agent.DEFAULT_OUTPUT_DIRECTORY, agent(null).resolveOutputDirectory());
        }
    }

    @Nested
    @DisplayName("system prompt")
    class Prompt {

        @Test
        @DisplayName("does NOT instruct the model to write to /app/output on a host")
        void hostPromptDoesNotMentionContainerPath() {
            assumeNoContainerOutputDir();

            ChatClient chatClient = MockChatClientFactory.withResponse("done");
            Agent agent = Agent.builder()
                    .role("Reporter").goal("Write a report").backstory("A reporter")
                    .chatClient(chatClient)
                    .build();

            String systemPrompt = systemPromptOf(agent, chatClient);

            assertFalse(systemPrompt.contains("/app/output"),
                    "REGRESSION: the agent is being told to write to a container-only path on a "
                            + "host. File writes will fail with AccessDeniedException and burn the "
                            + "retry budget. Prompt rule was:\n" + outputRuleOf(systemPrompt));
            assertTrue(systemPrompt.contains(Agent.DEFAULT_OUTPUT_DIRECTORY),
                    "the prompt should name the directory that actually resolves");
        }

        @Test
        @DisplayName("names the configured directory when one is set")
        void promptNamesConfiguredDirectory() {
            ChatClient chatClient = MockChatClientFactory.withResponse("done");
            Agent agent = Agent.builder()
                    .role("Reporter").goal("Write a report").backstory("A reporter")
                    .chatClient(chatClient)
                    .outputDirectory("/data/reports")
                    .build();

            String systemPrompt = systemPromptOf(agent, chatClient);

            assertTrue(systemPrompt.contains("/data/reports"),
                    "prompt must name the configured output directory. Rule was:\n"
                            + outputRuleOf(systemPrompt));
        }
    }

    @Nested
    @DisplayName("agent copies")
    class Copies {

        @Test
        @DisplayName("withAdditionalTools preserves the output directory")
        void copyPreservesOutputDirectory() {
            // Self-improving workflows rebuild agents with newly generated skills via this path;
            // dropping the setting there would silently resurrect the bug mid-run.
            Agent original = agent("/data/reports");

            Agent copy = original.withAdditionalTools(List.of());

            assertEquals("/data/reports", copy.getOutputDirectory());
            assertEquals("/data/reports", copy.resolveOutputDirectory());
        }
    }

    /** Skips a test when it happens to run inside a container that really has /app/output. */
    private static void assumeNoContainerOutputDir() {
        java.nio.file.Path p = java.nio.file.Path.of("/app/output");
        org.junit.jupiter.api.Assumptions.assumeFalse(
                java.nio.file.Files.isDirectory(p) && java.nio.file.Files.isWritable(p),
                "running inside a container with a real /app/output — host-fallback test N/A");
    }

    private static String outputRuleOf(String systemPrompt) {
        return systemPrompt.lines()
                .filter(l -> l.contains("OUTPUT FILES"))
                .findFirst()
                .orElse("(no OUTPUT FILES rule found)");
    }
}
