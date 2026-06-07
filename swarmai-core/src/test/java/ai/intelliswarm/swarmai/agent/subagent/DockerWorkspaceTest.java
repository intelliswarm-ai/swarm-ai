package ai.intelliswarm.swarmai.agent.subagent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests for {@link DockerWorkspace}. Auto-skip via JUnit assumptions when
 * Docker isn't installed/available — that way the suite stays green on CI
 * hosts without Docker, while still exercising the integration where Docker
 * is present.
 *
 * <p>Disabled on Windows because the bind-mount path semantics differ enough
 * to warrant a separate Windows test pass; the production code is
 * cross-platform but the assertions here use POSIX path conventions.
 */
@DisabledOnOs(OS.WINDOWS)
class DockerWorkspaceTest {

    /** Tiny image with sh + standard coreutils — ubiquitous on dev/CI hosts. */
    private static final String TEST_IMAGE = "alpine:3.19";

    @TempDir
    Path tempDir;

    @BeforeEach
    void requireDocker() {
        assumeTrue(DockerWorkspace.isDockerAvailable(),
                "Docker is not available — skipping DockerWorkspace tests");
    }

    // =================================================================
    // BindMount + ResourceLimits records — pure validation, no daemon
    // =================================================================
    @Nested
    @DisplayName("input records")
    class Records {

        @Test
        void bindMountRequiresAbsolutePath() {
            Path rel = Paths.get("relative/path");
            assertThrows(IllegalArgumentException.class,
                    () -> new DockerWorkspace.BindMount(rel, "/workspace", false));
        }

        @Test
        void bindMountRejectsBlankContainerPath() {
            Path abs = Paths.get("/tmp/foo").toAbsolutePath();
            assertThrows(IllegalArgumentException.class,
                    () -> new DockerWorkspace.BindMount(abs, "  ", false));
        }

        @Test
        void bindMountFactories() {
            Path host = Paths.get("/tmp/foo").toAbsolutePath();
            assertFalse(DockerWorkspace.BindMount.readWrite(host, "/c").readOnly());
            assertTrue(DockerWorkspace.BindMount.readOnly(host, "/c").readOnly());
        }

        @Test
        void resourceLimitsUnrestricted() {
            DockerWorkspace.ResourceLimits l = DockerWorkspace.ResourceLimits.unrestricted();
            assertNull(l.memory());
            assertNull(l.cpus());
            assertNull(l.pidsLimit());
            assertNull(l.networkMode());
        }

        @Test
        void builderRejectsBlankImage() {
            assertThrows(IllegalArgumentException.class,
                    () -> DockerWorkspace.builder("  "));
        }
    }

    // =================================================================
    // Lifecycle: container starts + cleans up
    // =================================================================
    @Nested
    @DisplayName("lifecycle")
    class Lifecycle {

        @Test
        void containerStartsAndStops() throws IOException {
            String containerName;
            try (DockerWorkspace ws = DockerWorkspace.builder(TEST_IMAGE)
                    .bindMount(tempDir, "/workspace")
                    .workdir("/workspace")
                    .networkMode("none")
                    .build()) {
                containerName = ws.containerName();
                assertTrue(ws.isOpen());
                assertEquals(tempDir.toAbsolutePath(), ws.root());
                // Container shows up in `docker ps`
                assertTrue(containerExists(containerName), "container should be running");
            }
            // After close: container should be gone
            assertFalse(containerExists(containerName), "container should be removed after close");
        }

        @Test
        void closeIsIdempotent() throws IOException {
            DockerWorkspace ws = DockerWorkspace.builder(TEST_IMAGE)
                    .bindMount(tempDir, "/workspace")
                    .build();
            ws.close();
            assertFalse(ws.isOpen());
            assertDoesNotThrow(ws::close);
        }

