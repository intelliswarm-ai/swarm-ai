package ai.intelliswarm.swarmai.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("InMemoryMemory Tests")
class InMemoryMemoryTest {

    private InMemoryMemory memory;

    @BeforeEach
    void setUp() {
        memory = new InMemoryMemory();
    }

    @Nested
    @DisplayName("save()")
    class SaveTests {

        @Test
        @DisplayName("stores entry")
        void save_storesEntry() {
            memory.save("agent-1", "Test content", null);

            assertEquals(1, memory.size());
        }

        @Test
        @DisplayName("stores with agent ID")
        void save_withAgentId_storesForAgent() {
            memory.save("agent-1", "Content 1", null);
            memory.save("agent-1", "Content 2", null);
            memory.save("agent-2", "Content 3", null);

            assertEquals(3, memory.size());
            assertEquals(2, memory.sizeForAgent("agent-1"));
            assertEquals(1, memory.sizeForAgent("agent-2"));
        }

        @Test
        @DisplayName("stores with metadata")
        void save_withMetadata_storesMetadata() {
            Map<String, Object> metadata = Map.of("key", "value");
            memory.save("agent-1", "Content", metadata);

            assertEquals(1, memory.size());
        }

        @Test
        @DisplayName("handles null agent ID")
        void save_withNullAgentId_storesGlobally() {
            memory.save(null, "Global content", null);

            assertEquals(1, memory.size());
            assertEquals(0, memory.sizeForAgent("any-agent"));
        }
    }

    @Nested
    @DisplayName("search()")
    class SearchTests {

        @Test
        @DisplayName("finds matching content")
        void search_findsMatching() {
            memory.save("agent-1", "The quick brown fox", null);
            memory.save("agent-1", "The lazy dog", null);
            memory.save("agent-1", "Another content", null);

            List<String> results = memory.search("quick", 10);

            assertEquals(1, results.size());
            assertTrue(results.get(0).contains("quick"));
        }

        @Test
        @DisplayName("returns empty when no match")
        void search_returnsEmptyWhenNoMatch() {
            memory.save("agent-1", "Some content", null);

            List<String> results = memory.search("nonexistent", 10);

            assertTrue(results.isEmpty());
        }

        @Test
        @DisplayName("limits results")
        void search_limitResults() {
            memory.save("agent-1", "Test content 1", null);
            memory.save("agent-1", "Test content 2", null);
            memory.save("agent-1", "Test content 3", null);

            List<String> results = memory.search("Test", 2);

            assertEquals(2, results.size());
        }

        @Test
        @DisplayName("is case insensitive")
        void search_isCaseInsensitive() {
            memory.save("agent-1", "UPPERCASE content", null);

            List<String> results = memory.search("uppercase", 10);

            assertEquals(1, results.size());
        }

        @Test
        @DisplayName("returns empty for null query")
        void search_withNullQuery_returnsEmpty() {
            memory.save("agent-1", "Content", null);

            List<String> results = memory.search(null, 10);

            assertTrue(results.isEmpty());
        }

        @Test
        @DisplayName("returns empty for blank query")
        void search_withBlankQuery_returnsEmpty() {
            memory.save("agent-1", "Content", null);

            List<String> results = memory.search("   ", 10);

            assertTrue(results.isEmpty());
        }
    }

    @Nested
    @DisplayName("getRecentMemories()")
    class GetRecentMemoriesTests {

        @Test
        @DisplayName("returns latest entries")
        void getRecentMemories_returnsLatest() throws InterruptedException {
            memory.save("agent-1", "Old content", null);
            Thread.sleep(10); // Ensure different timestamps
            memory.save("agent-1", "New content", null);

            List<String> results = memory.getRecentMemories("agent-1", 1);

            assertEquals(1, results.size());
            assertEquals("New content", results.get(0));
        }

