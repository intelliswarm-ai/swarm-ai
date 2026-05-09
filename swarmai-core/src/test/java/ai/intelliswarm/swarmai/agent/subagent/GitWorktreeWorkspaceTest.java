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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link GitWorktreeWorkspace}. Requires git on the PATH (every
 * developer/CI host has it). Disabled on Windows because the path semantics
 * differ enough that a separate test pass is needed; the production code is
 * cross-platform but the test fixtures use POSIX shell idioms.
 */
@DisabledOnOs(OS.WINDOWS)
class GitWorktreeWorkspaceTest {

    @TempDir
    Path tempDir;

    private Path repoPath;

    @BeforeEach
    void setUp() throws IOException, InterruptedException {
        // Create a fresh git repo with a known initial commit
        repoPath = tempDir.resolve("repo");
        Files.createDirectories(repoPath);

        runOrThrow(repoPath, "git", "init", "-q");
        runOrThrow(repoPath, "git", "config", "user.email", "test@example.com");
        runOrThrow(repoPath, "git", "config", "user.name", "test");

        Files.writeString(repoPath.resolve("README.md"), "# test repo\n", StandardCharsets.UTF_8);
        Files.writeString(repoPath.resolve("hello.txt"), "hello world\n", StandardCharsets.UTF_8);
        runOrThrow(repoPath, "git", "add", ".");
        runOrThrow(repoPath, "git", "commit", "-q", "-m", "initial");
    }

    // =================================================================
    // ChangeKind enum
    // =================================================================
    @Nested
    @DisplayName("ChangeKind")
    class StatusEnum {

        @Test
        void mapsGitCodes() {
            assertEquals(ChangeKind.ADDED, ChangeKind.fromGitCode("A"));
            assertEquals(ChangeKind.MODIFIED, ChangeKind.fromGitCode("M"));
            assertEquals(ChangeKind.DELETED, ChangeKind.fromGitCode("D"));
            assertEquals(ChangeKind.RENAMED, ChangeKind.fromGitCode("R"));
            assertEquals(ChangeKind.RENAMED, ChangeKind.fromGitCode("R100"));
            assertEquals(ChangeKind.OTHER, ChangeKind.fromGitCode("T"));
            assertEquals(ChangeKind.OTHER, ChangeKind.fromGitCode(""));
            assertEquals(ChangeKind.OTHER, ChangeKind.fromGitCode(null));
        }
    }

    // =================================================================
    // FileChange record
    // =================================================================
    @Nested
    @DisplayName("FileChange record")
    class ChangeRecord {

        @Test
        void factories() {
            FileChange a = FileChange.of(Paths.get("foo"), ChangeKind.ADDED);
            assertEquals(-1, a.linesAdded());
            assertFalse(a.hasLineCounts());

            FileChange m = FileChange.of(Paths.get("foo"), ChangeKind.MODIFIED, 5, 2);
            assertTrue(m.hasLineCounts());

            FileChange r = FileChange.renamed(Paths.get("a"), Paths.get("b"), 1, 1);
            assertEquals(ChangeKind.RENAMED, r.kind());
            assertEquals(Paths.get("a"), r.renamedFromOpt().orElseThrow());
            assertEquals(Paths.get("b"), r.path());
        }

        @Test
        void nullsRejected() {
            assertThrows(NullPointerException.class,
                    () -> new FileChange(null, ChangeKind.ADDED, 0, 0, null));
            assertThrows(NullPointerException.class,
                    () -> new FileChange(Paths.get("x"), null, 0, 0, null));
        }
    }

    // =================================================================
    // GitWorktreeWorkspace lifecycle
    // =================================================================
    @Nested
    @DisplayName("lifecycle")
    class Lifecycle {

        @Test
        void createWorktreeRootExistsAndContainsRepoFiles() throws IOException {
            try (GitWorktreeWorkspace ws = new GitWorktreeWorkspace(repoPath)) {
                assertTrue(ws.isOpen());
                assertTrue(Files.isDirectory(ws.root()));
                // Repo files visible inside the worktree
                assertTrue(Files.exists(ws.root().resolve("README.md")));
                assertTrue(Files.exists(ws.root().resolve("hello.txt")));
                // The branch name was generated
                assertNotNull(ws.branchName());
                assertTrue(ws.branchName().startsWith("swarmai-wt-"));
            }
        }

        @Test
        void closeCleansUpWorktreeAndBranch() throws IOException {
            Path worktreeRoot;
            String branchName;
            try (GitWorktreeWorkspace ws = new GitWorktreeWorkspace(repoPath)) {
                worktreeRoot = ws.root();
                branchName = ws.branchName();
                assertTrue(Files.exists(worktreeRoot));
            }
            // Worktree dir gone after close
            assertFalse(Files.exists(worktreeRoot));
            // Branch gone too — git branch --list should not list it
            String branches = runCapture(repoPath, "git", "branch", "--list");
            assertFalse(branches.contains(branchName), "ephemeral branch should be deleted: " + branches);
        }

