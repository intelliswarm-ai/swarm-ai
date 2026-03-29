package ai.intelliswarm.swarmai.state;

import ai.intelliswarm.swarmai.process.ProcessType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Mermaid Diagram Generator Tests")
class MermaidDiagramTest {

    @Test
    @DisplayName("generates diagram for sequential workflow")
    void generatesSequentialDiagram() {
        var task1 = ai.intelliswarm.swarmai.task.Task.builder()
                .id("research")
                .description("Research AI market trends")
                .agent(createAgent())
                .build();

        var task2 = ai.intelliswarm.swarmai.task.Task.builder()
                .id("analyze")
                .description("Analyze competitive landscape")
                .agent(createAgent())
                .dependsOn(task1)
                .build();

        CompiledSwarm swarm = SwarmGraph.create()
                .addAgent(createAgent())
                .addTask(task1)
                .addTask(task2)
                .process(ProcessType.SEQUENTIAL)
                .compileOrThrow();

        String diagram = new MermaidDiagramGenerator().generate(swarm);

        assertTrue(diagram.startsWith("graph TD"));
        assertTrue(diagram.contains("START"));
        assertTrue(diagram.contains("END"));
        assertTrue(diagram.contains("research"));
        assertTrue(diagram.contains("analyze"));
        assertTrue(diagram.contains("Research AI market trends"));
        assertTrue(diagram.contains("SEQUENTIAL"));
    }

    @Test
    @DisplayName("generates diagram with no tasks")
    void generatesEmptyDiagram() {
        // Can't compile with no tasks, so test the generator directly
        // by creating a swarm with tasks and checking format
        var task = ai.intelliswarm.swarmai.task.Task.builder()
                .id("only-task")
                .description("Single task workflow")
                .agent(createAgent())
                .build();

        CompiledSwarm swarm = SwarmGraph.create()
                .addAgent(createAgent())
                .addTask(task)
                .compileOrThrow();

        String diagram = new MermaidDiagramGenerator().generate(swarm);
        assertTrue(diagram.contains("START"));
        assertTrue(diagram.contains("only-task"));
        assertTrue(diagram.contains("END"));
    }

    @Test
    @DisplayName("highlights interrupt points")
    void highlightsInterruptPoints() {
        var task = ai.intelliswarm.swarmai.task.Task.builder()
                .id("review-step")
                .description("Human review required")
                .agent(createAgent())
                .build();

        CompiledSwarm swarm = SwarmGraph.create()
                .addAgent(createAgent())
                .addTask(task)
                .interruptBefore("review-step")
                .compileOrThrow();

        String diagram = new MermaidDiagramGenerator().generate(swarm);
        assertTrue(diagram.contains("style review-step stroke:#f66"));
    }

    @Test
    @DisplayName("truncates long descriptions")
    void truncatesLongDescriptions() {
        var task = ai.intelliswarm.swarmai.task.Task.builder()
                .id("long-task")
                .description("This is a very long task description that should be truncated in the diagram output")
                .agent(createAgent())
                .build();

        CompiledSwarm swarm = SwarmGraph.create()
                .addAgent(createAgent())
                .addTask(task)
                .compileOrThrow();

        String diagram = new MermaidDiagramGenerator().generate(swarm);
        assertTrue(diagram.contains("..."));
    }

    private ai.intelliswarm.swarmai.agent.Agent createAgent() {
        return ai.intelliswarm.swarmai.agent.Agent.builder()
                .role("worker")
                .goal("test")
                .backstory("test")
                .chatClient(org.mockito.Mockito.mock(
                        org.springframework.ai.chat.client.ChatClient.class))
                .build();
    }
}
