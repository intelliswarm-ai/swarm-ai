package ai.intelliswarm.swarmai.process;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.base.BaseSwarmTest;
import ai.intelliswarm.swarmai.base.MockChatClientFactory;
import ai.intelliswarm.swarmai.base.TestFixtures;
import ai.intelliswarm.swarmai.event.SwarmEvent;
import ai.intelliswarm.swarmai.memory.InMemoryMemory;
import ai.intelliswarm.swarmai.memory.Memory;
import ai.intelliswarm.swarmai.skill.*;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Self-Improving Process Tests")
class SelfImprovingProcessTest extends BaseSwarmTest {

    private Agent workerAgent;
    private Agent reviewerAgent;

    @BeforeEach
    void setUp() {
        workerAgent = createAgent("Worker Agent");
    }

    @Nested
    @DisplayName("SkillValidator permissive mode")
    class SkillValidatorPermissiveTests {

        @Test
        @DisplayName("default validator is strict (isPermissive=false)")
        void defaultIsStrict() {
            assertFalse(new SkillValidator().isPermissive());
        }

        @Test
        @DisplayName("permissive constructor sets the flag")
        void permissiveFlag() {
            assertTrue(new SkillValidator(true).isPermissive());
            assertFalse(new SkillValidator(false).isPermissive());
        }

        @Test
        @DisplayName("permissive mode accepts skills whose integration tests fail")
        void permissive_acceptsFailingIntegrationTests() {
            // Build a CODE skill whose IT will fail at runtime: assertion
            // claims output equals "wrong" but the script actually returns "right".
            SkillDefinition def = new SkillDefinition();
            def.setName("ag_test_skill");
            def.setDescription("a small skill that returns the literal 'right' to exercise validation");
            def.setType(SkillType.CODE);
            def.setCode("'right'"); // last expression is the returned String
            def.getTestCases().add("assert true");
            def.getTestCases().add("assert true");

            // Add an integration test that asserts the wrong answer — the IT
            // will fail at runtime ('right' != 'wrong'), but in permissive
            // mode the validation should still pass because the skill is callable.
            var integrationTest = new SkillDefinition.IntegrationTest(
                    "test_intentional_mismatch",
                    "verify permissive accepts even when assertion is wrong",
                    Map.of(),
                    "assert output == 'wrong'",
                    List.of()
            );
            def.getIntegrationTests().add(integrationTest);

            GeneratedSkill skill = new GeneratedSkill(def);
            skill.setLanguage(ai.intelliswarm.swarmai.skill.runtime.SkillSource.GROOVY);

            SkillValidator strict = new SkillValidator(false);
            SkillValidator permissive = new SkillValidator(true);

            SkillValidator.ValidationResult strictResult = strict.validate(skill);
            assertFalse(strictResult.passed(),
                    "strict mode must reject skills with failing integration tests");

            SkillValidator.ValidationResult permissiveResult = permissive.validate(skill);
            assertTrue(permissiveResult.passed(),
                    "permissive mode must accept the skill — its code runs, just the IT assertion was wrong");
            assertNotNull(permissiveResult.integrationTestResults(),
                    "permissive mode should still surface the IT outcome for inspection");
            assertTrue(permissiveResult.integrationTestResults().failed() > 0,
                    "the IT outcome should record the failure even though validation passed");
        }
    }

    @Nested
    @DisplayName("SkillEngine override (CodingAgent integration)")
    class SkillEngineOverrideTests {

        @Test
        @DisplayName("default skill engine is null (process falls back to LLM SkillGenerator)")
        void defaultEngineIsNull() throws Exception {
            ChatClient reviewerClient = MockChatClientFactory.withResponse("APPROVED");
            reviewerAgent = TestFixtures.createTestAgent("Reviewer", reviewerClient);
            SelfImprovingProcess process = new SelfImprovingProcess(
                    List.of(workerAgent), reviewerAgent, mockEventPublisher, 3, null);

            assertNull(readPrivateField(process, "skillEngineOverride"),
                    "engine should default to null so the LLM SkillGenerator is used");
        }

