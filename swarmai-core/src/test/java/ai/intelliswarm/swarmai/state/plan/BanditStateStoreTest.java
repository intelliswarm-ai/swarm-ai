package ai.intelliswarm.swarmai.state.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("BanditStateStore Tests")
class BanditStateStoreTest {

    private static PlanEntry entry(String id, Risk risk) {
        return PlanEntry.builder()
                .id(id).proposedBy("tool:x").payload("op-" + id).risk(risk).build();
    }

    private static BanditApprovalPolicy trainedBandit() {
        BanditApprovalPolicy bandit = new BanditApprovalPolicy(
                BanditApprovalPolicy.DEFAULT_BUCKET_KEY, 0);
        for (int i = 0; i < 50; i++) {
            bandit.recordOutcome(entry("a" + i, Risk.LOW), 1.0);
        }
        for (int i = 0; i < 50; i++) {
            bandit.recordOutcome(entry("b" + i, Risk.HIGH), -1.0);
        }
        return bandit;
    }

    @Nested
    @DisplayName("InMemoryBanditStateStore")
    class InMemory {

        @Test
        @DisplayName("fresh store returns empty state")
        void freshStoreEmpty() throws IOException {
            InMemoryBanditStateStore store = new InMemoryBanditStateStore();
            assertTrue(store.load().isEmpty());
        }

        @Test
        @DisplayName("save then load round-trips state exactly")
        void saveLoadRoundTrip() throws IOException {
            BanditApprovalPolicy bandit = trainedBandit();
            BanditState state = bandit.snapshot();

            InMemoryBanditStateStore store = new InMemoryBanditStateStore();
            store.save(state);

            BanditState loaded = store.load();
            assertEquals(state, loaded);
        }

        @Test
        @DisplayName("save replaces previous state atomically")
        void saveReplaces() throws IOException {
            InMemoryBanditStateStore store = new InMemoryBanditStateStore();
            store.save(BanditState.builder()
                    .bucket("k", 5, 5, 5, 5).build());
            store.save(BanditState.builder()
                    .bucket("k", 1, 1, 1, 1).build());

            assertEquals(1.0, store.load().buckets().get("k").alphaAuto(), 1e-9);
        }

        @Test
        @DisplayName("save(null) throws")
        void saveNullRejected() {
            InMemoryBanditStateStore store = new InMemoryBanditStateStore();
            assertThrows(NullPointerException.class, () -> store.save(null));
        }
    }

    @Nested
    @DisplayName("FileBanditStateStore")
    class FileStore {

        @Test
        @DisplayName("missing file returns empty state (first-run behaviour)")
        void missingFileEmpty(@TempDir Path tempDir) throws IOException {
            FileBanditStateStore store = new FileBanditStateStore(tempDir.resolve("nope.json"));
            assertTrue(store.load().isEmpty());
        }

        @Test
        @DisplayName("save creates the file and parent directories if missing")
        void saveCreatesParents(@TempDir Path tempDir) throws IOException {
            Path nested = tempDir.resolve("a/b/c/state.json");
            FileBanditStateStore store = new FileBanditStateStore(nested);

            store.save(BanditState.builder().bucket("k", 2, 1, 1, 2).build());

            assertTrue(Files.exists(nested));
            assertTrue(Files.size(nested) > 0);
        }

        @Test
        @DisplayName("save then load round-trips state exactly through JSON")
        void saveLoadRoundTrip(@TempDir Path tempDir) throws IOException {
            BanditApprovalPolicy bandit = trainedBandit();
            BanditState original = bandit.snapshot();

            FileBanditStateStore store = new FileBanditStateStore(tempDir.resolve("bandit.json"));
            store.save(original);

            BanditState loaded = store.load();
            assertEquals(original, loaded,
                    "JSON round-trip should preserve state exactly");
        }

