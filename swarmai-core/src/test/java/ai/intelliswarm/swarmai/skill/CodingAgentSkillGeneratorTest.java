package ai.intelliswarm.swarmai.skill;

import ai.intelliswarm.swarmai.agent.subagent.EphemeralDirectoryWorkspace;
import ai.intelliswarm.swarmai.agent.subagent.IsolatedWorkspace;
import ai.intelliswarm.swarmai.agent.subagent.Subagent;
import ai.intelliswarm.swarmai.agent.subagent.SubagentResult;
import ai.intelliswarm.swarmai.agent.subagent.SubagentSpawner;
import ai.intelliswarm.swarmai.agent.subagent.SubagentSpec;
import ai.intelliswarm.swarmai.agent.subagent.SubagentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests {@link CodingAgentSkillGenerator} via a fake {@link SubagentSpawner}
 * that simulates a coding agent's filesystem side-effects without launching
 * any real process.
 *
 * <p>The pattern: each test plugs in a {@link Consumer} that runs against
 * the workspace root before the fake subagent's terminal status is
 * reported. That consumer plays the role of the agent's filesystem actions
 * (write SKILL.md, write nothing, write garbage, etc.).
 */
class CodingAgentSkillGeneratorTest {

    /** A valid CODE-type SKILL.md the parser will accept. */
    private static final String VALID_SKILL_MD = """
            ---
            name: test_skill
            description: A test skill that performs a simple arithmetic operation for verification
            type: CODE
            triggerWhen: when the user wants a basic test computation
            avoidWhen: never
            category: computation
            tags: [test, generated]
            ---

            # Test Skill

            ## Code
            ```groovy
            def n = (params.get("n") ?: 0) as int
            return "result=" + (n * 2)
            ```

            ## Test Cases
            ```groovy
            assert true
            ```

            ## Integration Tests

            ### test_basic
            Verify it doubles a number

            **Input:**
            ```yaml
            n: 5
            ```

            **Assertions:**
            ```groovy
            assert output != null
            assert output.contains("result=10")
            ```
            """;

    // ---------------------------------------------------------------------
    // Fake spawner / subagent infrastructure
    // ---------------------------------------------------------------------

    /**
     * Fake spawner that:
     *  - calls {@code spec.workspaceFactory().get()} to materialise the workspace,
     *  - runs {@code agentBehavior} on the workspace root (this stands in for
     *    the coding agent's filesystem actions),
     *  - returns a {@link Subagent} whose {@code awaitResult} returns
     *    {@code resultStatus}.
     *
     * <p>If the test wants to verify that the workspace was reachable
     * <em>during</em> the agent run, it can capture state inside the
     * behavior consumer.
     */
    private static SubagentSpawner fakeSpawner(SubagentStatus resultStatus,
                                                Consumer<Path> agentBehavior) {
        return spec -> {
            // Materialise the workspace as the real spawner would.
            IsolatedWorkspace ws = spec.workspaceFactory().get();
            try {
                agentBehavior.accept(ws.root());
            } catch (RuntimeException e) {
                throw new RuntimeException("agent behavior threw", e);
            }
            return new FakeSubagent(spec.id(), resultStatus);
        };
    }

    private static final class FakeSubagent implements Subagent {
        private final String id;
        private final SubagentStatus status;
        FakeSubagent(String id, SubagentStatus status) {
            this.id = id;
            this.status = status;
        }
        @Override public String id() { return id; }
        @Override public SubagentStatus status() { return status; }
        @Override public SubagentResult awaitResult(Duration cap) {
            return switch (status) {
                case COMPLETED -> SubagentResult.success(id, "ok", Map.of());
                case FAILED    -> SubagentResult.failure(id, "fake failure", Map.of());
                case TIMED_OUT -> SubagentResult.timedOut(id, Map.of());
                case CANCELLED -> SubagentResult.cancelled(id, Map.of());
                default        -> SubagentResult.failure(id, "unexpected status " + status, Map.of());
            };
        }
        @Override public void cancel() { /* no-op */ }
    }

    private static java.util.function.Supplier<IsolatedWorkspace> ephemeralFactory() {
        return () -> new EphemeralDirectoryWorkspace("coding-skill-gen-");
    }