        @Test
        @DisplayName("setSkillEngine() sets the override field")
        void setterStoresEngine() throws Exception {
            ChatClient reviewerClient = MockChatClientFactory.withResponse("APPROVED");
            reviewerAgent = TestFixtures.createTestAgent("Reviewer", reviewerClient);
            SelfImprovingProcess process = new SelfImprovingProcess(
                    List.of(workerAgent), reviewerAgent, mockEventPublisher, 3, null);

            SkillEngine fake = new RecordingSkillEngine();
            SelfImprovingProcess returned = process.setSkillEngine(fake);

            assertSame(process, returned, "setSkillEngine must return this for chaining");
            assertSame(fake, readPrivateField(process, "skillEngineOverride"),
                    "engine field should hold the supplied SkillEngine");
        }

        @Test
        @DisplayName("setSkillEngine(null) reverts to the default LLM path")
        void setterAcceptsNullForRevert() throws Exception {
            ChatClient reviewerClient = MockChatClientFactory.withResponse("APPROVED");
            reviewerAgent = TestFixtures.createTestAgent("Reviewer", reviewerClient);
            SelfImprovingProcess process = new SelfImprovingProcess(
                    List.of(workerAgent), reviewerAgent, mockEventPublisher, 3, null);

            process.setSkillEngine(new RecordingSkillEngine());
            process.setSkillEngine(null);

            assertNull(readPrivateField(process, "skillEngineOverride"),
                    "passing null should clear the override and re-enable LLM fallback");
        }

        // Note: a tighter integration test that runs the full execute() loop
        // and asserts engine.generate() gets called would be ideal. Tried it,
        // but it's brittle in suite-runs because SelfImprovingProcess loads
        // persisted skills from output/skills/ in its constructor — siblings
        // that have written there cause this test's gap to dedupe against
        // pre-existing skills and skip generation. Keeping the field-level
        // tests above and the runnable codex-skill-creation showcase as the
        // end-to-end demonstration of the override actually being consulted.

        @Test
        @DisplayName("any SkillEngine implementation is acceptable")
        void acceptsArbitrarySkillEngine() throws Exception {
            ChatClient reviewerClient = MockChatClientFactory.withResponse("APPROVED");
            reviewerAgent = TestFixtures.createTestAgent("Reviewer", reviewerClient);
            SelfImprovingProcess process = new SelfImprovingProcess(
                    List.of(workerAgent), reviewerAgent, mockEventPublisher, 3, null);

            // Simulate plugging the Codex-backed engine (or any other SkillEngine).
            SkillEngine pluggable = new SkillEngine() {
                @Override public GeneratedSkill generate(String gap, List<String> tools) { return null; }
                @Override public GeneratedSkill refine(GeneratedSkill failed, String errors) { return null; }
            };
            process.setSkillEngine(pluggable);

            assertSame(pluggable, readPrivateField(process, "skillEngineOverride"));
        }

        // -- helpers --

        private static Object readPrivateField(Object target, String name) throws Exception {
            var f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        }

        /** SkillEngine that records every call — proves the field can hold any impl. */
        private static final class RecordingSkillEngine implements SkillEngine {
            int generateCalls;
            int refineCalls;
            @Override public GeneratedSkill generate(String gap, List<String> tools) {
                generateCalls++;
                return null;
            }
            @Override public GeneratedSkill refine(GeneratedSkill failed, String errors) {
                refineCalls++;
                return null;
            }
        }
    }

    @Nested
    @DisplayName("Constructor and Memory Integration")
    class ConstructorTests {

        @Test
        @DisplayName("constructs without memory (backward compatible)")
        void construct_withoutMemory_works() {
            ChatClient reviewerClient = MockChatClientFactory.withResponse("APPROVED");
            reviewerAgent = TestFixtures.createTestAgent("Reviewer", reviewerClient);

            SelfImprovingProcess process = new SelfImprovingProcess(
                List.of(workerAgent), reviewerAgent, mockEventPublisher, 3, null);

            assertNotNull(process);
            assertNull(process.getMemory());
            assertEquals(ProcessType.SELF_IMPROVING, process.getType());
            assertFalse(process.isAsync());
        }