        @Test
        @DisplayName("loaded state restores into a fresh policy with identical means")
        void crossInstanceRestoreRoundTrip(@TempDir Path tempDir) throws IOException {
            BanditApprovalPolicy original = trainedBandit();
            FileBanditStateStore store = new FileBanditStateStore(tempDir.resolve("bandit.json"));
            store.save(original.snapshot());

            // Simulate a fresh process: brand new policy + load + restore
            BanditApprovalPolicy restored = new BanditApprovalPolicy(
                    BanditApprovalPolicy.DEFAULT_BUCKET_KEY, 0);
            restored.restore(store.load());

            assertEquals(original.observationCount("tool:x:" + Risk.LOW),
                    restored.observationCount("tool:x:" + Risk.LOW));
            assertEquals(original.meanSuccessRate("tool:x:" + Risk.LOW),
                    restored.meanSuccessRate("tool:x:" + Risk.LOW), 1e-9);
            assertEquals(original.meanSuccessRate("tool:x:" + Risk.HIGH),
                    restored.meanSuccessRate("tool:x:" + Risk.HIGH), 1e-9);
        }

        @Test
        @DisplayName("multiple saves leave no .tmp file behind")
        void cleansUpTempFile(@TempDir Path tempDir) throws IOException {
            FileBanditStateStore store = new FileBanditStateStore(tempDir.resolve("bandit.json"));
            for (int i = 0; i < 5; i++) {
                store.save(BanditState.builder()
                        .bucket("k", 1.0 + i, 1.0, 1.0, 1.0 + i).build());
            }
            // The .tmp file should not exist after a successful save (atomic rename consumed it)
            try (var stream = Files.list(tempDir)) {
                long tmpCount = stream.filter(p -> p.getFileName().toString().endsWith(".tmp")).count();
                assertEquals(0, tmpCount, "no .tmp residue should remain");
            }
        }

        @Test
        @DisplayName("compact mode produces shorter JSON than pretty mode")
        void compactModeShorterFile(@TempDir Path tempDir) throws IOException {
            BanditState state = trainedBandit().snapshot();
            Path pretty = tempDir.resolve("pretty.json");
            Path compact = tempDir.resolve("compact.json");
            new FileBanditStateStore(pretty, /*prettyPrint*/ true).save(state);
            new FileBanditStateStore(compact, /*prettyPrint*/ false).save(state);

            assertTrue(Files.size(compact) < Files.size(pretty),
                    "compact JSON should be smaller than pretty-printed JSON");
        }

        @Test
        @DisplayName("path() accessor reports the configured path")
        void pathAccessor(@TempDir Path tempDir) {
            Path p = tempDir.resolve("here.json");
            FileBanditStateStore store = new FileBanditStateStore(p);
            assertNotNull(store.path());
            assertEquals(p, store.path());
        }

        @Test
        @DisplayName("corrupted JSON raises IOException on load")
        void corruptedFileThrows(@TempDir Path tempDir) throws IOException {
            Path p = tempDir.resolve("bad.json");
            Files.writeString(p, "not json at all");
            FileBanditStateStore store = new FileBanditStateStore(p);
            assertThrows(IOException.class, store::load);
        }
    }

    @Nested
    @DisplayName("End-to-end: train → save → fresh policy → load → continue learning")
    class CrossSession {

        @Test
        @DisplayName("learning continues seamlessly across a save/load boundary")
        void learningContinuesAcrossPersistence(@TempDir Path tempDir) throws IOException {
            FileBanditStateStore store = new FileBanditStateStore(tempDir.resolve("bandit.json"));

            // --- "Session 1" — train, then save ---
            BanditApprovalPolicy s1 = new BanditApprovalPolicy(
                    BanditApprovalPolicy.DEFAULT_BUCKET_KEY, 0);
            for (int i = 0; i < 100; i++) {
                s1.recordOutcome(entry("e" + i, Risk.LOW), 1.0);
            }
            store.save(s1.snapshot());
            long s1Count = s1.observationCount("tool:x:" + Risk.LOW);

            // --- "Session 2" — load + continue training ---
            BanditApprovalPolicy s2 = new BanditApprovalPolicy(
                    BanditApprovalPolicy.DEFAULT_BUCKET_KEY, 0);
            assertFalse(s2.bucketKeys().contains("tool:x:" + Risk.LOW));
            s2.restore(store.load());
            assertEquals(s1Count, s2.observationCount("tool:x:" + Risk.LOW));

            for (int i = 100; i < 200; i++) {
                s2.recordOutcome(entry("e" + i, Risk.LOW), 1.0);
            }
            assertTrue(s2.observationCount("tool:x:" + Risk.LOW) > s1Count,
                    "session 2 should accumulate observations on top of session 1");
        }
    }
}