        @Test
        void closeIsIdempotent() throws IOException {
            GitWorktreeWorkspace ws = new GitWorktreeWorkspace(repoPath);
            ws.close();
            assertFalse(ws.isOpen());
            // Second close — must not throw
            assertDoesNotThrow(ws::close);
            assertFalse(ws.isOpen());
        }

        @Test
        void invalidRepoRejected() {
            Path notARepo = tempDir.resolve("not-a-repo");
            assertThrows(IllegalArgumentException.class,
                    () -> new GitWorktreeWorkspace(notARepo));
        }

        @Test
        void invalidRefRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> new GitWorktreeWorkspace(repoPath, "definitely-not-a-real-branch"));
        }

        @Test
        void blankRefRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> new GitWorktreeWorkspace(repoPath, "  "));
        }

        @Test
        void multipleConcurrentWorktreesIndependent() throws IOException {
            // Two workspaces off the same repo; both must materialise + close cleanly
            try (GitWorktreeWorkspace a = new GitWorktreeWorkspace(repoPath);
                 GitWorktreeWorkspace b = new GitWorktreeWorkspace(repoPath)) {
                assertTrue(Files.isDirectory(a.root()));
                assertTrue(Files.isDirectory(b.root()));
                assertNotEquals(a.root(), b.root());
                assertNotEquals(a.branchName(), b.branchName());
            }
        }

        @Test
        void operationsAfterCloseFail() throws IOException {
            GitWorktreeWorkspace ws = new GitWorktreeWorkspace(repoPath);
            ws.close();
            assertThrows(IllegalStateException.class, ws::diff);
            assertThrows(IllegalStateException.class, ws::diffAsPatch);
        }
    }

    // =================================================================
    // Diff discovery — the load-bearing functionality for skill creation
    // =================================================================
    @Nested
    @DisplayName("diff discovery")
    class Diff {

        @Test
        void noChangesReturnsEmpty() throws IOException {
            try (GitWorktreeWorkspace ws = new GitWorktreeWorkspace(repoPath)) {
                assertEquals(List.of(), ws.diff());
                assertEquals("", ws.diffAsPatch());
                assertFalse(ws.hasChanges());
            }
        }

        @Test
        void addedFileSurfacesAsAdded() throws IOException {
            try (GitWorktreeWorkspace ws = new GitWorktreeWorkspace(repoPath)) {
                Files.writeString(ws.root().resolve("new.txt"), "fresh content\n", StandardCharsets.UTF_8);
                List<FileChange> changes = ws.diff();
                assertEquals(1, changes.size(), "expected 1 change, got: " + changes);
                FileChange c = changes.get(0);
                assertEquals(Paths.get("new.txt"), c.path());
                assertEquals(ChangeKind.ADDED, c.kind());
                assertTrue(ws.hasChanges());
            }
        }

        @Test
        void modifiedFileSurfacesAsModified() throws IOException {
            try (GitWorktreeWorkspace ws = new GitWorktreeWorkspace(repoPath)) {
                Files.writeString(ws.root().resolve("hello.txt"),
                        "hello changed\n", StandardCharsets.UTF_8);
                List<FileChange> changes = ws.diff();
                assertEquals(1, changes.size());
                FileChange c = changes.get(0);
                assertEquals(Paths.get("hello.txt"), c.path());
                assertEquals(ChangeKind.MODIFIED, c.kind());
                assertTrue(c.hasLineCounts(), "modified text files should have line counts");
                assertTrue(c.linesAdded() > 0);
            }
        }

        @Test
        void deletedFileSurfacesAsDeleted() throws IOException {
            try (GitWorktreeWorkspace ws = new GitWorktreeWorkspace(repoPath)) {
                Files.delete(ws.root().resolve("hello.txt"));
                List<FileChange> changes = ws.diff();
                assertEquals(1, changes.size());
                FileChange c = changes.get(0);
                assertEquals(Paths.get("hello.txt"), c.path());
                assertEquals(ChangeKind.DELETED, c.kind());
            }
        }

        @Test
        void multiFileChangesAllSurface() throws IOException {
            try (GitWorktreeWorkspace ws = new GitWorktreeWorkspace(repoPath)) {
                Files.writeString(ws.root().resolve("a.txt"), "a\n", StandardCharsets.UTF_8);
                Files.writeString(ws.root().resolve("b.txt"), "b\n", StandardCharsets.UTF_8);
                Files.writeString(ws.root().resolve("hello.txt"), "modified\n", StandardCharsets.UTF_8);

                List<FileChange> changes = ws.diff();
                assertEquals(3, changes.size());
                long added = changes.stream().filter(c -> c.kind() == ChangeKind.ADDED).count();
                long modified = changes.stream().filter(c -> c.kind() == ChangeKind.MODIFIED).count();
                assertEquals(2, added);
                assertEquals(1, modified);
            }
        }

        @Test
        void diffAsPatchReturnsUnifiedDiff() throws IOException {
            try (GitWorktreeWorkspace ws = new GitWorktreeWorkspace(repoPath)) {
                Files.writeString(ws.root().resolve("hello.txt"),
                        "completely different\n", StandardCharsets.UTF_8);
                String patch = ws.diffAsPatch();
                assertTrue(patch.contains("hello.txt"), "patch should mention the file: " + patch);
                assertTrue(patch.contains("-hello world") || patch.contains("+completely different"),
                        "patch should show the change: " + patch);
            }
        }

        @Test
        void changesInWorktreeDoNotAffectMainRepo() throws IOException {
            try (GitWorktreeWorkspace ws = new GitWorktreeWorkspace(repoPath)) {
                Files.writeString(ws.root().resolve("hello.txt"),
                        "modified in worktree\n", StandardCharsets.UTF_8);
                // The main repo's hello.txt is unchanged
                String mainContent = Files.readString(repoPath.resolve("hello.txt"));
                assertEquals("hello world\n", mainContent,
                        "main repo's working tree must be untouched by worktree edits");
            }
        }
    }

    // =================================================================
    // Pure parser tests — exercise edge cases without spawning git
    // =================================================================
    @Nested
    @DisplayName("parser edge cases")
    class Parsers {

        @Test
        void parseNameStatusEmpty() {
            assertTrue(GitWorktreeWorkspace.parseNameStatus("").isEmpty());
            assertTrue(GitWorktreeWorkspace.parseNameStatus(null).isEmpty());
            assertTrue(GitWorktreeWorkspace.parseNameStatus("   ").isEmpty());
        }

        @Test
        void parseNameStatusSimple() {
            String out = "A\tnew.txt\nM\thello.txt\nD\tgone.txt\n";
            List<FileChange> changes = GitWorktreeWorkspace.parseNameStatus(out);
            assertEquals(3, changes.size());
            assertEquals(ChangeKind.ADDED, changes.get(0).kind());
            assertEquals(ChangeKind.MODIFIED, changes.get(1).kind());
            assertEquals(ChangeKind.DELETED, changes.get(2).kind());
        }

        @Test
        void parseNameStatusRename() {
            // git uses "R<similarity>\told\tnew" for rename status lines
            String out = "R100\told.txt\tnew.txt\n";
            List<FileChange> changes = GitWorktreeWorkspace.parseNameStatus(out);
            assertEquals(1, changes.size());
            FileChange c = changes.get(0);
            assertEquals(ChangeKind.RENAMED, c.kind());
            assertEquals(Paths.get("new.txt"), c.path());
            assertEquals(Paths.get("old.txt"), c.renamedFromOpt().orElseThrow());
        }

        @Test
        void parseNumstatBinary() {
            // Binary files show "-\t-\tpath"
            String out = "-\t-\tlogo.png\n5\t2\tdoc.txt\n";
            List<FileChange> changes = GitWorktreeWorkspace.parseNumstat(out);
            assertEquals(2, changes.size());
            assertFalse(changes.get(0).hasLineCounts(), "binary file: line counts should be -1");
            assertTrue(changes.get(1).hasLineCounts());
            assertEquals(5, changes.get(1).linesAdded());
            assertEquals(2, changes.get(1).linesRemoved());
        }

        @Test
        void extractRenameTarget() {
            assertEquals("new.txt",
                    GitWorktreeWorkspace.extractRenameTarget("old.txt => new.txt"));
            assertEquals("src/new/file.java",
                    GitWorktreeWorkspace.extractRenameTarget("src/{old => new}/file.java"));
            assertEquals("plain.txt",
                    GitWorktreeWorkspace.extractRenameTarget("plain.txt"));
        }

        @Test
        void mergeNumstatJoinsLineCountsByPath() {
            List<FileChange> kindOnly = List.of(
                    FileChange.of(Paths.get("a.txt"), ChangeKind.ADDED),
                    FileChange.of(Paths.get("b.txt"), ChangeKind.MODIFIED));
            List<FileChange> numstat = List.of(
                    FileChange.of(Paths.get("a.txt"), ChangeKind.OTHER, 3, 0),
                    FileChange.of(Paths.get("b.txt"), ChangeKind.OTHER, 1, 1));

            List<FileChange> merged = GitWorktreeWorkspace.mergeNumstat(kindOnly, numstat);
            assertEquals(2, merged.size());
            assertEquals(ChangeKind.ADDED, merged.get(0).kind(),
                    "kind from name-status preserved after merge");
            assertEquals(3, merged.get(0).linesAdded());
            assertEquals(ChangeKind.MODIFIED, merged.get(1).kind());
            assertEquals(1, merged.get(1).linesAdded());
        }
    }

    // =================================================================
    // Test helpers
    // =================================================================

    /** Run a process to completion in {@code cwd}, throw on non-zero exit. */
    private static void runOrThrow(Path cwd, String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true);
        Process p = pb.start();
        if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("timeout running: " + Arrays.toString(cmd));
        }
        if (p.exitValue() != 0) {
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            throw new IOException("non-zero exit " + p.exitValue()
                    + " from " + Arrays.toString(cmd) + ":\n" + out);
        }
    }

    /** Run a process in {@code cwd} and return its (combined) stdout. */
    private static String runCapture(Path cwd, String... cmd) throws IOException {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("timeout");
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }
}