        @Test
        @DisplayName("constructs with memory")
        void construct_withMemory_works() {
            ChatClient reviewerClient = MockChatClientFactory.withResponse("APPROVED");
            reviewerAgent = TestFixtures.createTestAgent("Reviewer", reviewerClient);
            Memory memory = new InMemoryMemory();

            SelfImprovingProcess process = new SelfImprovingProcess(
                List.of(workerAgent), reviewerAgent, mockEventPublisher, 3, null, memory);

            assertNotNull(process);
            assertNotNull(process.getMemory());
        }
    }

    @Nested
    @DisplayName("execute() - Approved on first iteration")
    class ApprovedFirstIterationTests {

        @Test
        @DisplayName("stops iterating when reviewer approves")
        void execute_approved_stopsImmediately() {
            ChatClient reviewerClient = MockChatClientFactory.withResponse(
                "VERDICT: APPROVED\n\nExcellent output.");
            reviewerAgent = TestFixtures.createTestAgent("Reviewer", reviewerClient);

            SelfImprovingProcess process = new SelfImprovingProcess(
                List.of(workerAgent), reviewerAgent, mockEventPublisher, 3, null);

            Task task = createTask(workerAgent);
            SwarmOutput output = process.execute(List.of(task), Map.of(), "test-swarm");

            assertNotNull(output);
            assertTrue(output.isSuccessful());
            assertEquals(1, output.getMetadata().get("totalIterations"));
            assertEquals(0, output.getMetadata().get("skillsGenerated"));
            assertEquals(0, output.getMetadata().get("skillsReused"));
        }

        @Test
        @DisplayName("publishes ITERATION_REVIEW_PASSED event")
        void execute_approved_publishesPassedEvent() {
            ChatClient reviewerClient = MockChatClientFactory.withResponse(
                "VERDICT: APPROVED");
            reviewerAgent = TestFixtures.createTestAgent("Reviewer", reviewerClient);

            SelfImprovingProcess process = new SelfImprovingProcess(
                List.of(workerAgent), reviewerAgent, mockEventPublisher, 3, null);

            Task task = createTask(workerAgent);
            process.execute(List.of(task), Map.of(), "test-swarm");

            assertEventPublished(SwarmEvent.Type.PROCESS_STARTED);
            assertEventPublished(SwarmEvent.Type.ITERATION_STARTED);
            assertEventPublished(SwarmEvent.Type.ITERATION_REVIEW_PASSED);
            assertEventPublished(SwarmEvent.Type.ITERATION_COMPLETED);
        }
    }

    @Nested
    @DisplayName("execute() - Memory integration")
    class MemoryIntegrationTests {

        @Test
        @DisplayName("saves iteration outcomes to memory")
        void execute_withMemory_savesIterationOutcome() {
            ChatClient reviewerClient = MockChatClientFactory.withResponse(
                "VERDICT: APPROVED");
            reviewerAgent = TestFixtures.createTestAgent("Reviewer", reviewerClient);
            InMemoryMemory memory = new InMemoryMemory();

            SelfImprovingProcess process = new SelfImprovingProcess(
                List.of(workerAgent), reviewerAgent, mockEventPublisher, 3, null, memory);

            Task task = createTask(workerAgent);
            process.execute(List.of(task), Map.of(), "test-swarm");

            // Memory should have at least the iteration outcome
            assertFalse(memory.isEmpty());
            List<String> memories = memory.search("iteration", 10);
            assertFalse(memories.isEmpty());
            assertTrue(memories.stream().anyMatch(m -> m.contains("APPROVED")));
        }

