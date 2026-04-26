package ai.intelliswarm.swarmai.knowledge.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("HybridRetriever Tests")
class HybridRetrieverTest {

    private VectorStore vectorStore;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
    }

    private static Document doc(String id, String text) {
        return new Document(id, text, Map.of("sourceId", id.split("#")[0],
                "originalText", text,
                "chunk", Integer.parseInt(id.split("#")[1])));
    }

    @Nested
    @DisplayName("vector-only retrieval")
    class VectorOnlyTests {

        @Test
        @DisplayName("returns top-K from vector store when BM25 is off")
        void vector_only_path() {
            HybridRetriever r = new HybridRetriever(vectorStore, false);
            when(vectorStore.similaritySearch(any(SearchRequest.class)))
                    .thenReturn(List.of(doc("docA#0", "K-means clustering"), doc("docA#1", "DBSCAN")));

            List<HybridRetriever.ChunkRecord> hits = r.retrieve("clustering", 5);

            assertEquals(2, hits.size());
            assertEquals("docA#0", hits.get(0).chunkId());
            verify(vectorStore, times(1)).similaritySearch(any(SearchRequest.class));
        }

        @Test
        @DisplayName("vector store failure does not throw")
        void vector_failure_swallowed() {
            HybridRetriever r = new HybridRetriever(vectorStore, false);
            when(vectorStore.similaritySearch(any(SearchRequest.class)))
                    .thenThrow(new RuntimeException("chroma down"));

            // should not throw
            List<HybridRetriever.ChunkRecord> hits = r.retrieve("anything", 5);
            assertTrue(hits.isEmpty());
        }
    }

    @Nested
    @DisplayName("BM25 + vector RRF fusion")
    class HybridTests {

        @Test
        @DisplayName("BM25 catches exact-keyword chunks vector store missed")
        void bm25_catches_exact_keyword() {
            HybridRetriever r = new HybridRetriever(vectorStore, true);
            // Index 3 chunks for BM25 — only one mentions "ID3"
            r.index("alg#0", "alg", "K-means clustering algorithm", 0);
            r.index("alg#1", "alg", "DBSCAN density-based clustering", 1);
            r.index("alg#2", "alg", "ID3 decision tree induction algorithm", 2);

            // Vector store returns chunks unrelated to ID3
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                    doc("alg#0", "K-means clustering algorithm"),
                    doc("alg#1", "DBSCAN density-based clustering")));

            List<HybridRetriever.ChunkRecord> hits = r.retrieve("ID3 decision tree", 5);

            // BM25 should surface alg#2 (only chunk with "ID3" + "decision" + "tree")
            assertTrue(hits.stream().anyMatch(h -> h.chunkId().equals("alg#2")),
                    "expected ID3 chunk surfaced via BM25; got " + hits.stream().map(HybridRetriever.ChunkRecord::chunkId).toList());
        }

        @Test
        @DisplayName("RRF surfaces a chunk vector ranks low when BM25 ranks it high")
        void rrf_fused_promotes_bm25_winner() {
            HybridRetriever r = new HybridRetriever(vectorStore, true);
            r.index("doc#0", "doc", "common-fluff text content here filler", 0);
            r.index("doc#1", "doc", "common-fluff with extra padding only", 1);
            r.index("doc#2", "doc", "ID3 algorithm decision-tree induction details", 2);

            // Vector retrieval ranks the two tangential chunks first; doc#2 (the
            // exact-keyword match) doesn't make it into top-2 vector results.
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                    doc("doc#0", "common-fluff text content here filler"),
                    doc("doc#1", "common-fluff with extra padding only")));

            // BM25 for "ID3 decision-tree induction" should rank doc#2 first
            // (only chunk with all three distinctive tokens), promoting it into
            // the fused top-K via RRF even though vector missed it.
            List<HybridRetriever.ChunkRecord> hits = r.retrieve("ID3 decision-tree induction", 3);

            assertTrue(hits.stream().anyMatch(h -> h.chunkId().equals("doc#2")),
                    "doc#2 (BM25 winner) should make the fused top-K; got "
                            + hits.stream().map(HybridRetriever.ChunkRecord::chunkId).toList());
        }

        @Test
        @DisplayName("dedupes when same chunk appears in both retrievers")
        void dedupe_across_retrievers() {
            HybridRetriever r = new HybridRetriever(vectorStore, true);
            r.index("doc#0", "doc", "alpha beta", 0);
            when(vectorStore.similaritySearch(any(SearchRequest.class)))
                    .thenReturn(List.of(doc("doc#0", "alpha beta")));

            List<HybridRetriever.ChunkRecord> hits = r.retrieve("alpha", 5);

            assertEquals(1, hits.size(), "same chunk in both retrievers must dedupe");
            assertEquals("doc#0", hits.get(0).chunkId());
        }
    }

    @Nested
    @DisplayName("edge cases")
    class EdgeTests {

        @Test
        @DisplayName("blank query returns empty")
        void blank_query() {
            HybridRetriever r = new HybridRetriever(vectorStore, true);
            assertTrue(r.retrieve("",  5).isEmpty());
            assertTrue(r.retrieve("   ", 5).isEmpty());
            assertTrue(r.retrieve(null, 5).isEmpty());
            verifyNoInteractions(vectorStore);
        }

        @Test
        @DisplayName("topK = 0 returns empty")
        void zero_topk() {
            HybridRetriever r = new HybridRetriever(vectorStore, true);
            assertTrue(r.retrieve("anything", 0).isEmpty());
        }

        @Test
        @DisplayName("unindex removes from BM25 index")
        void unindex_removes() {
            HybridRetriever r = new HybridRetriever(vectorStore, true);
            r.index("x#0", "x", "unique-keyword present here", 0);
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

            // Sanity: BM25 finds it
            assertFalse(r.retrieve("unique-keyword", 5).isEmpty());

            r.unindex("x#0");
            assertTrue(r.retrieve("unique-keyword", 5).isEmpty(),
                    "after unindex, BM25 should not match");
        }
    }
}
