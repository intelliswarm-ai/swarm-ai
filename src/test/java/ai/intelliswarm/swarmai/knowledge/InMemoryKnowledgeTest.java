package ai.intelliswarm.swarmai.knowledge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("InMemoryKnowledge Tests")
class InMemoryKnowledgeTest {

    private InMemoryKnowledge knowledge;

    @BeforeEach
    void setUp() {
        knowledge = new InMemoryKnowledge();
    }

    @Nested
    @DisplayName("addSource()")
    class AddSourceTests {

        @Test
        @DisplayName("stores content")
        void addSource_storesContent() {
            knowledge.addSource("doc-1", "Document content", null);

            assertEquals(1, knowledge.size());
            assertTrue(knowledge.hasSource("doc-1"));
        }

        @Test
        @DisplayName("stores with metadata")
        void addSource_storesWithMetadata() {
            Map<String, Object> metadata = Map.of("author", "John", "version", 1);
            knowledge.addSource("doc-1", "Content", metadata);

            Map<String, Object> storedMetadata = knowledge.getSourceMetadata("doc-1");
            assertEquals("John", storedMetadata.get("author"));
            assertEquals(1, storedMetadata.get("version"));
        }

        @Test
        @DisplayName("throws exception for null source ID")
        void addSource_withNullSourceId_throwsException() {
            assertThrows(IllegalArgumentException.class, () ->
                    knowledge.addSource(null, "Content", null));
        }

        @Test
        @DisplayName("throws exception for blank source ID")
        void addSource_withBlankSourceId_throwsException() {
            assertThrows(IllegalArgumentException.class, () ->
                    knowledge.addSource("   ", "Content", null));
        }

        @Test
        @DisplayName("throws exception for null content")
        void addSource_withNullContent_throwsException() {
            assertThrows(IllegalArgumentException.class, () ->
                    knowledge.addSource("doc-1", null, null));
        }

        @Test
        @DisplayName("overwrites existing source")
        void addSource_overwritesExisting() {
            knowledge.addSource("doc-1", "Original content", null);
            knowledge.addSource("doc-1", "Updated content", null);

            assertEquals(1, knowledge.size());
            assertEquals("Updated content", knowledge.getSourceContent("doc-1"));
        }
    }

    @Nested
    @DisplayName("query()")
    class QueryTests {

        @Test
        @DisplayName("returns relevant content")
        void query_returnsRelevantContent() {
            knowledge.addSource("ai-doc", "Artificial intelligence and machine learning", null);
            knowledge.addSource("cooking-doc", "Recipes and cooking tips", null);

            String result = knowledge.query("artificial intelligence");

            assertTrue(result.contains("Artificial intelligence"));
        }