        @Test
        @DisplayName("works correctly without memory (null)")
        void execute_withoutMemory_noErrors() {
            ChatClient reviewerClient = MockChatClientFactory.withResponse(
                "VERDICT: APPROVED");
            reviewerAgent = TestFixtures.createTestAgent("Reviewer", reviewerClient);

            SelfImprovingProcess process = new SelfImprovingProcess(
                List.of(workerAgent), reviewerAgent, mockEventPublisher, 3, null, (ai.intelliswarm.swarmai.memory.Memory) null);

            Task task = createTask(workerAgent);
            SwarmOutput output = process.execute(List.of(task), Map.of(), "test-swarm");

            assertNotNull(output);
            assertTrue(output.isSuccessful());
        }
    }

    @Nested
    @DisplayName("execute() - Quality issues (no capability gaps)")
    class QualityIssuesTests {

        @Test
        @DisplayName("re-executes with feedback when quality issues found")
        void execute_qualityIssues_reExecutes() {
            ChatClient workerClient = MockChatClientFactory.withResponses("Draft v1", "Draft v2");
            Agent iteratingWorker = TestFixtures.createTestAgent("Worker", workerClient);

            ChatClient reviewerClient = MockChatClientFactory.withResponses(
                "VERDICT: NEEDS_REFINEMENT\n\nQUALITY_ISSUES:\n- Missing code examples\n- Needs more depth",
                "VERDICT: APPROVED");
            reviewerAgent = TestFixtures.createTestAgent("Reviewer", reviewerClient);

            SelfImprovingProcess process = new SelfImprovingProcess(
                List.of(iteratingWorker), reviewerAgent, mockEventPublisher, 3, null);

            Task task = Task.builder()
                .description("Write a technical post")
                .expectedOutput("A post with examples")
                .agent(iteratingWorker)
                .build();

            SwarmOutput output = process.execute(List.of(task), Map.of(), "test-swarm");

            assertNotNull(output);
            assertTrue(output.isSuccessful());
            assertEquals(2, output.getMetadata().get("totalIterations"));
            assertEquals(0, output.getMetadata().get("skillsGenerated"));
        }
    }

    @Nested
    @DisplayName("execute() - Thread interruption")
    class ThreadInterruptionTests {

        @Test
        @DisplayName("stops immediately when thread is already interrupted")
        void execute_interruptedThread_stopsWithoutIterations() {
            ChatClient reviewerClient = MockChatClientFactory.withResponse("VERDICT: APPROVED");
            reviewerAgent = TestFixtures.createTestAgent("Reviewer", reviewerClient);

            SelfImprovingProcess process = new SelfImprovingProcess(
                List.of(workerAgent), reviewerAgent, mockEventPublisher, 3, null);

            Task task = createTask(workerAgent);
            Thread.currentThread().interrupt();
            try {
                SwarmOutput output = process.execute(List.of(task), Map.of(), "test-swarm");

                assertNotNull(output);
                assertFalse(output.isSuccessful());
                assertEquals(0, output.getMetadata().get("totalIterations"));
            } finally {
                Thread.interrupted(); // clear interrupt flag for following tests
            }
        }
    }

    @Nested
    @DisplayName("execute() - Max iterations")
    class MaxIterationsTests {

        @Test
        @DisplayName("stops after max iterations even if not approved")
        void execute_maxIterations_stops() {
            ChatClient reviewerClient = MockChatClientFactory.withResponse(
                "VERDICT: NEEDS_REFINEMENT\n\nQUALITY_ISSUES:\n- Not good enough");
            reviewerAgent = TestFixtures.createTestAgent("Reviewer", reviewerClient);

            SelfImprovingProcess process = new SelfImprovingProcess(
                List.of(workerAgent), reviewerAgent, mockEventPublisher, 2, null);

            Task task = createTask(workerAgent);
            SwarmOutput output = process.execute(List.of(task), Map.of(), "test-swarm");

            assertNotNull(output);
            assertEquals(2, output.getMetadata().get("totalIterations"));
        }
    }

    @Nested
    @DisplayName("validateTasks()")
    class ValidationTests {