        @Test
        void rootRequiresAtLeastOneBindMount() throws IOException {
            // Builder allows zero mounts — they're not strictly required for the container.
            // But root() should throw because there's nothing to point at on the host.
            try (DockerWorkspace ws = DockerWorkspace.builder(TEST_IMAGE).build()) {
                assertThrows(IllegalStateException.class, ws::root);
            }
        }

        @Test
        void execAfterCloseFails() throws IOException {
            DockerWorkspace ws = DockerWorkspace.builder(TEST_IMAGE)
                    .bindMount(tempDir, "/workspace")
                    .build();
            ws.close();
            assertThrows(IllegalStateException.class,
                    () -> ws.exec(List.of("echo", "hi"), Duration.ofSeconds(5)));
        }
    }

    // =================================================================
    // exec(): commands inside the container
    // =================================================================
    @Nested
    @DisplayName("exec()")
    class Exec {

        @Test
        void echoReturnsStdout() throws IOException {
            try (DockerWorkspace ws = DockerWorkspace.builder(TEST_IMAGE)
                    .bindMount(tempDir, "/workspace")
                    .workdir("/workspace")
                    .build()) {
                DockerWorkspace.ExecResult r = ws.exec(
                        List.of("echo", "hello-from-container"),
                        Duration.ofSeconds(10));
                assertTrue(r.ok(), "expected exit 0; got " + r.exitCode() + ", stderr: " + r.stderr());
                assertEquals("hello-from-container\n", r.stdout());
            }
        }

        @Test
        void shellCommandConvenience() throws IOException {
            try (DockerWorkspace ws = DockerWorkspace.builder(TEST_IMAGE)
                    .bindMount(tempDir, "/workspace")
                    .build()) {
                DockerWorkspace.ExecResult r = ws.exec(
                        "echo $((40 + 2))", Duration.ofSeconds(10));
                assertTrue(r.ok());
                assertEquals("42\n", r.stdout());
            }
        }

        @Test
        void nonZeroExitCaptured() throws IOException {
            try (DockerWorkspace ws = DockerWorkspace.builder(TEST_IMAGE)
                    .bindMount(tempDir, "/workspace")
                    .build()) {
                DockerWorkspace.ExecResult r = ws.exec(
                        List.of("sh", "-c", "echo oops 1>&2; exit 7"),
                        Duration.ofSeconds(10));
                assertFalse(r.ok());
                assertEquals(7, r.exitCode());
                assertTrue(r.stderr().contains("oops"), "stderr captured: " + r.stderr());
            }
        }

        @Test
        void timeoutKillsExec() throws IOException {
            try (DockerWorkspace ws = DockerWorkspace.builder(TEST_IMAGE)
                    .bindMount(tempDir, "/workspace")
                    .build()) {
                long t0 = System.currentTimeMillis();
                IOException thrown = assertThrows(IOException.class,
                        () -> ws.exec(List.of("sh", "-c", "sleep 30"),
                                Duration.ofMillis(500)));
                long elapsed = System.currentTimeMillis() - t0;
                assertTrue(thrown.getMessage().contains("timed out"));
                assertTrue(elapsed < 5000, "timeout should fire within ~500ms; took " + elapsed + "ms");
            }
        }

        @Test
        void envVariablesPropagate() throws IOException {
            try (DockerWorkspace ws = DockerWorkspace.builder(TEST_IMAGE)
                    .bindMount(tempDir, "/workspace")
                    .env("MY_KEY", "my-value")
                    .build()) {
                DockerWorkspace.ExecResult r = ws.exec(
                        "echo $MY_KEY", Duration.ofSeconds(10));
                assertTrue(r.ok());
                assertEquals("my-value\n", r.stdout());
            }
        }
    }

    // =================================================================
    // Bind mount: writes inside the container visible on host (and vice-versa)
    // =================================================================
    @Nested
    @DisplayName("bind mount")
    class BindMounts {