        @Test
        @DisplayName("respects limit")
        void getRecentMemories_respectsLimit() {
            for (int i = 0; i < 10; i++) {
                memory.save("agent-1", "Content " + i, null);
            }

            List<String> results = memory.getRecentMemories("agent-1", 3);

            assertEquals(3, results.size());
        }

        @Test
        @DisplayName("filters by agent ID")
        void getRecentMemories_filtersByAgent() {
            memory.save("agent-1", "Agent 1 content", null);
            memory.save("agent-2", "Agent 2 content", null);

            List<String> results = memory.getRecentMemories("agent-1", 10);

            assertEquals(1, results.size());
            assertEquals("Agent 1 content", results.get(0));
        }

        @Test
        @DisplayName("returns all memories for null agent ID")
        void getRecentMemories_withNullAgentId_returnsAll() {
            memory.save("agent-1", "Content 1", null);
            memory.save("agent-2", "Content 2", null);

            List<String> results = memory.getRecentMemories(null, 10);

            assertEquals(2, results.size());
        }

        @Test
        @DisplayName("returns empty for unknown agent")
        void getRecentMemories_unknownAgent_returnsEmpty() {
            memory.save("agent-1", "Content", null);

            List<String> results = memory.getRecentMemories("unknown-agent", 10);

            assertTrue(results.isEmpty());
        }
    }

    @Nested
    @DisplayName("clear()")
    class ClearTests {

        @Test
        @DisplayName("removes all entries")
        void clear_removesAll() {
            memory.save("agent-1", "Content 1", null);
            memory.save("agent-2", "Content 2", null);

            assertEquals(2, memory.size());

            memory.clear();

            assertEquals(0, memory.size());
            assertTrue(memory.isEmpty());
        }
    }

    @Nested
    @DisplayName("clearForAgent()")
    class ClearForAgentTests {

        @Test
        @DisplayName("removes only agent memories")
        void clearForAgent_removesOnlyAgentMemories() {
            memory.save("agent-1", "Content 1", null);
            memory.save("agent-1", "Content 2", null);
            memory.save("agent-2", "Content 3", null);

            memory.clearForAgent("agent-1");

            assertEquals(1, memory.size());
            assertEquals(0, memory.sizeForAgent("agent-1"));
            assertEquals(1, memory.sizeForAgent("agent-2"));
        }

        @Test
        @DisplayName("handles null agent ID")
        void clearForAgent_withNull_doesNothing() {
            memory.save("agent-1", "Content", null);

            memory.clearForAgent(null);

            assertEquals(1, memory.size());
        }
    }

    @Nested
    @DisplayName("size() and isEmpty()")
    class SizeTests {

        @Test
        @DisplayName("returns correct count")
        void size_returnsCorrectCount() {
            assertEquals(0, memory.size());

            memory.save("agent-1", "Content 1", null);
            assertEquals(1, memory.size());

            memory.save("agent-2", "Content 2", null);
            assertEquals(2, memory.size());
        }

        @Test
        @DisplayName("isEmpty returns true when empty")
        void isEmpty_whenEmpty_returnsTrue() {
            assertTrue(memory.isEmpty());
        }

        @Test
        @DisplayName("isEmpty returns false when not empty")
        void isEmpty_whenNotEmpty_returnsFalse() {
            memory.save("agent-1", "Content", null);

            assertFalse(memory.isEmpty());
        }
    }

    @Nested
    @DisplayName("Keyword retrieval")
    class KeywordRetrievalTests {

        @Test
        @DisplayName("full task descriptions match stored task summaries by keywords")
        void search_taskDescriptionQuery_findsRelatedMemory() {
            // Stored the way Agent.saveToMemory writes it
            memory.save("agent-1", "Task: Analyze quarterly revenue figures\nResult: Revenue grew 12%", null);
            memory.save("agent-1", "Task: Draft marketing email\nResult: Email drafted", null);

            // A different-but-related task description: substring match would find nothing
            List<String> results = memory.search("Summarize the revenue trends for the last quarter", 5);

            assertFalse(results.isEmpty(), "keyword OR search should match on 'revenue'");
            assertTrue(results.get(0).contains("revenue figures"));
        }

