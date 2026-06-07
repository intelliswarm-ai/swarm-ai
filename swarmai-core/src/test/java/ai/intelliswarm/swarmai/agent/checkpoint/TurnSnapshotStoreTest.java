package ai.intelliswarm.swarmai.agent.checkpoint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TurnSnapshotStoreTest {

    @Test
    void initialisesShadowGitRepo(@TempDir Path tempDir) {
        Path storeRoot = tempDir.resolve("store");
        try (TurnSnapshotStore store = new TurnSnapshotStore(storeRoot)) {
            assertThat(storeRoot.resolve(".git")).exists();
        }
    }

    @Test
    void snapshotCapturesWorkspaceState(@TempDir Path tempDir) throws Exception {
        Path workspace = tempDir.resolve("ws");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("model.py"), "def dcf(): pass\n");

        try (TurnSnapshotStore store = new TurnSnapshotStore(tempDir.resolve("store"))) {
            TurnSnapshot snap = store.snapshot(workspace, "run-1", "initial state", 0,
                    Map.of("tool", "init"));
            assertThat(snap.commitId()).isNotBlank();
            assertThat(snap.runId()).isEqualTo("run-1");
            assertThat(snap.turnIndex()).isZero();
            assertThat(snap.message()).isEqualTo("initial state");
        }
    }

    @Test
    void multipleSnapshotsTrackWorkspaceEvolution(@TempDir Path tempDir) throws Exception {
        Path workspace = tempDir.resolve("ws");
        Files.createDirectories(workspace);

        try (TurnSnapshotStore store = new TurnSnapshotStore(tempDir.resolve("store"))) {
            Files.writeString(workspace.resolve("a.txt"), "first");
            TurnSnapshot s1 = store.snapshot(workspace, "run-1", "first turn", 0, Map.of());

            Files.writeString(workspace.resolve("a.txt"), "second");
            Files.writeString(workspace.resolve("b.txt"), "new file");
            TurnSnapshot s2 = store.snapshot(workspace, "run-1", "second turn", 1, Map.of());

            assertThat(s1.commitId()).isNotEqualTo(s2.commitId());
            String diff = store.diff(s1.commitId(), s2.commitId());
            assertThat(diff).contains("second").contains("new file");
        }
    }

    @Test
    void recentEnumeratesSnapshotsReverseChronological(@TempDir Path tempDir) throws Exception {
        Path workspace = tempDir.resolve("ws");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("file.txt"), "v0");

        try (TurnSnapshotStore store = new TurnSnapshotStore(tempDir.resolve("store"))) {
            for (int i = 0; i < 3; i++) {
                Files.writeString(workspace.resolve("file.txt"), "v" + i);
                store.snapshot(workspace, "run-x", "turn " + i, i, Map.of());
            }
            var snapshots = store.recent(10);
            // 3 turn snapshots; the "init" empty commit is filtered out by parseMessage
            assertThat(snapshots).hasSize(3);
            // Most recent first → turn 2 before turn 0
            assertThat(snapshots.get(0).turnIndex()).isEqualTo(2);
            assertThat(snapshots.get(2).turnIndex()).isZero();
        }
    }

    @Test
    void shortCommitIdReturnsFirstEightChars(@TempDir Path tempDir) throws Exception {
        Path workspace = tempDir.resolve("ws");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("x.txt"), "data");
        try (TurnSnapshotStore store = new TurnSnapshotStore(tempDir.resolve("store"))) {
            TurnSnapshot snap = store.snapshot(workspace, "r", "m", 0, Map.of());
            assertThat(snap.shortCommitId()).hasSize(8);
            assertThat(snap.commitId()).startsWith(snap.shortCommitId());
        }
    }

    @Test
    void reopenStoreReadsExistingHistory(@TempDir Path tempDir) throws Exception {
        Path storeRoot = tempDir.resolve("store");
        Path workspace = tempDir.resolve("ws");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("file.txt"), "data");

        try (TurnSnapshotStore store = new TurnSnapshotStore(storeRoot)) {
            store.snapshot(workspace, "r", "first", 0, Map.of());
        }
        try (TurnSnapshotStore store2 = new TurnSnapshotStore(storeRoot)) {
            assertThat(store2.recent(10)).hasSize(1);
        }
    }

    @Test
    void emptySnapshotStillCommits(@TempDir Path tempDir) throws Exception {
        Path workspace = tempDir.resolve("ws");
        Files.createDirectories(workspace);
        try (TurnSnapshotStore store = new TurnSnapshotStore(tempDir.resolve("store"))) {
            // No files in workspace — should still produce a snapshot via setAllowEmpty
            TurnSnapshot snap = store.snapshot(workspace, "r", "no-op turn", 0, Map.of());
            assertThat(snap.commitId()).isNotBlank();
        }
    }

    // ---------- regression tests (Codex review of v1.0.24) ----------

    @Test
    void deletedFilesAreCapturedInLaterSnapshots(@TempDir Path tempDir) throws Exception {
        // Regression: copyTree previously only added new/modified files. A file
        // deleted from the workspace persisted in the mirror and got re-committed,
        // so diffs lied about state evolution and restore brought back ghosts.
        Path workspace = tempDir.resolve("ws");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("kept.txt"), "kept content");
        Files.writeString(workspace.resolve("doomed.txt"), "going away soon");

        try (TurnSnapshotStore store = new TurnSnapshotStore(tempDir.resolve("store"))) {
            TurnSnapshot s1 = store.snapshot(workspace, "run-1", "before", 0, Map.of());

            // Delete one file in the workspace, take another snapshot
            Files.delete(workspace.resolve("doomed.txt"));
            TurnSnapshot s2 = store.snapshot(workspace, "run-1", "after delete", 1, Map.of());

            // The diff must show doomed.txt as removed between s1 and s2.
            String diff = store.diff(s1.commitId(), s2.commitId());
            assertThat(diff).contains("doomed.txt");
            // And restoring s2 to a clean workspace must NOT bring doomed.txt back.
            Path target = tempDir.resolve("restored");
            Files.createDirectories(target);
            store.restore(target, s2.commitId());
            assertThat(target.resolve("kept.txt")).exists();
            assertThat(target.resolve("doomed.txt")).doesNotExist();
        }
    }

    @Test
    void restorePicksCorrectWorkspaceWhenStoreHoldsMultipleRuns(@TempDir Path tempDir) throws Exception {
        // Regression: restore previously walked all workspace-* mirrors with
        // findFirst(), so a commit from run-B could replay run-A's files in
        // a multi-run store. Verify each runId's snapshots restore the right files.
        Path wsA = tempDir.resolve("wsA");
        Path wsB = tempDir.resolve("wsB");
        Files.createDirectories(wsA);
        Files.createDirectories(wsB);
        Files.writeString(wsA.resolve("only-in-A.txt"), "alpha");
        Files.writeString(wsB.resolve("only-in-B.txt"), "bravo");

        try (TurnSnapshotStore store = new TurnSnapshotStore(tempDir.resolve("store"))) {
            TurnSnapshot snapA = store.snapshot(wsA, "run-A", "alpha turn", 0, Map.of());
            TurnSnapshot snapB = store.snapshot(wsB, "run-B", "bravo turn", 0, Map.of());

            // Restore commit from run-A → should only contain run-A files
            Path restoredA = tempDir.resolve("restoredA");
            Files.createDirectories(restoredA);
            store.restore(restoredA, snapA.commitId());
            assertThat(restoredA.resolve("only-in-A.txt")).exists();
            assertThat(restoredA.resolve("only-in-B.txt")).doesNotExist();

            // Restore commit from run-B → should only contain run-B files
            Path restoredB = tempDir.resolve("restoredB");
            Files.createDirectories(restoredB);
            store.restore(restoredB, snapB.commitId());
            assertThat(restoredB.resolve("only-in-B.txt")).exists();
            assertThat(restoredB.resolve("only-in-A.txt")).doesNotExist();
        }
    }

    @Test
    void restoreClearsStaleFilesInNonEmptyTarget(@TempDir Path tempDir) throws Exception {
        // Regression: restore previously copied the mirror into the target
        // without first clearing it, so files in the target that were absent
        // from the snapshot lingered and downstream turns operated on ghost
        // artifacts. After this fix, restore yields a target that is bit-for-bit
        // equal to the snapshot.
        Path workspace = tempDir.resolve("ws");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("snapshot-file.txt"), "in snapshot");

        try (TurnSnapshotStore store = new TurnSnapshotStore(tempDir.resolve("store"))) {
            TurnSnapshot snap = store.snapshot(workspace, "run-1", "captured", 0, Map.of());

            Path target = tempDir.resolve("dirty-target");
            Files.createDirectories(target);
            Files.writeString(target.resolve("ghost.txt"), "should be gone after restore");
            Files.createDirectories(target.resolve("nested"));
            Files.writeString(target.resolve("nested/also-ghost.txt"), "also gone");

            store.restore(target, snap.commitId());

            assertThat(target.resolve("snapshot-file.txt")).exists();
            assertThat(target.resolve("ghost.txt")).doesNotExist();
            assertThat(target.resolve("nested/also-ghost.txt")).doesNotExist();
        }
    }
}
