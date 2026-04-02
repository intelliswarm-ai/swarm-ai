package ai.intelliswarm.swarmai.dsl;

import ai.intelliswarm.swarmai.dsl.compiler.SwarmCompiler;
import ai.intelliswarm.swarmai.dsl.parser.YamlSwarmParser;
import ai.intelliswarm.swarmai.swarm.Swarm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SwarmLoaderTest {

    private SwarmLoader loader;

    @BeforeEach
    void setUp() {
        AssistantMessage message = new AssistantMessage("Mock response");
        Generation generation = new Generation(message);

        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(100);
        when(usage.getCompletionTokens()).thenReturn(50);
        when(usage.getTotalTokens()).thenReturn(150);

        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        when(metadata.getUsage()).thenReturn(usage);

        ChatResponse chatResponse = mock(ChatResponse.class);
        when(chatResponse.getResult()).thenReturn(generation);
        when(chatResponse.getMetadata()).thenReturn(metadata);

        ChatClient chatClient = mock(ChatClient.class, Mockito.RETURNS_DEEP_STUBS);
        when(chatClient.prompt().system(anyString()).user(anyString())
                .call().chatResponse()).thenReturn(chatResponse);
        when(chatClient.prompt().system(anyString()).user(anyString())
                .toolNames(any(String[].class)).call().chatResponse()).thenReturn(chatResponse);
        when(chatClient.prompt().user(anyString())
                .call().chatResponse()).thenReturn(chatResponse);
        when(chatClient.prompt().user(anyString())
                .toolNames(any(String[].class)).call().chatResponse()).thenReturn(chatResponse);

        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

        YamlSwarmParser parser = new YamlSwarmParser();
        SwarmCompiler compiler = SwarmCompiler.builder()
                .chatClient(chatClient)
                .eventPublisher(eventPublisher)
                .build();

        loader = new SwarmLoader(parser, compiler);
    }

    @Test
    void loadFromClasspath() throws IOException {
        Swarm swarm = loader.load("workflows/simple-sequential.yaml");

        assertNotNull(swarm);
        assertEquals("Simple Research", swarm.getId());
        assertEquals(2, swarm.getAgents().size());
        assertEquals(2, swarm.getTasks().size());
    }

    @Test
    void loadWithVariables() throws IOException {
        Swarm swarm = loader.load("workflows/template-workflow.yaml",
                Map.of(
                        "workflowName", "Security Audit",
                        "maxTokens", 100000,
                        "role", "Security Auditor",
                        "topic", "API security"
                ));

        assertNotNull(swarm);
        assertEquals("Security Audit", swarm.getId());
    }

    @Test
    void fromInlineYaml() throws IOException {
        Swarm swarm = loader.fromYaml("""
                swarm:
                  process: SEQUENTIAL
                  agents:
                    helper:
                      role: "Helper"
                      goal: "Help with tasks"
                      backstory: "A helpful assistant"
                  tasks:
                    greet:
                      description: "Say hello"
                      agent: helper
                """);

        assertNotNull(swarm);
        assertEquals(1, swarm.getAgents().size());
    }

    @Test
    void failOnMissingResource() {
        assertThrows(IOException.class,
                () -> loader.load("workflows/nonexistent.yaml"));
    }
}