    private static void writeSkillMd(Path root, String content) {
        try {
            Files.writeString(root.resolve(CodingAgentSkillGenerator.SKILL_FILE_NAME), content);
        } catch (IOException e) {
            fail("failed to write SKILL.md in test setup: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("generate(): happy path")
    class GenerateHappy {

        @Test
        @DisplayName("agent writes valid SKILL.md → returns parsed GeneratedSkill")
        void parsesValidSkill() {
            SubagentSpawner spawner = fakeSpawner(SubagentStatus.COMPLETED,
                    root -> writeSkillMd(root, VALID_SKILL_MD));
            CodingAgentSkillGenerator gen = new CodingAgentSkillGenerator(
                    spawner, ephemeralFactory(), Duration.ofSeconds(5));

            GeneratedSkill skill = gen.generate("Need a doubler skill", List.of("calculator"));

            assertNotNull(skill, "generator should return a parsed skill");
            assertEquals("test_skill", skill.getName());
            assertEquals(SkillType.CODE, skill.getDefinition().getType());
            assertTrue(skill.getDefinition().getCode().contains("result="));
            assertFalse(skill.getDefinition().getIntegrationTests().isEmpty(),
                    "integration tests should be parsed from the SKILL.md");
        }

        @Test
        @DisplayName("user prompt mentions the gap and existing tools")
        void promptReachesAgent() {
            AtomicReference<String> capturedPrompt = new AtomicReference<>();
            SubagentSpawner capturing = spec -> {
                capturedPrompt.set(spec.userPrompt());
                IsolatedWorkspace ws = spec.workspaceFactory().get();
                writeSkillMd(ws.root(), VALID_SKILL_MD);
                return new FakeSubagent(spec.id(), SubagentStatus.COMPLETED);
            };
            CodingAgentSkillGenerator gen = new CodingAgentSkillGenerator(
                    capturing, ephemeralFactory(), Duration.ofSeconds(5));

            gen.generate("Reverse a string", List.of("text_util", "logger"));

            String prompt = capturedPrompt.get();
            assertNotNull(prompt);
            assertTrue(prompt.contains("Reverse a string"), "prompt should mention the gap");
            assertTrue(prompt.contains("text_util"), "prompt should list existing tools");
            assertTrue(prompt.contains("logger"));
            assertTrue(prompt.contains(CodingAgentSkillGenerator.SKILL_FILE_NAME),
                    "prompt should tell the agent the output filename");
        }

        @Test
        @DisplayName("workspace is reachable during the agent run")
        void workspaceIsLiveDuringAgentRun() {
            AtomicBoolean directoryExisted = new AtomicBoolean(false);
            SubagentSpawner spawner = fakeSpawner(SubagentStatus.COMPLETED, root -> {
                directoryExisted.set(Files.isDirectory(root));
                writeSkillMd(root, VALID_SKILL_MD);
            });
            CodingAgentSkillGenerator gen = new CodingAgentSkillGenerator(
                    spawner, ephemeralFactory(), Duration.ofSeconds(5));

            gen.generate("trivial gap", List.of());

            assertTrue(directoryExisted.get(),
                    "workspace root should be a real directory while the agent runs");
        }
    }

    @Nested
    @DisplayName("generate(): failure paths return null")
    class GenerateFailures {

        @Test
        @DisplayName("agent fails → null")
        void agentFailureReturnsNull() {
            SubagentSpawner spawner = fakeSpawner(SubagentStatus.FAILED, root -> { /* nothing */ });
            CodingAgentSkillGenerator gen = new CodingAgentSkillGenerator(
                    spawner, ephemeralFactory(), Duration.ofSeconds(5));

            assertNull(gen.generate("any gap", List.of()));
        }

        @Test
        @DisplayName("agent times out → null")
        void agentTimeoutReturnsNull() {
            SubagentSpawner spawner = fakeSpawner(SubagentStatus.TIMED_OUT, root -> { /* nothing */ });
            CodingAgentSkillGenerator gen = new CodingAgentSkillGenerator(
                    spawner, ephemeralFactory(), Duration.ofSeconds(5));

            assertNull(gen.generate("any gap", List.of()));
        }

        @Test
        @DisplayName("agent succeeds but no SKILL.md → null")
        void noSkillFileReturnsNull() {
            SubagentSpawner spawner = fakeSpawner(SubagentStatus.COMPLETED, root -> {
                // Pretend the agent ran but didn't write SKILL.md
            });
            CodingAgentSkillGenerator gen = new CodingAgentSkillGenerator(
                    spawner, ephemeralFactory(), Duration.ofSeconds(5));

            assertNull(gen.generate("any gap", List.of()));
        }

        @Test
        @DisplayName("agent writes empty SKILL.md → null")
        void emptySkillFileReturnsNull() {
            SubagentSpawner spawner = fakeSpawner(SubagentStatus.COMPLETED, root -> {
                writeSkillMd(root, "");
            });
            CodingAgentSkillGenerator gen = new CodingAgentSkillGenerator(
                    spawner, ephemeralFactory(), Duration.ofSeconds(5));

            assertNull(gen.generate("any gap", List.of()));
        }

        @Test
        @DisplayName("spawner throws → null (does not propagate)")
        void spawnerThrowsReturnsNull() {
            SubagentSpawner explosive = spec -> { throw new RuntimeException("CLI not found"); };
            CodingAgentSkillGenerator gen = new CodingAgentSkillGenerator(
                    explosive, ephemeralFactory(), Duration.ofSeconds(5));

            assertNull(gen.generate("any gap", List.of()));
        }
    }

    @Nested
    @DisplayName("workspace lifecycle")
    class WorkspaceLifecycle {

        @Test
        @DisplayName("happy path: workspace closed after generator returns")
        void workspaceClosedAfterReturn() {
            AtomicReference<IsolatedWorkspace> captured = new AtomicReference<>();
            java.util.function.Supplier<IsolatedWorkspace> capturingFactory = () -> {
                IsolatedWorkspace ws = new EphemeralDirectoryWorkspace("coding-skill-gen-close-");
                captured.set(ws);
                return ws;
            };

            SubagentSpawner spawner = fakeSpawner(SubagentStatus.COMPLETED,
                    root -> writeSkillMd(root, VALID_SKILL_MD));
            CodingAgentSkillGenerator gen = new CodingAgentSkillGenerator(
                    spawner, capturingFactory, Duration.ofSeconds(5));

            GeneratedSkill skill = gen.generate("test gap", List.of());

            assertNotNull(skill);
            assertNotNull(captured.get());
            assertFalse(captured.get().isOpen(), "workspace should be closed after generate() returns");
        }

        @Test
        @DisplayName("failure path: workspace still closed")
        void workspaceClosedAfterFailure() {
            AtomicReference<IsolatedWorkspace> captured = new AtomicReference<>();
            java.util.function.Supplier<IsolatedWorkspace> capturingFactory = () -> {
                IsolatedWorkspace ws = new EphemeralDirectoryWorkspace("coding-skill-gen-fail-");
                captured.set(ws);
                return ws;
            };

            SubagentSpawner spawner = fakeSpawner(SubagentStatus.FAILED, root -> { /* nothing */ });
            CodingAgentSkillGenerator gen = new CodingAgentSkillGenerator(
                    spawner, capturingFactory, Duration.ofSeconds(5));

            GeneratedSkill skill = gen.generate("test gap", List.of());

            assertNull(skill);
            assertNotNull(captured.get());
            assertFalse(captured.get().isOpen(), "workspace should be closed even when agent fails");
        }
    }

    @Nested
    @DisplayName("refine()")
    class Refine {

        private GeneratedSkill failedSkill() {
            SkillDefinition def = new SkillDefinition();
            def.setName("broken_skill");
            def.setDescription("a previously-generated skill that failed validation");
            def.setType(SkillType.CODE);
            def.setCode("syntax error here");
            GeneratedSkill skill = new GeneratedSkill(def);
            skill.setVersion("1.0.0");
            return skill;
        }

        @Test
        @DisplayName("agent writes fixed SKILL.md → returns refined skill with bumped version")
        void refinesAndBumpsPatch() {
            SubagentSpawner spawner = fakeSpawner(SubagentStatus.COMPLETED,
                    root -> writeSkillMd(root, VALID_SKILL_MD));
            CodingAgentSkillGenerator gen = new CodingAgentSkillGenerator(
                    spawner, ephemeralFactory(), Duration.ofSeconds(5));

            GeneratedSkill failed = failedSkill();
            GeneratedSkill refined = gen.refine(failed, "code does not compile: unexpected token 'error'");

            assertNotNull(refined, "refine() should return a parsed skill");
            assertEquals("1.0.1", refined.getVersion(), "patch version should be bumped");
        }

        @Test
        @DisplayName("refine prompt includes existing skill content and validation errors")
        void refinePromptIncludesContext() {
            AtomicReference<String> capturedPrompt = new AtomicReference<>();
            SubagentSpawner capturing = spec -> {
                capturedPrompt.set(spec.userPrompt());
                IsolatedWorkspace ws = spec.workspaceFactory().get();
                writeSkillMd(ws.root(), VALID_SKILL_MD);
                return new FakeSubagent(spec.id(), SubagentStatus.COMPLETED);
            };
            CodingAgentSkillGenerator gen = new CodingAgentSkillGenerator(
                    capturing, ephemeralFactory(), Duration.ofSeconds(5));

            gen.refine(failedSkill(), "missing integration tests");

            String prompt = capturedPrompt.get();
            assertNotNull(prompt);
            assertTrue(prompt.contains("broken_skill"), "prompt should include the broken skill");
            assertTrue(prompt.contains("missing integration tests"),
                    "prompt should include the validation errors");
        }

        @Test
        @DisplayName("refine: agent fails → null (no version bump on null)")
        void refineFailureReturnsNull() {
            SubagentSpawner spawner = fakeSpawner(SubagentStatus.FAILED, root -> { /* nothing */ });
            CodingAgentSkillGenerator gen = new CodingAgentSkillGenerator(
                    spawner, ephemeralFactory(), Duration.ofSeconds(5));

            assertNull(gen.refine(failedSkill(), "some validation error"));
        }
    }
}
