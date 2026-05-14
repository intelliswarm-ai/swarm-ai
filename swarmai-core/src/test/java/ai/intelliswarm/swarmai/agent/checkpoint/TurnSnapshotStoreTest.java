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
}
