package ai.intelliswarm.swarmai.state;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.task.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@DisplayName("SwarmGraph Compilation Tests")
class SwarmGraphCompilationTest {

    private ChatClient chatClient;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class);
    }

    private Agent createAgent(String role) {
        return Agent.builder()
                .role(role)
                .goal("Test goal")
                .backstory("Test backstory")
                .chatClient(chatClient)
                .build();
    }

    private Task createTask(String description) {
        return Task.builder()
                .description(description)
                .agent(createAgent("worker"))
                .build();
    }

    @Nested
    @DisplayName("Successful compilation")
    class SuccessfulCompilationTests {

        @Test
        @DisplayName("compiles valid sequential graph")
        void compilesValidSequentialGraph() {
            CompilationResult result = SwarmGraph.create()
                    .addAgent(createAgent("researcher"))
                    .addTask(createTask("Research AI"))
                    .process(ProcessType.SEQUENTIAL)
                    .compile();

            assertTrue(result.isSuccess());
            assertNotNull(result.compiled());
            assertEquals(ProcessType.SEQUENTIAL, result.compiled().processType());
            assertEquals(1, result.compiled().agents().size());
            assertEquals(1, result.compiled().tasks().size());
        }

        @Test
        @DisplayName("compiles hierarchical graph with manager")
        void compilesHierarchicalWithManager() {
            Agent manager = createAgent("manager");

            CompilationResult result = SwarmGraph.create()
                    .addAgent(createAgent("worker"))
                    .addTask(createTask("Do work"))
                    .process(ProcessType.HIERARCHICAL)
                    .managerAgent(manager)
                    .compile();

            assertTrue(result.isSuccess());
        }

        @Test
        @DisplayName("compileOrThrow returns CompiledSwarm on success")
        void compileOrThrowReturnsOnSuccess() {
            CompiledSwarm compiled = SwarmGraph.create()
                    .addAgent(createAgent("worker"))
                    .addTask(createTask("Do work"))
                    .compileOrThrow();

            assertNotNull(compiled);
            assertNotNull(compiled.getId());
        }

        @Test
        @DisplayName("compiled swarm has custom id when set")
        void compiledSwarmHasCustomId() {
            CompiledSwarm compiled = SwarmGraph.create()
                    .id("my-swarm-123")
                    .addAgent(createAgent("worker"))
                    .addTask(createTask("Do work"))
                    .compileOrThrow();

            assertEquals("my-swarm-123", compiled.getId());
        }

        @Test
        @DisplayName("compiled swarm is immutable")
        void compiledSwarmIsImmutable() {
            CompiledSwarm compiled = SwarmGraph.create()
                    .addAgent(createAgent("worker"))
                    .addTask(createTask("Do work"))
                    .compileOrThrow();

            // Lists returned by agents() and tasks() should be immutable
            assertThrows(UnsupportedOperationException.class,
                    () -> compiled.agents().add(createAgent("sneaky")));
            assertThrows(UnsupportedOperationException.class,
                    () -> compiled.tasks().add(createTask("sneaky")));
        }
    }

    @Nested
    @DisplayName("Compilation errors")
    class CompilationErrorTests {

        @Test
        @DisplayName("fails with no agents")
        void failsWithNoAgents() {
            CompilationResult result = SwarmGraph.create()
                    .addTask(createTask("Do work"))
                    .compile();

            assertFalse(result.isSuccess());
            assertTrue(result.errors().stream()
                    .anyMatch(e -> e instanceof CompilationError.NoAgents));
        }

        @Test
        @DisplayName("fails with no tasks")
        void failsWithNoTasks() {
            CompilationResult result = SwarmGraph.create()
                    .addAgent(createAgent("worker"))
                    .compile();

            assertFalse(result.isSuccess());
            assertTrue(result.errors().stream()
                    .anyMatch(e -> e instanceof CompilationError.NoTasks));
        }

        @Test
        @DisplayName("collects multiple errors at once")
        void collectsMultipleErrors() {
            CompilationResult result = SwarmGraph.create()
                    .process(ProcessType.HIERARCHICAL)
                    .compile();

            assertFalse(result.isSuccess());
            // Should have NoAgents, NoTasks, and MissingManagerAgent
            assertTrue(result.errors().size() >= 3);
        }

        @Test
        @DisplayName("fails when hierarchical has no manager")
        void failsHierarchicalWithoutManager() {
            CompilationResult result = SwarmGraph.create()
                    .addAgent(createAgent("worker"))
                    .addTask(createTask("Do work"))
                    .process(ProcessType.HIERARCHICAL)
                    .compile();

            assertFalse(result.isSuccess());
            assertTrue(result.errors().stream()
                    .anyMatch(e -> e instanceof CompilationError.MissingManagerAgent));
        }

        @Test
        @DisplayName("fails when iterative has no manager")
        void failsIterativeWithoutManager() {
            CompilationResult result = SwarmGraph.create()
                    .addAgent(createAgent("worker"))
                    .addTask(createTask("Do work"))
                    .process(ProcessType.ITERATIVE)
                    .compile();

            assertFalse(result.isSuccess());
            assertTrue(result.errors().stream()
                    .anyMatch(e -> e instanceof CompilationError.MissingManagerAgent mgr
                            && mgr.processType().equals("iterative")));
        }

        @Test
        @DisplayName("fails when self-improving has no manager")
        void failsSelfImprovingWithoutManager() {
            CompilationResult result = SwarmGraph.create()
                    .addAgent(createAgent("worker"))
                    .addTask(createTask("Do work"))
                    .process(ProcessType.SELF_IMPROVING)
                    .compile();

            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("fails with invalid task dependency")
        void failsWithInvalidDependency() {
            Task task = Task.builder()
                    .description("Depends on ghost")
                    .agent(createAgent("worker"))
                    .dependsOn("non-existent-task-id")
                    .build();

            CompilationResult result = SwarmGraph.create()
                    .addAgent(createAgent("worker"))
                    .addTask(task)
                    .compile();

            assertFalse(result.isSuccess());
            assertTrue(result.errors().stream()
                    .anyMatch(e -> e instanceof CompilationError.InvalidDependency));
        }

        @Test
        @DisplayName("compileOrThrow throws descriptive exception on failure")
        void compileOrThrowThrowsOnFailure() {
            IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                    SwarmGraph.create()
                            .process(ProcessType.HIERARCHICAL)
                            .compileOrThrow());

            assertTrue(ex.getMessage().contains("compilation failed"));
            assertTrue(ex.getMessage().contains("error"));
        }

        @Test
        @DisplayName("compiled() throws on failure result")
        void compiledThrowsOnFailure() {
            CompilationResult result = SwarmGraph.create().compile();
            assertFalse(result.isSuccess());
            assertThrows(IllegalStateException.class, result::compiled);
        }
    }

    @Nested
    @DisplayName("SwarmDefinition sealed hierarchy")
    class SealedHierarchyTests {

        @Test
        @DisplayName("SwarmGraph is a SwarmDefinition")
        void swarmGraphIsSwarmDefinition() {
            SwarmGraph graph = SwarmGraph.create()
                    .addAgent(createAgent("worker"))
                    .addTask(createTask("Do work"));

            assertInstanceOf(SwarmDefinition.class, graph);
        }

        @Test
        @DisplayName("CompiledSwarm is a SwarmDefinition")
        void compiledSwarmIsSwarmDefinition() {
            CompiledSwarm compiled = SwarmGraph.create()
                    .addAgent(createAgent("worker"))
                    .addTask(createTask("Do work"))
                    .compileOrThrow();

            assertInstanceOf(SwarmDefinition.class, compiled);
        }

        @Test
        @DisplayName("pattern matching works on sealed hierarchy")
        void patternMatchingWorks() {
            SwarmDefinition def = SwarmGraph.create()
                    .addAgent(createAgent("worker"))
                    .addTask(createTask("Do work"));

            String result = switch (def) {
                case SwarmGraph g -> "graph with " + g.agents().size() + " agents";
                case CompiledSwarm c -> "compiled: " + c.getId();
            };

            assertEquals("graph with 1 agents", result);
        }
    }

    @Nested
    @DisplayName("StateSchema integration")
    class StateSchemaTests {

        @Test
        @DisplayName("compiled swarm uses default permissive schema")
        void defaultPermissiveSchema() {
            CompiledSwarm compiled = SwarmGraph.create()
                    .addAgent(createAgent("worker"))
                    .addTask(createTask("Do work"))
                    .compileOrThrow();

            assertEquals(StateSchema.PERMISSIVE, compiled.getStateSchema());
        }

        @Test
        @DisplayName("compiled swarm uses custom schema")
        void customSchema() {
            StateSchema schema = StateSchema.builder()
                    .channel("messages", Channels.<String>appender())
                    .channel("count", Channels.counter())
                    .allowUndeclaredKeys(true)
                    .build();

            CompiledSwarm compiled = SwarmGraph.create()
                    .stateSchema(schema)
                    .addAgent(createAgent("worker"))
                    .addTask(createTask("Do work"))
                    .compileOrThrow();

            assertEquals(schema, compiled.getStateSchema());
        }
    }

    @Nested
    @DisplayName("CompilationError messages")
    class ErrorMessageTests {

        @Test
        @DisplayName("MissingManagerAgent has descriptive message")
        void missingManagerMessage() {
            var error = new CompilationError.MissingManagerAgent("hierarchical");
            assertTrue(error.message().contains("hierarchical"));
            assertTrue(error.message().contains("Manager agent"));
        }

        @Test
        @DisplayName("InvalidDependency has descriptive message")
        void invalidDependencyMessage() {
            var error = new CompilationError.InvalidDependency("task-1", "task-ghost");
            assertTrue(error.message().contains("task-1"));
            assertTrue(error.message().contains("task-ghost"));
        }

        @Test
        @DisplayName("CompilationResult toString summarizes errors")
        void compilationResultToString() {
            CompilationResult result = SwarmGraph.create().compile();
            assertTrue(result.toString().contains("errors"));
        }
    }
}