        @Test
        @DisplayName("ranks entries matching more keywords first")
        void search_ranksByKeywordCount() {
            memory.save("agent-1", "database migration completed", null);
            memory.save("agent-1", "database migration rollback plan tested", null);

            List<String> results = memory.search("database migration rollback", 2);

            assertEquals(2, results.size());
            assertTrue(results.get(0).contains("rollback"), "entry matching 3 keywords should rank first");
        }

        @Test
        @DisplayName("stopword-only queries fall back to raw matching")
        void search_stopwordOnlyQuery_usesRawFallback() {
            memory.save("agent-1", "this and that", null);

            assertFalse(memory.search("and", 5).isEmpty());
        }
    }

    @Nested
    @DisplayName("Bounded growth")
    class EvictionTests {

        @Test
        @DisplayName("evicts oldest entries beyond maxEntries")
        void save_beyondCap_evictsOldest() {
            InMemoryMemory bounded = new InMemoryMemory(5);
            for (int i = 0; i < 8; i++) {
                bounded.save("agent-1", "entry-" + i, null);
            }

            assertEquals(5, bounded.size());
            assertEquals(5, bounded.sizeForAgent("agent-1"));
            List<String> recent = bounded.getRecentMemories("agent-1", 10);
            assertEquals("entry-7", recent.get(0));
            assertFalse(recent.contains("entry-0"), "oldest entries must be evicted");
            assertFalse(recent.contains("entry-2"));
        }

        @Test
        @DisplayName("rejects non-positive cap")
        void constructor_rejectsNonPositiveCap() {
            assertThrows(IllegalArgumentException.class, () -> new InMemoryMemory(0));
        }
    }

    @Nested
    @DisplayName("searchByAgentPrefix()")
    class AgentPrefixTests {

        @Test
        @DisplayName("scopes results to matching agent prefix")
        void searchByAgentPrefix_scopesResults() {
            memory.save("tenant-a::agent-1", "shared keyword alpha", null);
            memory.save("tenant-b::agent-1", "shared keyword beta", null);

            List<String> results = memory.searchByAgentPrefix("shared keyword", "tenant-a::", 10);

            assertEquals(1, results.size());
            assertTrue(results.get(0).contains("alpha"));
        }

        @Test
        @DisplayName("interface default fails closed instead of leaking unscoped results")
        void defaultImplementation_failsClosed() {
            Memory custom = new Memory() {
                @Override
                public void save(String agentId, String content, Map<String, Object> metadata) {
                }

                @Override
                public List<String> search(String query, int limit) {
                    return List.of("LEAKED cross-tenant result");
                }

                @Override
                public List<String> getRecentMemories(String agentId, int limit) {
                    return List.of();
                }

                @Override
                public void clear() {
                }

                @Override
                public void clearForAgent(String agentId) {
                }

                @Override
                public int size() {
                    return 0;
                }

                @Override
                public boolean isEmpty() {
                    return true;
                }
            };

            assertTrue(custom.searchByAgentPrefix("anything", "tenant-a::", 10).isEmpty(),
                    "default must not fall back to unscoped search");
        }
    }

    @Nested
    @DisplayName("Thread Safety")
    class ThreadSafetyTests {

        @Test
        @DisplayName("handles concurrent saves")
        void concurrentSaves_noErrors() throws InterruptedException {
            int threadCount = 10;
            int savesPerThread = 100;
            Thread[] threads = new Thread[threadCount];

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                threads[t] = new Thread(() -> {
                    for (int i = 0; i < savesPerThread; i++) {
                        memory.save("agent-" + threadId, "Content " + i, null);
                    }
                });
            }

            for (Thread thread : threads) {
                thread.start();
            }

            for (Thread thread : threads) {
                thread.join();
            }

            assertEquals(threadCount * savesPerThread, memory.size());
        }
    }
}