        @Test
        void containerWritesVisibleOnHost() throws IOException {
            try (DockerWorkspace ws = DockerWorkspace.builder(TEST_IMAGE)
                    .bindMount(tempDir, "/workspace")
                    .workdir("/workspace")
                    .build()) {
                DockerWorkspace.ExecResult r = ws.exec(
                        "echo created-by-container > /workspace/from-container.txt",
                        Duration.ofSeconds(10));
                assertTrue(r.ok());
            }
            Path file = tempDir.resolve("from-container.txt");
            assertTrue(Files.exists(file), "container's write should be visible on host");
            assertEquals("created-by-container\n",
                    Files.readString(file, StandardCharsets.UTF_8));
        }

        @Test
        void hostFilesVisibleInContainer() throws IOException {
            Files.writeString(tempDir.resolve("from-host.txt"),
                    "from host\n", StandardCharsets.UTF_8);
            try (DockerWorkspace ws = DockerWorkspace.builder(TEST_IMAGE)
                    .bindMount(tempDir, "/workspace")
                    .workdir("/workspace")
                    .build()) {
                DockerWorkspace.ExecResult r = ws.exec(
                        "cat /workspace/from-host.txt", Duration.ofSeconds(10));
                assertTrue(r.ok());
                assertEquals("from host\n", r.stdout());
            }
        }

        @Test
        void readOnlyMountRejectsWrites() throws IOException {
            Files.writeString(tempDir.resolve("data.txt"), "ro\n", StandardCharsets.UTF_8);
            try (DockerWorkspace ws = DockerWorkspace.builder(TEST_IMAGE)
                    .bindMount(tempDir, "/data", true)
                    .build()) {
                DockerWorkspace.ExecResult r = ws.exec(
                        "echo new > /data/data.txt", Duration.ofSeconds(10));
                assertFalse(r.ok(), "write to a read-only mount should fail");
                // Host file unchanged
                assertEquals("ro\n", Files.readString(tempDir.resolve("data.txt")));
            }
        }
    }

    // =================================================================
    // Resource limits: confirm docker inspect shows them applied
    // =================================================================
    @Nested
    @DisplayName("resource limits")
    class Limits {

        @Test
        void memoryLimitApplied() throws IOException {
            try (DockerWorkspace ws = DockerWorkspace.builder(TEST_IMAGE)
                    .bindMount(tempDir, "/workspace")
                    .memoryLimit("64m")
                    .build()) {
                String memory = inspect(ws.containerName(), "{{.HostConfig.Memory}}");
                // 64m = 67108864 bytes
                assertEquals("67108864", memory.trim(),
                        "expected memory cap of 67108864 bytes; got: " + memory);
            }
        }

        @Test
        void networkNoneApplied() throws IOException {
            try (DockerWorkspace ws = DockerWorkspace.builder(TEST_IMAGE)
                    .bindMount(tempDir, "/workspace")
                    .networkMode("none")
                    .build()) {
                String mode = inspect(ws.containerName(), "{{.HostConfig.NetworkMode}}");
                assertEquals("none", mode.trim());
                // And the agent really cannot reach the network: try and fail
                DockerWorkspace.ExecResult r = ws.exec(
                        "sh -c 'wget -q -T 1 http://example.com -O /dev/null; echo $?'",
                        Duration.ofSeconds(10));
                // Should be non-zero exit (network unreachable). Whatever wget prints,
                // the captured "echo $?" should be > 0.
                assertTrue(r.stdout().trim().matches("[1-9][0-9]*"),
                        "network=none should make wget fail; got stdout=" + r.stdout());
            }
        }

        @Test
        void pidsLimitApplied() throws IOException {
            try (DockerWorkspace ws = DockerWorkspace.builder(TEST_IMAGE)
                    .bindMount(tempDir, "/workspace")
                    .pidsLimit(50)
                    .build()) {
                String pids = inspect(ws.containerName(), "{{.HostConfig.PidsLimit}}");
                assertEquals("50", pids.trim());
            }
        }
    }