        @Test
        @DisplayName("throws on empty task list")
        void validateTasks_emptyList_throws() {
            ChatClient reviewerClient = MockChatClientFactory.withResponse("APPROVED");
            reviewerAgent = TestFixtures.createTestAgent("Reviewer", reviewerClient);

            SelfImprovingProcess process = new SelfImprovingProcess(
                List.of(workerAgent), reviewerAgent, mockEventPublisher, 3, null);

            assertThrows(IllegalArgumentException.class, () ->
                process.execute(Collections.emptyList(), Map.of(), "test-swarm"));
        }

        @Test
        @DisplayName("throws on null task list")
        void validateTasks_nullList_throws() {
            ChatClient reviewerClient = MockChatClientFactory.withResponse("APPROVED");
            reviewerAgent = TestFixtures.createTestAgent("Reviewer", reviewerClient);

            SelfImprovingProcess process = new SelfImprovingProcess(
                List.of(workerAgent), reviewerAgent, mockEventPublisher, 3, null);

            assertThrows(IllegalArgumentException.class, () ->
                process.execute(null, Map.of(), "test-swarm"));
        }
    }

    @Nested
    @DisplayName("Skill Registry Integration")
    class SkillRegistryTests {

        @Test
        @DisplayName("exposes skill registry with search and stats")
        void getSkillRegistry_returnsUsableRegistry() {
            ChatClient reviewerClient = MockChatClientFactory.withResponse("APPROVED");
            reviewerAgent = TestFixtures.createTestAgent("Reviewer", reviewerClient);

            SelfImprovingProcess process = new SelfImprovingProcess(
                List.of(workerAgent), reviewerAgent, mockEventPublisher, 3, null);

            SkillRegistry registry = process.getSkillRegistry();
            assertNotNull(registry);
            assertNotNull(registry.getStats());
            assertNotNull(registry.getActiveSkills());
            // findSimilar and selectRelevant should work without error
            assertNotNull(registry.findSimilar("test gap", 0.5));
            assertNotNull(registry.selectRelevant("test task", 5));
        }
    }

    @Nested
    @DisplayName("Output metadata")
    class OutputMetadataTests {

        @Test
        @DisplayName("includes skillsReused and skillsPromoted in metadata")
        void execute_metadata_includesNewFields() {
            ChatClient reviewerClient = MockChatClientFactory.withResponse(
                "VERDICT: APPROVED");
            reviewerAgent = TestFixtures.createTestAgent("Reviewer", reviewerClient);

            SelfImprovingProcess process = new SelfImprovingProcess(
                List.of(workerAgent), reviewerAgent, mockEventPublisher, 3, null);

            Task task = createTask(workerAgent);
            SwarmOutput output = process.execute(List.of(task), Map.of(), "test-swarm");

            Map<String, Object> metadata = output.getMetadata();
            assertTrue(metadata.containsKey("skillsGenerated"));
            assertTrue(metadata.containsKey("skillsReused"));
            assertTrue(metadata.containsKey("skillsPromoted"));
            assertTrue(metadata.containsKey("totalIterations"));
            assertTrue(metadata.containsKey("registryStats"));
        }
    }

    @Nested
    @DisplayName("Process Properties")
    class ProcessPropertiesTests {

        @Test
        @DisplayName("returns SELF_IMPROVING type")
        void getType_returnsSelfImproving() {
            ChatClient reviewerClient = MockChatClientFactory.withResponse("APPROVED");
            reviewerAgent = TestFixtures.createTestAgent("Reviewer", reviewerClient);

            SelfImprovingProcess process = new SelfImprovingProcess(
                List.of(workerAgent), reviewerAgent, mockEventPublisher, 3, null);

            assertEquals(ProcessType.SELF_IMPROVING, process.getType());
        }

        @Test
        @DisplayName("is not async")
        void isAsync_returnsFalse() {
            ChatClient reviewerClient = MockChatClientFactory.withResponse("APPROVED");
            reviewerAgent = TestFixtures.createTestAgent("Reviewer", reviewerClient);

            SelfImprovingProcess process = new SelfImprovingProcess(
                List.of(workerAgent), reviewerAgent, mockEventPublisher, 3, null);

            assertFalse(process.isAsync());
        }
    }
}
