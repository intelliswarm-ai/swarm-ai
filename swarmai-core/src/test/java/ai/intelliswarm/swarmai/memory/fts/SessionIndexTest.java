package ai.intelliswarm.swarmai.memory.fts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SessionIndexTest {

    @Test
    void roundTripSingleTurn(@TempDir Path tempDir) {
        SessionIndex idx = new SessionIndex(tempDir.resolve("index.db"));
        idx.addTurn("session-1", "analyst-1", "user",
                "What's ACME's intrinsic value using DCF?", Instant.now());
        assertThat(idx.totalTurns()).isEqualTo(1);

        var hits = idx.search("ACME DCF", null, 10);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).sessionId()).isEqualTo("session-1");
        assertThat(hits.get(0).content()).contains("ACME").contains("DCF");
        assertThat(hits.get(0).snippet()).contains("<b>").contains("</b>");
    }

    @Test
    void batchAddsAllTurns(@TempDir Path tempDir) {
        SessionIndex idx = new SessionIndex(tempDir.resolve("index.db"));
        idx.addTurns("session-x", "agent-1", List.of(
                new SessionIndex.TurnRecord("user", "What's the WACC?", Instant.now()),
                new SessionIndex.TurnRecord("assistant", "Computed WACC of 8.4%.", Instant.now()),
                new SessionIndex.TurnRecord("user", "And the terminal growth?", Instant.now())));
        assertThat(idx.totalTurns()).isEqualTo(3);
    }

    @Test
    void searchRankedByBm25(@TempDir Path tempDir) {
        SessionIndex idx = new SessionIndex(tempDir.resolve("index.db"));
        idx.addTurn("s1", "a", "assistant",
                "EBITDA grew 15% year-over-year.", Instant.now());
        idx.addTurn("s2", "a", "assistant",
                "EBITDA EBITDA EBITDA — strong performance.", Instant.now());

        var hits = idx.search("EBITDA", null, 10);
        assertThat(hits).hasSize(2);
        // BM25: more occurrences in a shorter document ranks higher (lower score)
        assertThat(hits.get(0).rank()).isLessThanOrEqualTo(hits.get(1).rank());
    }

    @Test
    void agentIdFilterNarrowsResults(@TempDir Path tempDir) {
        SessionIndex idx = new SessionIndex(tempDir.resolve("index.db"));
        idx.addTurn("s1", "analyst-1", "user", "ACME DCF analysis", Instant.now());
        idx.addTurn("s2", "analyst-2", "user", "ACME DCF analysis", Instant.now());

        var allHits = idx.search("ACME", null, 10);
        assertThat(allHits).hasSize(2);

        var filtered = idx.search("ACME", "analyst-1", 10);
        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).agentId()).isEqualTo("analyst-1");
    }

    @Test
    void prefixWildcardSyntaxSupported(@TempDir Path tempDir) {
        SessionIndex idx = new SessionIndex(tempDir.resolve("index.db"));
        idx.addTurn("s", "a", "user", "discount cash flow", Instant.now());
        idx.addTurn("s", "a", "user", "discounted at WACC", Instant.now());

        var hits = idx.search("discount*", null, 10);
        assertThat(hits).hasSize(2);
    }

    @Test
    void forgetSessionRemovesAllTurns(@TempDir Path tempDir) {
        SessionIndex idx = new SessionIndex(tempDir.resolve("index.db"));
        idx.addTurn("keep", "a", "user", "ACME", Instant.now());
        idx.addTurn("forget", "a", "user", "ACME", Instant.now());
        idx.addTurn("forget", "a", "assistant", "ACME response", Instant.now());

        int deleted = idx.forgetSession("forget");
        assertThat(deleted).isEqualTo(2);
        assertThat(idx.totalTurns()).isEqualTo(1);
    }

    @Test
    void limitHonoured(@TempDir Path tempDir) {
        SessionIndex idx = new SessionIndex(tempDir.resolve("index.db"));
        for (int i = 0; i < 20; i++) {
            idx.addTurn("s", "a", "user", "common keyword " + i, Instant.now());
        }
        assertThat(idx.search("common", null, 5)).hasSize(5);
        assertThat(idx.search("common", null, 100)).hasSize(20);
    }

    @Test
    void indexPersistsAcrossInstances(@TempDir Path tempDir) {
        Path dbPath = tempDir.resolve("persist.db");
        try (SessionIndex idx1 = new SessionIndex(dbPath)) {
            idx1.addTurn("s", "a", "user", "persistent data", Instant.now());
        }
        try (SessionIndex idx2 = new SessionIndex(dbPath)) {
            assertThat(idx2.totalTurns()).isEqualTo(1);
            assertThat(idx2.search("persistent", null, 10)).hasSize(1);
        }
    }
}
