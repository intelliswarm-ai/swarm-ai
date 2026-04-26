package ai.intelliswarm.swarmai.knowledge.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("RagPipeline Tests")
class RagPipelineTest {

    private VectorStore vectorStore;
    private ChatClient chatClient;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        chatClient = mockChatClient("The capital of Japan is Tokyo [source: japan.md #0].");
    }

    /** Builds a ChatClient mock returning the given canned text via .call().content(). */
    private static ChatClient mockChatClient(String response) {
        ChatClient client = mock(ChatClient.class, Mockito.RETURNS_DEEP_STUBS);
        when(client.prompt().system(anyString()).user(anyString()).call().content())
                .thenReturn(response);
        return client;
    }

    private static Document doc(String chunkId, String text, String sourceId, int chunk) {
        return new Document(chunkId, text, Map.of("sourceId", sourceId,
                "originalText", text, "chunk", chunk));
    }

    @Nested
    @DisplayName("ingestText()")
    class IngestTests {

        @Test
        @DisplayName("splits content and adds to vector store")
        void ingest_splits_and_adds() {
            RagPipeline rag = RagPipeline.builder()
                    .vectorStore(vectorStore).chatClient(chatClient).build();

            String content = "Paragraph one with some content.\n\nParagraph two with more content.";
            rag.ingestText("doc.md", content);

            ArgumentCaptor<List<Document>> cap = ArgumentCaptor.forClass(List.class);
            verify(vectorStore).add(cap.capture());
            List<Document> docs = cap.getValue();
            assertFalse(docs.isEmpty());
            assertTrue(docs.get(0).getId().startsWith("doc.md#"));
        }

        @Test
        @DisplayName("contextualPrefix=true prepends [filename] before embedding")
        void ingest_with_contextual_prefix() {
            RagPipeline rag = RagPipeline.builder()
                    .vectorStore(vectorStore).chatClient(chatClient)
                    .config(RagConfig.defaults().withContextualPrefix(true))
                    .build();

            rag.ingestText("japan.md", "Tokyo is the capital of Japan.");

            ArgumentCaptor<List<Document>> cap = ArgumentCaptor.forClass(List.class);
            verify(vectorStore).add(cap.capture());
            assertTrue(cap.getValue().get(0).getText().startsWith("[japan.md] "));
            // originalText (in metadata) is the raw chunk
            assertEquals("Tokyo is the capital of Japan.",
                    cap.getValue().get(0).getMetadata().get("originalText"));
        }

        @Test
        @DisplayName("contextualPrefix=false embeds raw chunk")
        void ingest_without_contextual_prefix() {
            RagPipeline rag = RagPipeline.builder()
                    .vectorStore(vectorStore).chatClient(chatClient)
                    .config(RagConfig.defaults().withContextualPrefix(false))
                    .build();

            rag.ingestText("japan.md", "Tokyo is the capital of Japan.");

            ArgumentCaptor<List<Document>> cap = ArgumentCaptor.forClass(List.class);
            verify(vectorStore).add(cap.capture());
            assertEquals("Tokyo is the capital of Japan.", cap.getValue().get(0).getText());
        }

        @Test
        @DisplayName("blank content is a no-op")
        void blank_content_no_op() {
            RagPipeline rag = RagPipeline.builder()
                    .vectorStore(vectorStore).chatClient(chatClient).build();
            rag.ingestText("empty.md", "");
            rag.ingestText("blank.md", "    ");
            verifyNoInteractions(vectorStore);
        }

        @Test
        @DisplayName("vector store failure is logged not thrown")
        void ingest_vector_failure_swallowed() {
            doThrow(new RuntimeException("chroma down")).when(vectorStore).add(any());
            RagPipeline rag = RagPipeline.builder()
                    .vectorStore(vectorStore).chatClient(chatClient).build();
            // should not throw
            assertDoesNotThrow(() -> rag.ingestText("doc.md", "some content"));
        }
    }

    @Nested
    @DisplayName("query()")
    class QueryTests {

        @Test
        @DisplayName("returns answer + parsed citations")
        void query_parses_citations() {
            when(vectorStore.similaritySearch(any(SearchRequest.class)))
                    .thenReturn(List.of(doc("japan.md#0", "Tokyo is the capital of Japan.", "japan.md", 0)));

            RagPipeline rag = RagPipeline.builder()
                    .vectorStore(vectorStore).chatClient(chatClient).build();
            rag.ingestText("japan.md", "Tokyo is the capital of Japan.");

            RagAnswer a = rag.query("Capital of Japan?");

            assertEquals("Capital of Japan?", a.question());
            assertTrue(a.answer().contains("Tokyo"));
            assertEquals(1, a.citations().size());
            assertEquals("japan.md", a.citations().get(0).sourceId());
            assertEquals(0, a.citations().get(0).chunkIndex());
            assertFalse(a.refused());
            assertTrue(a.durationMs() >= 0);
        }

        @Test
        @DisplayName("dedupes repeated citation tags")
        void query_dedupes_citations() {
            ChatClient client = mockChatClient(
                    "Tokyo [source: japan.md #0]. It's also the capital [source: japan.md #0].");
            when(vectorStore.similaritySearch(any(SearchRequest.class)))
                    .thenReturn(List.of(doc("japan.md#0", "Tokyo is the capital", "japan.md", 0)));

            RagPipeline rag = RagPipeline.builder()
                    .vectorStore(vectorStore).chatClient(client).build();
            rag.ingestText("japan.md", "Tokyo is the capital of Japan.");

            RagAnswer a = rag.query("Capital?");
            assertEquals(1, a.citations().size(), "duplicate [source:] tags must dedupe");
        }

        @Test
        @DisplayName("detects refusal phrase")
        void query_detects_refusal() {
            ChatClient client = mockChatClient(RagPrompts.REFUSAL);
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

            RagPipeline rag = RagPipeline.builder()
                    .vectorStore(vectorStore).chatClient(client).build();

            RagAnswer a = rag.query("Some question?");
            assertTrue(a.refused());
            assertTrue(a.citations().isEmpty());
        }

        @Test
        @DisplayName("multiple citations are all parsed")
        void query_multiple_citations() {
            ChatClient client = mockChatClient(
                    "K-means is partition-based [source: ml.pdf #5]. DBSCAN is density-based [source: ml.pdf #7].");
            when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                    doc("ml.pdf#5", "K-means partition", "ml.pdf", 5),
                    doc("ml.pdf#7", "DBSCAN density", "ml.pdf", 7)));

            RagPipeline rag = RagPipeline.builder()
                    .vectorStore(vectorStore).chatClient(client).build();
            rag.ingestText("ml.pdf", "K-means clustering. DBSCAN clustering.");

            RagAnswer a = rag.query("What clustering methods?");
            assertEquals(2, a.citations().size());
            assertEquals(5, a.citations().get(0).chunkIndex());
            assertEquals(7, a.citations().get(1).chunkIndex());
        }
    }

    @Nested
    @DisplayName("UTF-16 sanitization")
    class SanitizationTests {

        @Test
        @DisplayName("preserves valid surrogate pairs (e.g. emoji)")
        void preserves_valid_surrogates() {
            // U+1F600 = "😀", encoded as high+low surrogate pair
            String emoji = "Hello 😀 World";
            assertEquals(emoji, RagPipeline.sanitizeUtf16(emoji));
        }

        @Test
        @DisplayName("replaces lone high surrogate with U+FFFD")
        void replaces_lone_high_surrogate() {
            String broken = "Hello \uD83D World";  // high surrogate without low
            String cleaned = RagPipeline.sanitizeUtf16(broken);
            assertFalse(cleaned.contains("\uD83D"));
            assertTrue(cleaned.contains("�"));
        }

        @Test
        @DisplayName("replaces lone low surrogate with U+FFFD")
        void replaces_lone_low_surrogate() {
            String broken = "Hello \uDE00 World";  // low surrogate without high
            String cleaned = RagPipeline.sanitizeUtf16(broken);
            assertFalse(cleaned.contains("\uDE00"));
            assertTrue(cleaned.contains("�"));
        }

        @Test
        @DisplayName("null/empty are no-ops")
        void null_empty_no_op() {
            assertNull(RagPipeline.sanitizeUtf16(null));
            assertEquals("", RagPipeline.sanitizeUtf16(""));
        }

        @Test
        @DisplayName("plain ASCII unchanged")
        void plain_ascii_unchanged() {
            assertEquals("Hello world.", RagPipeline.sanitizeUtf16("Hello world."));
        }
    }

    @Nested
    @DisplayName("builder")
    class BuilderTests {

        @Test
        @DisplayName("requires vectorStore")
        void requires_vector_store() {
            assertThrows(NullPointerException.class, () -> RagPipeline.builder()
                    .chatClient(chatClient).build());
        }

        @Test
        @DisplayName("requires chatClient")
        void requires_chat_client() {
            assertThrows(NullPointerException.class, () -> RagPipeline.builder()
                    .vectorStore(vectorStore).build());
        }

        @Test
        @DisplayName("uses default config when none supplied")
        void uses_default_config() {
            RagPipeline rag = RagPipeline.builder()
                    .vectorStore(vectorStore).chatClient(chatClient).build();
            // Smoke: ingest + query don't throw
            rag.ingestText("doc.md", "content");
            assertDoesNotThrow(() -> rag.query("anything"));
        }
    }
}