    // =================================================================
    // Composition: GitWorktreeWorkspace as the bind-mount source
    // =================================================================
    @Nested
    @DisplayName("composition with GitWorktreeWorkspace")
    class Composition {

        @Test
        void agentInsideContainerWritesShowUpInWorktreeDiff() throws IOException, InterruptedException {
            // 1. Set up a real git repo
            Path repoPath = tempDir.resolve("repo");
            Files.createDirectories(repoPath);
            run(repoPath, "git", "init", "-q");
            run(repoPath, "git", "config", "user.email", "test@example.com");
            run(repoPath, "git", "config", "user.name", "test");
            Files.writeString(repoPath.resolve("README.md"), "# repo\n", StandardCharsets.UTF_8);
            run(repoPath, "git", "add", ".");
            run(repoPath, "git", "commit", "-q", "-m", "initial");

            // 2. Worktree off main; mount it into a Docker workspace
            try (GitWorktreeWorkspace wt = new GitWorktreeWorkspace(repoPath);
                 DockerWorkspace dws = DockerWorkspace.builder(TEST_IMAGE)
                         .bindMount(wt.root(), "/workspace")
                         .workdir("/workspace")
                         .networkMode("none")
                         .memoryLimit("128m")
                         .build()) {

                // 3. "Coding agent" inside the container creates a new file
                DockerWorkspace.ExecResult r = dws.exec(
                        "echo agent-output > /workspace/skills/MySkill.txt && "
                                + "mkdir -p /workspace/skills && "
                                + "echo definition > /workspace/skills/skill.json",
                        Duration.ofSeconds(10));
                // Note: order matters in shell; let's redo in a saner order
            }

            // 4. The above demo command had a bug (file before mkdir); rerun cleanly:
            try (GitWorktreeWorkspace wt = new GitWorktreeWorkspace(repoPath);
                 DockerWorkspace dws = DockerWorkspace.builder(TEST_IMAGE)
                         .bindMount(wt.root(), "/workspace")
                         .workdir("/workspace")
                         .networkMode("none")
                         .build()) {

                DockerWorkspace.ExecResult mk = dws.exec(
                        "mkdir -p /workspace/skills && "
                                + "echo agent-output > /workspace/skills/MySkill.txt && "
                                + "echo '{\"name\": \"my\"}' > /workspace/skills/skill.json",
                        Duration.ofSeconds(10));
                assertTrue(mk.ok(),
                        "container script failed: stderr=" + mk.stderr() + ", stdout=" + mk.stdout());

                // 5. Worktree.diff() sees the agent's writes
                List<FileChange> changes = wt.diff();
                long added = changes.stream()
                        .filter(c -> c.kind() == ChangeKind.ADDED)
                        .count();
                assertEquals(2, added,
                        "expected 2 new files (MySkill.txt + skill.json); got: " + changes);

                // The host can read the file the container wrote
                assertEquals("agent-output\n",
                        Files.readString(wt.root().resolve("skills/MySkill.txt")));
            }
        }
    }

    // =================================================================
    // Helpers
    // =================================================================

    private static boolean containerExists(String name) throws IOException {
        Process p = new ProcessBuilder("docker", "ps", "-a",
                "--filter", "name=" + name,
                "--format", "{{.Names}}")
                .redirectErrorStream(true)
                .start();
        try {
            p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return out.lines().anyMatch(line -> line.trim().equals(name));
    }

    private static String inspect(String containerName, String format) throws IOException {
        Process p = new ProcessBuilder("docker", "inspect",
                "--format", format, containerName)
                .redirectErrorStream(true)
                .start();
        try {
            p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
        return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void run(Path cwd, String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true);
        Process p = pb.start();
        if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("timeout: " + Arrays.toString(cmd));
        }
        if (p.exitValue() != 0) {
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            throw new IOException("non-zero from " + Arrays.toString(cmd) + ": " + out);
        }
    }
}
