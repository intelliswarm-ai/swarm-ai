package ai.intelliswarm.swarmai.agent.subagent.coding;

import ai.intelliswarm.swarmai.agent.subagent.EphemeralDirectoryWorkspace;
import ai.intelliswarm.swarmai.agent.subagent.IsolatedWorkspace;
import ai.intelliswarm.swarmai.agent.subagent.Subagent;
import ai.intelliswarm.swarmai.agent.subagent.SubagentResult;
import ai.intelliswarm.swarmai.agent.subagent.SubagentSpec;
import ai.intelliswarm.swarmai.agent.subagent.SubagentStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CodingAgentSubagentSpawner} + {@link ProcessSubagent} +
 * {@link CodexSubagentSpawner}. Uses {@code sh -c} as a fake CLI so the
 * tests don't depend on Codex or any other coding agent being installed.
 *
 * <p>Disabled on Windows because the test fixtures use POSIX shell idioms;
 * the production code is cross-platform.
 */
@DisabledOnOs(OS.WINDOWS)
class CodingAgentSubagentSpawnerTest {

    private ExecutorService executor;
    private Supplier<IsolatedWorkspace> wsFactory;

    @BeforeEach
    void setUp() {
        executor = Executors.newCachedThreadPool();
        wsFactory = () -> {
            try {
                return new EphemeralDirectoryWorkspace();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    /**
     * Build a spawner whose "CLI" is {@code sh} with {@code -c} as the only
     * base arg. Combined with {@link PromptInjection#ARG}, the spec's
     * userPrompt becomes the shell command — a deterministic stand-in for a
     * real coding agent.
     */
    private CodingAgentSubagentSpawner shSpawner() {
        return shSpawner(simpleParser());
    }

    private CodingAgentSubagentSpawner shSpawner(SubagentOutputParser parser) {
        return CodingAgentSubagentSpawner.builder()
                .cliCommand("sh")
                .baseArgs("-c")
                .promptInjection(PromptInjection.ARG)
                .executor(executor)
                .defaultWorkspaceFactory(wsFactory)
                .outputParser(parser)
                .defaultTimeout(Duration.ofSeconds(10))
                .build();
    }

    private static SubagentOutputParser simpleParser() {
        return (id, out) -> out.ok()
                ? SubagentResult.success(id, out.stdout(), Map.of("exitCode", out.exitCode()))
                : SubagentResult.failure(id, "exit=" + out.exitCode() + " stderr=" + out.stderr().trim(),
                        Map.of("exitCode", out.exitCode()));
    }

    private static SubagentSpec specFor(String id, String shellCommand, Duration timeout) {
        return SubagentSpec.builder()
                .id(id)
                .userPrompt(shellCommand)
                .timeout(timeout)
                .build();
    }

    // =================================================================
    // Happy path
    // =================================================================
    @Nested
    @DisplayName("happy path")
    class Happy {

        @Test
        void simpleEchoSucceeds() throws Exception {
            CodingAgentSubagentSpawner spawner = shSpawner();
            SubagentSpec spec = specFor("t1", "echo hello-from-fake-cli", Duration.ofSeconds(5));

            Subagent agent = spawner.spawn(spec);
            SubagentResult result = agent.awaitResult(Duration.ofSeconds(5));

            assertEquals(SubagentStatus.COMPLETED, result.status());
            assertTrue(result.output().contains("hello-from-fake-cli"));
            assertEquals(0, result.metrics().get("exitCode"));
        }

        @Test
        void workingDirectoryIsTheWorkspace() throws Exception {
            // Have the "CLI" print pwd; verify it matches the workspace root.
            AtomicReference<IsolatedWorkspace> capturedWs = new AtomicReference<>();
            Supplier<IsolatedWorkspace> capturingFactory = () -> {
                try {
                    IsolatedWorkspace ws = new EphemeralDirectoryWorkspace();
                    capturedWs.set(ws);
                    return ws;
                } catch (Exception e) { throw new RuntimeException(e); }
            };

            CodingAgentSubagentSpawner spawner = CodingAgentSubagentSpawner.builder()
                    .cliCommand("sh")
                    .baseArgs("-c")
                    .promptInjection(PromptInjection.ARG)
                    .executor(executor)
                    .defaultWorkspaceFactory(capturingFactory)
                    .outputParser(simpleParser())
                    .defaultTimeout(Duration.ofSeconds(5))
                    .build();

            SubagentSpec spec = specFor("t-pwd", "pwd", Duration.ofSeconds(5));
            Subagent agent = spawner.spawn(spec);
            SubagentResult result = agent.awaitResult(Duration.ofSeconds(5));

            assertEquals(SubagentStatus.COMPLETED, result.status());
            String wsRoot = capturedWs.get().root().toAbsolutePath().toString();
            // pwd output may or may not have trailing newline; trim
            assertEquals(wsRoot, result.output().trim(),
                    "process working dir should be the workspace root");
        }

        @Test
        void envVariablesPropagate() throws Exception {
            CodingAgentSubagentSpawner spawner = CodingAgentSubagentSpawner.builder()
                    .cliCommand("sh")
                    .baseArgs("-c")
                    .promptInjection(PromptInjection.ARG)
                    .executor(executor)
                    .defaultWorkspaceFactory(wsFactory)
                    .outputParser(simpleParser())
                    .defaultTimeout(Duration.ofSeconds(5))
                    .env("MY_VAR", "the-value")
                    .build();

            SubagentSpec spec = specFor("t-env", "echo $MY_VAR", Duration.ofSeconds(5));
            Subagent agent = spawner.spawn(spec);
            SubagentResult result = agent.awaitResult(Duration.ofSeconds(5));

            assertEquals(SubagentStatus.COMPLETED, result.status());
            assertEquals("the-value", result.output().trim());
        }
    }

    // =================================================================
    // Failure surface
    // =================================================================
    @Nested
    @DisplayName("failure surface")
    class Failures {

        @Test
        void nonZeroExitMapsToFailure() throws Exception {
            CodingAgentSubagentSpawner spawner = shSpawner();
            SubagentSpec spec = specFor("t-fail", "echo problem >&2; exit 7", Duration.ofSeconds(5));

            Subagent agent = spawner.spawn(spec);
            SubagentResult result = agent.awaitResult(Duration.ofSeconds(5));

            assertEquals(SubagentStatus.FAILED, result.status());
            assertTrue(result.output().contains("problem"),
                    "stderr should be in the failure message: " + result.output());
            assertEquals(7, result.metrics().get("exitCode"));
        }

        @Test
        void parserExceptionsBecomeFailures() throws Exception {
            SubagentOutputParser broken = (id, out) -> {
                throw new RuntimeException("parser blew up");
            };
            CodingAgentSubagentSpawner spawner = shSpawner(broken);
            SubagentSpec spec = specFor("t-parser", "echo ok", Duration.ofSeconds(5));

            Subagent agent = spawner.spawn(spec);
            SubagentResult result = agent.awaitResult(Duration.ofSeconds(5));

            assertEquals(SubagentStatus.FAILED, result.status());
            assertTrue(result.output().contains("parser blew up"),
                    "parser exception should be surfaced: " + result.output());
        }

        @Test
        void cliBinaryNotFoundReturnsFailedStub() throws Exception {
            CodingAgentSubagentSpawner spawner = CodingAgentSubagentSpawner.builder()
                    .cliCommand("/nonexistent/binary-that-does-not-exist")
                    .baseArgs(List.of())
                    .promptInjection(PromptInjection.ARG)
                    .executor(executor)
                    .defaultWorkspaceFactory(wsFactory)
                    .outputParser(simpleParser())
                    .defaultTimeout(Duration.ofSeconds(5))
                    .build();

            SubagentSpec spec = specFor("t-missing", "irrelevant", Duration.ofSeconds(5));
            // spawn() never throws — failed-stub subagent is returned
            Subagent agent = spawner.spawn(spec);
            assertEquals(SubagentStatus.FAILED, agent.status());
            SubagentResult result = agent.awaitResult(Duration.ofSeconds(2));
            assertEquals(SubagentStatus.FAILED, result.status());
            assertTrue(result.output().toLowerCase().contains("failed to start"),
                    "expected 'failed to start' in output: " + result.output());
        }
    }

    // =================================================================
    // Timeout + cancel
    // =================================================================
    @Nested
    @DisplayName("timeout + cancel")
    class TimeoutCancel {

        @Test
        void specTimeoutKillsLongRunningProcess() throws Exception {
            CodingAgentSubagentSpawner spawner = shSpawner();
            SubagentSpec spec = specFor("t-slow", "sleep 30", Duration.ofMillis(500));

            long t0 = System.currentTimeMillis();
            Subagent agent = spawner.spawn(spec);
            SubagentResult result = agent.awaitResult(Duration.ofSeconds(5));
            long elapsed = System.currentTimeMillis() - t0;

            assertEquals(SubagentStatus.TIMED_OUT, result.status());
            assertTrue(elapsed < 5000, "timeout should fire well before sleep completes; took " + elapsed + "ms");
            assertEquals(500L, result.metrics().get("timeoutMs"));
        }

        @Test
        void cancelStopsRunningProcess() throws Exception {
            CodingAgentSubagentSpawner spawner = shSpawner();
            SubagentSpec spec = specFor("t-cancel", "sleep 30", Duration.ofSeconds(60));

            Subagent agent = spawner.spawn(spec);
            // Give the process a moment to start
            Thread.sleep(150);
            assertEquals(SubagentStatus.RUNNING, agent.status());

            agent.cancel();
            SubagentResult result = agent.awaitResult(Duration.ofSeconds(5));
            assertEquals(SubagentStatus.CANCELLED, result.status());
        }

        @Test
        void cancelOnTerminalSubagentIsNoOp() throws Exception {
            CodingAgentSubagentSpawner spawner = shSpawner();
            SubagentSpec spec = specFor("t-fast", "echo done", Duration.ofSeconds(5));
            Subagent agent = spawner.spawn(spec);
            agent.awaitResult(Duration.ofSeconds(5));
            assertEquals(SubagentStatus.COMPLETED, agent.status());
            assertDoesNotThrow(agent::cancel);
            assertEquals(SubagentStatus.COMPLETED, agent.status());
        }
    }

    // =================================================================
    // Workspace lifecycle
    // =================================================================
    @Nested
    @DisplayName("workspace lifecycle")
    class WorkspaceLifecycle {

        @Test
        void workspaceClosedAfterSuccessfulCompletion() throws Exception {
            AtomicReference<IsolatedWorkspace> captured = new AtomicReference<>();
            Supplier<IsolatedWorkspace> capturing = () -> {
                try {
                    IsolatedWorkspace ws = new EphemeralDirectoryWorkspace();
                    captured.set(ws);
                    return ws;
                } catch (Exception e) { throw new RuntimeException(e); }
            };
            CodingAgentSubagentSpawner spawner = CodingAgentSubagentSpawner.builder()
                    .cliCommand("sh").baseArgs("-c").promptInjection(PromptInjection.ARG)
                    .executor(executor).defaultWorkspaceFactory(capturing).outputParser(simpleParser())
                    .defaultTimeout(Duration.ofSeconds(5)).build();

            SubagentSpec spec = specFor("t-wsl", "echo done", Duration.ofSeconds(5));
            Subagent agent = spawner.spawn(spec);
            agent.awaitResult(Duration.ofSeconds(5));
            // Race-fix invariant: workspace.close() ran BEFORE the result was delivered.
            assertFalse(captured.get().isOpen(),
                    "workspace must be closed before the result is delivered");
        }

        @Test
        void workspaceClosedAfterTimeout() throws Exception {
            AtomicReference<IsolatedWorkspace> captured = new AtomicReference<>();
            Supplier<IsolatedWorkspace> capturing = () -> {
                try {
                    IsolatedWorkspace ws = new EphemeralDirectoryWorkspace();
                    captured.set(ws);
                    return ws;
                } catch (Exception e) { throw new RuntimeException(e); }
            };
            CodingAgentSubagentSpawner spawner = CodingAgentSubagentSpawner.builder()
                    .cliCommand("sh").baseArgs("-c").promptInjection(PromptInjection.ARG)
                    .executor(executor).defaultWorkspaceFactory(capturing).outputParser(simpleParser())
                    .defaultTimeout(Duration.ofMillis(500)).build();

            SubagentSpec spec = specFor("t-tow", "sleep 30", Duration.ofMillis(500));
            Subagent agent = spawner.spawn(spec);
            agent.awaitResult(Duration.ofSeconds(5));
            assertFalse(captured.get().isOpen(),
                    "workspace must be closed even on timeout");
        }

        @Test
        void specCanProvideItsOwnWorkspaceFactory() throws Exception {
            AtomicReference<IsolatedWorkspace> capturedSpec = new AtomicReference<>();
            Supplier<IsolatedWorkspace> specFactory = () -> {
                try {
                    IsolatedWorkspace ws = new EphemeralDirectoryWorkspace();
                    capturedSpec.set(ws);
                    return ws;
                } catch (Exception e) { throw new RuntimeException(e); }
            };
            // Spawner has a different default; the per-spec factory should win.
            AtomicReference<IsolatedWorkspace> capturedDefault = new AtomicReference<>();
            Supplier<IsolatedWorkspace> defaultFactory = () -> {
                try {
                    IsolatedWorkspace ws = new EphemeralDirectoryWorkspace();
                    capturedDefault.set(ws);
                    return ws;
                } catch (Exception e) { throw new RuntimeException(e); }
            };
            CodingAgentSubagentSpawner spawner = CodingAgentSubagentSpawner.builder()
                    .cliCommand("sh").baseArgs("-c").promptInjection(PromptInjection.ARG)
                    .executor(executor).defaultWorkspaceFactory(defaultFactory).outputParser(simpleParser())
                    .defaultTimeout(Duration.ofSeconds(5)).build();

            SubagentSpec spec = SubagentSpec.builder()
                    .id("t-spec-ws")
                    .userPrompt("echo done")
                    .timeout(Duration.ofSeconds(5))
                    .workspace(specFactory)
                    .build();

            Subagent agent = spawner.spawn(spec);
            agent.awaitResult(Duration.ofSeconds(5));
            assertNotNull(capturedSpec.get(), "spec's factory should have been used");
            assertNull(capturedDefault.get(), "default factory should NOT have been used");
        }
    }

    // =================================================================
    // STDIN injection
    // =================================================================
    @Nested
    @DisplayName("STDIN injection")
    class StdinInjection {

        @Test
        void promptWrittenToStdinIsReadByCli() throws Exception {
            // Use `cat` as the fake CLI: with stdin injection, cat echoes whatever we write.
            CodingAgentSubagentSpawner spawner = CodingAgentSubagentSpawner.builder()
                    .cliCommand("cat")
                    .baseArgs(List.of())
                    .promptInjection(PromptInjection.STDIN)
                    .executor(executor).defaultWorkspaceFactory(wsFactory).outputParser(simpleParser())
                    .defaultTimeout(Duration.ofSeconds(5)).build();

            SubagentSpec spec = specFor("t-stdin", "the prompt content", Duration.ofSeconds(5));
            Subagent agent = spawner.spawn(spec);
            SubagentResult result = agent.awaitResult(Duration.ofSeconds(5));

            assertEquals(SubagentStatus.COMPLETED, result.status());
            assertEquals("the prompt content", result.output(),
                    "cat should echo whatever we wrote to stdin");
        }
    }

    // =================================================================
    // CodexSubagentSpawner factory
    // =================================================================
    @Nested
    @DisplayName("CodexSubagentSpawner factory")
    class CodexFactory {

        @Test
        void defaultsAreApplied() {
            CodingAgentSubagentSpawner spawner = CodexSubagentSpawner.create(
                    executor,
                    wsFactory);
            assertEquals(CodexSubagentSpawner.DEFAULT_CLI, spawner.cliCommand());
            assertEquals(CodexSubagentSpawner.DEFAULT_ARGS, spawner.baseArgs());
            assertEquals(PromptInjection.ARG, spawner.promptInjection());
        }

        @Test
        void builderAllowsFlagOverrides() {
            CodingAgentSubagentSpawner spawner = CodexSubagentSpawner.builder()
                    .baseArgs("exec", "--my-flag")     // override defaults
                    .executor(executor)
                    .defaultWorkspaceFactory(wsFactory)
                    .build();
            assertEquals("codex", spawner.cliCommand());
            assertEquals(List.of("exec", "--my-flag"), spawner.baseArgs());
        }

        @Test
        void parserMapsExitZeroToSuccess() {
            SubagentOutputParser parser = CodexSubagentSpawner.defaultParser();
            ProcessOutput ok = new ProcessOutput(0, "agent reply", "info logs", Duration.ofSeconds(2));
            SubagentResult r = parser.parse("id-1", ok);
            assertEquals(SubagentStatus.COMPLETED, r.status());
            assertEquals("agent reply", r.output());
            assertEquals(0, r.metrics().get("exitCode"));
            assertEquals(2000L, r.metrics().get("elapsedMs"));
        }

        @Test
        void parserMapsNonZeroExitToFailure() {
            SubagentOutputParser parser = CodexSubagentSpawner.defaultParser();
            ProcessOutput bad = new ProcessOutput(2, "", "auth failed",
                    Duration.ofSeconds(1));
            SubagentResult r = parser.parse("id-2", bad);
            assertEquals(SubagentStatus.FAILED, r.status());
            assertTrue(r.output().contains("auth failed"));
            assertEquals(2, r.metrics().get("exitCode"));
        }
    }

    // =================================================================
    // Regression tests for the three review findings:
    // 1) Stream readers must NOT depend on the caller's executor capacity.
    // 2) Cross-platform stdin redirection (no /dev/null path).
    // (Fix 3 — patch text including untracked files — is in
    //  GitWorktreeWorkspaceTest, since it lives in a different file.)
    // =================================================================
    @Nested
    @DisplayName("regression: review findings")
    class RegressionReviewFindings {

        @Test
        @DisplayName("single-thread executor still produces stdout/stderr (readers run on virtual threads)")
        void singleThreadExecutor_doesNotStarveStreamReaders() {
            // Drive everything through a single-thread executor. Before the
            // fix, this deadlocked: driveProcess took the only thread and
            // the stream readers never started, so waitFor timed out and
            // readSafely returned empty stdout/stderr.
            ExecutorService single = Executors.newSingleThreadExecutor();
            try {
                CodingAgentSubagentSpawner spawner = CodingAgentSubagentSpawner.builder()
                        .cliCommand("sh")
                        .baseArgs("-c")
                        .promptInjection(PromptInjection.ARG)
                        .executor(single)
                        .defaultWorkspaceFactory(wsFactory)
                        .outputParser((id, out) -> SubagentResult.success(id, out.stdout(),
                                Map.of("stderr", out.stderr(), "exitCode", out.exitCode())))
                        .build();

                SubagentSpec spec = SubagentSpec.builder()
                        .id("single-exec-test")
                        .userPrompt("printf 'OUT'; printf 'ERR' 1>&2; exit 0")
                        .timeout(Duration.ofSeconds(5))
                        .build();

                Subagent agent = spawner.spawn(spec);
                SubagentResult r = agent.awaitResult(Duration.ofSeconds(10));

                assertEquals(SubagentStatus.COMPLETED, r.status(),
                        "single-thread executor must not deadlock the readers");
                assertEquals("OUT", r.output(),
                        "stdout must be captured even when the executor only has one thread");
                assertEquals("ERR", r.metrics().get("stderr"),
                        "stderr must also be captured (independent reader)");
            } finally {
                single.shutdownNow();
            }
        }

        @Test
        @DisplayName("ARG injection works without /dev/null (closed PIPE substitutes)")
        void argInjection_doesNotDependOnDevNull() {
            // Before the fix, pb.redirectInput(Redirect.from(new File("/dev/null")))
            // failed on Windows because the path doesn't exist there. The new
            // path is a closed PIPE, which works on every OS. We can't simulate
            // Windows from this Linux test, but we can verify the spawner does
            // not reach for any host-specific file path: a successful run that
            // reads stdin and gets EOF immediately is the proof.
            CodingAgentSubagentSpawner spawner = CodingAgentSubagentSpawner.builder()
                    .cliCommand("sh")
                    .baseArgs("-c")
                    .promptInjection(PromptInjection.ARG)
                    .executor(executor)
                    .defaultWorkspaceFactory(wsFactory)
                    .outputParser((id, out) -> SubagentResult.success(id, out.stdout(),
                            Map.of("exitCode", out.exitCode())))
                    .build();

            SubagentSpec spec = SubagentSpec.builder()
                    .id("stdin-eof-test")
                    // `cat <&0` echoes whatever stdin holds. With closed pipe,
                    // it reads zero bytes and prints empty + exits 0.
                    .userPrompt("cat <&0; echo done")
                    .timeout(Duration.ofSeconds(5))
                    .build();

            Subagent agent = spawner.spawn(spec);
            SubagentResult r = agent.awaitResult(Duration.ofSeconds(10));

            assertEquals(SubagentStatus.COMPLETED, r.status(),
                    "ARG injection must complete with closed-stdin pipe (no /dev/null)");
            assertEquals("done\n", r.output(),
                    "child should see EOF on first read of stdin and produce only 'done'");
        }
    }
}