        @Test
        @DisplayName("returns empty when no match")
        void query_returnsEmptyWhenNoMatch() {
            knowledge.addSource("doc-1", "Some content", null);

            String result = knowledge.query("nonexistent topic");

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("is case insensitive")
        void query_isCaseInsensitive() {
            knowledge.addSource("doc-1", "UPPERCASE CONTENT", null);

            String result = knowledge.query("uppercase");

            assertFalse(result.isEmpty());
        }

        @Test
        @DisplayName("returns empty for null query")
        void query_withNullQuery_returnsEmpty() {
            knowledge.addSource("doc-1", "Content", null);

            String result = knowledge.query(null);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("returns empty for blank query")
        void query_withBlankQuery_returnsEmpty() {
            knowledge.addSource("doc-1", "Content", null);

            String result = knowledge.query("   ");

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("returns most relevant source")
        void query_returnsMostRelevant() {
            knowledge.addSource("doc-1", "AI is great", null);
            knowledge.addSource("doc-2", "AI and AI systems are AI powered", null);

            String result = knowledge.query("AI");

            // doc-2 has more occurrences of "AI"
            assertTrue(result.contains("AI systems"));
        }
    }

    @Nested
    @DisplayName("search()")
    class SearchTests {

        @Test
        @DisplayName("finds matching sources")
        void search_findsMatchingSources() {
            knowledge.addSource("doc-1", "Machine learning algorithms", null);
            knowledge.addSource("doc-2", "Deep learning models", null);
            knowledge.addSource("doc-3", "Cooking recipes", null);

            List<String> results = knowledge.search("learning", 10);

            assertEquals(2, results.size());
        }

        @Test
        @DisplayName("limits results")
        void search_limitResults() {
            knowledge.addSource("doc-1", "Python programming", null);
            knowledge.addSource("doc-2", "Python scripting", null);
            knowledge.addSource("doc-3", "Python automation", null);

            List<String> results = knowledge.search("Python", 2);

            assertEquals(2, results.size());
        }

        @Test
        @DisplayName("returns empty for no matches")
        void search_returnsEmptyForNoMatches() {
            knowledge.addSource("doc-1", "Some content", null);

            List<String> results = knowledge.search("nonexistent", 10);

            assertTrue(results.isEmpty());
        }

        @Test
        @DisplayName("orders by relevance")
        void search_orderedByRelevance() {
            knowledge.addSource("doc-1", "Python", null);
            knowledge.addSource("doc-2", "Python Python Python", null);

            List<String> results = knowledge.search("Python", 10);

            // doc-2 has more matches, should be first
            assertEquals("Python Python Python", results.get(0));
        }
    }

    @Nested
    @DisplayName("removeSource()")
    class RemoveSourceTests {

        @Test
        @DisplayName("deletes source")
        void removeSource_deletesSource() {
            knowledge.addSource("doc-1", "Content 1", null);
            knowledge.addSource("doc-2", "Content 2", null);

            knowledge.removeSource("doc-1");

            assertEquals(1, knowledge.size());
            assertFalse(knowledge.hasSource("doc-1"));
            assertTrue(knowledge.hasSource("doc-2"));
        }

        @Test
        @DisplayName("handles null source ID")
        void removeSource_withNull_doesNothing() {
            knowledge.addSource("doc-1", "Content", null);

            knowledge.removeSource(null);

            assertEquals(1, knowledge.size());
        }

        @Test
        @DisplayName("handles nonexistent source")
        void removeSource_nonexistent_doesNothing() {
            knowledge.addSource("doc-1", "Content", null);

            knowledge.removeSource("nonexistent");

            assertEquals(1, knowledge.size());
        }
    }

    @Nested
    @DisplayName("getSources()")
    class GetSourcesTests {

        @Test
        @DisplayName("returns all source IDs")
        void getSources_returnsAllIds() {
            knowledge.addSource("doc-1", "Content 1", null);
            knowledge.addSource("doc-2", "Content 2", null);
            knowledge.addSource("doc-3", "Content 3", null);

            List<String> sources = knowledge.getSources();

            assertEquals(3, sources.size());
            assertTrue(sources.contains("doc-1"));
            assertTrue(sources.contains("doc-2"));
            assertTrue(sources.contains("doc-3"));
        }

        @Test
        @DisplayName("returns empty list when empty")
        void getSources_whenEmpty_returnsEmptyList() {
            List<String> sources = knowledge.getSources();

            assertTrue(sources.isEmpty());
        }
    }

    @Nested
    @DisplayName("hasSource()")
    class HasSourceTests {

        @Test
        @DisplayName("returns true when source exists")
        void hasSource_whenExists_returnsTrue() {
            knowledge.addSource("doc-1", "Content", null);

            assertTrue(knowledge.hasSource("doc-1"));
        }

        @Test
        @DisplayName("returns false when source doesn't exist")
        void hasSource_whenNotExists_returnsFalse() {
            assertFalse(knowledge.hasSource("nonexistent"));
        }

        @Test
        @DisplayName("returns false for null")
        void hasSource_withNull_returnsFalse() {
            knowledge.addSource("doc-1", "Content", null);

            assertFalse(knowledge.hasSource(null));
        }
    }

    @Nested
    @DisplayName("Helper Methods")
    class HelperMethodsTests {

        @Test
        @DisplayName("getSourceContent returns content")
        void getSourceContent_returnsContent() {
            knowledge.addSource("doc-1", "Test content", null);

            assertEquals("Test content", knowledge.getSourceContent("doc-1"));
        }

        @Test
        @DisplayName("getSourceContent returns null for unknown")
        void getSourceContent_unknownSource_returnsNull() {
            assertNull(knowledge.getSourceContent("unknown"));
        }

        @Test
        @DisplayName("size returns correct count")
        void size_returnsCorrectCount() {
            assertEquals(0, knowledge.size());

            knowledge.addSource("doc-1", "Content", null);
            assertEquals(1, knowledge.size());

            knowledge.addSource("doc-2", "Content", null);
            assertEquals(2, knowledge.size());
        }

        @Test
        @DisplayName("isEmpty works correctly")
        void isEmpty_worksCorrectly() {
            assertTrue(knowledge.isEmpty());

            knowledge.addSource("doc-1", "Content", null);
            assertFalse(knowledge.isEmpty());
        }

        @Test
        @DisplayName("clear removes all sources")
        void clear_removesAllSources() {
            knowledge.addSource("doc-1", "Content 1", null);
            knowledge.addSource("doc-2", "Content 2", null);

            knowledge.clear();

            assertTrue(knowledge.isEmpty());
            assertEquals(0, knowledge.size());
        }
    }
}
