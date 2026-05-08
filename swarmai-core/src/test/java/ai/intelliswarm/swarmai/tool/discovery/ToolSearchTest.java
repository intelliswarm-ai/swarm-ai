package ai.intelliswarm.swarmai.tool.discovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for the deferred tool-loading / ToolSearch primitive. */
class ToolSearchTest {

    // =================================================================
    // CatalogEntry record
    // =================================================================
    @Nested
    @DisplayName("CatalogEntry record")
    class EntryRecord {

        @Test
        void simpleFactory() {
            CatalogEntry e = CatalogEntry.of("calculator", "do math");
            assertEquals("calculator", e.name());
            assertEquals("do math", e.description());
            assertTrue(e.keywords().isEmpty());
            assertEquals("", e.schemaSummary());
        }

        @Test
        void factoryWithKeywords() {
            CatalogEntry e = CatalogEntry.of("calculator", "do math",
                    List.of("arithmetic", "compute"));
            assertEquals(2, e.keywords().size());
        }

        @Test
        void fullConstructorAcceptsSchemaSummary() {
            CatalogEntry e = new CatalogEntry("calculator", "do math",
                    List.of("arithmetic"), "args: expression (string)");
            assertEquals("args: expression (string)", e.schemaSummary());
        }

        @Test
        void blankNameRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> CatalogEntry.of("", "desc"));
            assertThrows(IllegalArgumentException.class,
                    () -> CatalogEntry.of("  ", "desc"));
        }

        @Test
        void blankDescriptionRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> CatalogEntry.of("name", ""));
            assertThrows(IllegalArgumentException.class,
                    () -> CatalogEntry.of("name", "  "));
        }

        @Test
        void nullKeywordsBecomesEmpty() {
            CatalogEntry e = new CatalogEntry("name", "desc", null, "");
            assertTrue(e.keywords().isEmpty());
        }

        @Test
        void keywordsAreImmutable() {
            CatalogEntry e = CatalogEntry.of("name", "desc",
                    List.of("a", "b"));
            assertThrows(UnsupportedOperationException.class,
                    () -> e.keywords().add("c"));
        }

        @Test
        void nullSchemaBecomesEmpty() {
            CatalogEntry e = new CatalogEntry("name", "desc", List.of(), null);
            assertEquals("", e.schemaSummary());
        }
    }

    // =================================================================
    // ToolMatch record
    // =================================================================
    @Nested
    @DisplayName("ToolMatch record")
    class MatchRecord {

        @Test
        void validMatchCreated() {
            CatalogEntry e = CatalogEntry.of("a", "desc");
            ToolMatch m = new ToolMatch(e, 3.5);
            assertSame(e, m.entry());
            assertEquals(3.5, m.score());
        }

        @Test
        void zeroScoreAccepted() {
            assertDoesNotThrow(() -> new ToolMatch(CatalogEntry.of("a", "desc"), 0.0));
        }

        @Test
        void negativeScoreRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ToolMatch(CatalogEntry.of("a", "desc"), -0.1));
        }

        @Test
        void nanScoreRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ToolMatch(CatalogEntry.of("a", "desc"), Double.NaN));
        }
    }

    // =================================================================
    // ToolCatalog
    // =================================================================
    @Nested
    @DisplayName("ToolCatalog")
    class Catalog {

        @Test
        void registerAndLookup() {
            ToolCatalog cat = new ToolCatalog();
            CatalogEntry e = CatalogEntry.of("foo", "desc");
            cat.register(e);

            assertSame(e, cat.lookup("foo").orElseThrow());
            assertTrue(cat.contains("foo"));
            assertEquals(1, cat.size());
            assertFalse(cat.isEmpty());
        }

        @Test
        void registerReplacesPriorEntry() {
            ToolCatalog cat = new ToolCatalog();
            cat.register(CatalogEntry.of("foo", "v1"));
            cat.register(CatalogEntry.of("foo", "v2"));
            assertEquals("v2", cat.lookup("foo").orElseThrow().description());
            assertEquals(1, cat.size());
        }

        @Test
        void registerStrictFailsOnDuplicate() {
            ToolCatalog cat = new ToolCatalog();
            cat.registerStrict(CatalogEntry.of("foo", "v1"));
            assertThrows(IllegalArgumentException.class,
                    () -> cat.registerStrict(CatalogEntry.of("foo", "v2")));
        }

        @Test
        void unregisterRemoves() {
            ToolCatalog cat = new ToolCatalog();
            cat.register(CatalogEntry.of("foo", "desc"));
            assertTrue(cat.unregister("foo").isPresent());
            assertFalse(cat.contains("foo"));
        }

        @Test
        void allReturnsRegistrationOrder() {
            ToolCatalog cat = new ToolCatalog();
            cat.register(CatalogEntry.of("c", "third"));
            cat.register(CatalogEntry.of("a", "first"));
            cat.register(CatalogEntry.of("b", "second"));

            var names = cat.all().stream().map(CatalogEntry::name).toList();
            assertEquals(List.of("c", "a", "b"), names);
        }

        @Test
        void allIsImmutableSnapshot() {
            ToolCatalog cat = new ToolCatalog();
            cat.register(CatalogEntry.of("foo", "desc"));
            assertThrows(UnsupportedOperationException.class, () -> cat.all().clear());
        }

        @Test
        void emptyReports() {
            ToolCatalog cat = new ToolCatalog();
            assertTrue(cat.isEmpty());
            assertEquals(0, cat.size());
            assertTrue(cat.lookup("foo").isEmpty());
        }
    }

    // =================================================================
    // ToolSearchService — scoring + ranking
    // =================================================================
    @Nested
    @DisplayName("ToolSearchService")
    class Search {

        private ToolCatalog catalog() {
            ToolCatalog cat = new ToolCatalog();
            cat.register(new CatalogEntry("calculator",
                    "Perform arithmetic on a math expression.",
                    List.of("math", "arithmetic", "compute"),
                    "args: expression (string)"));
            cat.register(new CatalogEntry("file_read",
                    "Read the full contents of a file from disk.",
                    List.of("filesystem", "io"),
                    "args: path (string)"));
            cat.register(new CatalogEntry("file_write",
                    "Write content to a file on disk.",
                    List.of("filesystem", "io"),
                    "args: path (string), content (string)"));
            cat.register(new CatalogEntry("web_search",
                    "Search the web for a query.",
                    List.of("internet", "google"),
                    "args: query (string)"));
            cat.register(new CatalogEntry("statistics",
                    "Statistical analysis of a numeric array.",
                    List.of("math", "stats", "analytics"),
                    "args: data (array<number>)"));
            return cat;
        }

        @Test
        void emptyQueryReturnsEmpty() {
            ToolSearchService svc = new ToolSearchService(catalog());
            assertTrue(svc.search("").isEmpty());
            assertTrue(svc.search("   ").isEmpty());
            assertTrue(svc.search(null).isEmpty());
        }

        @Test
        void zeroOrNegativeMaxResultsReturnsEmpty() {
            ToolSearchService svc = new ToolSearchService(catalog());
            assertTrue(svc.search("math", 0).isEmpty());
            assertTrue(svc.search("math", -1).isEmpty());
        }

        @Test
        void exactNameMatchScoresHighest() {
            ToolSearchService svc = new ToolSearchService(catalog());
            List<ToolMatch> r = svc.search("calculator");
            assertFalse(r.isEmpty());
            assertEquals("calculator", r.get(0).entry().name());
        }

        @Test
        void keywordMatchOutranksDescriptionMatch() {
            // "math" is a keyword on calculator and statistics; the query
            // "math" should rank either of those above any tool that only
            // mentions math in description.
            ToolSearchService svc = new ToolSearchService(catalog());
            List<ToolMatch> r = svc.search("math");
            assertFalse(r.isEmpty());
            assertTrue(r.get(0).entry().keywords().contains("math"),
                    "top result should be a keyword match: " + r.get(0).entry().name());
        }

        @Test
        void multiTermQueryNarrows() {
            ToolSearchService svc = new ToolSearchService(catalog());
            // "file write" should pull file_write to the top
            List<ToolMatch> r = svc.search("file write");
            assertEquals("file_write", r.get(0).entry().name(),
                    "expected file_write top match for 'file write', got: "
                            + r.stream().map(m -> m.entry().name()).toList());
        }

        @Test
        void noMatchReturnsEmpty() {
            ToolSearchService svc = new ToolSearchService(catalog());
            assertTrue(svc.search("xylophone underwater bicycle").isEmpty());
        }

        @Test
        void maxResultsCapsOutput() {
            ToolSearchService svc = new ToolSearchService(catalog());
            List<ToolMatch> r = svc.search("file", 1);
            assertEquals(1, r.size());
        }

        @Test
        void resultIsImmutable() {
            ToolSearchService svc = new ToolSearchService(catalog());
            List<ToolMatch> r = svc.search("file");
            assertThrows(UnsupportedOperationException.class, () -> r.add(null));
        }

        @Test
        void caseInsensitive() {
            ToolSearchService svc = new ToolSearchService(catalog());
            List<ToolMatch> upper = svc.search("FILE");
            List<ToolMatch> lower = svc.search("file");
            assertEquals(upper.size(), lower.size());
            assertEquals(upper.get(0).entry().name(),
                    lower.get(0).entry().name());
        }

        @Test
        void scoresAreDescending() {
            ToolSearchService svc = new ToolSearchService(catalog());
            List<ToolMatch> r = svc.search("file io read");
            for (int i = 0; i < r.size() - 1; i++) {
                assertTrue(r.get(i).score() >= r.get(i + 1).score(),
                        "scores not descending at " + i);
            }
        }

        @Test
        void allTermsHitBonusBoostsTightMatches() {
            // "math compute" — both terms hit calculator (compute is a keyword).
            // statistics has "math" keyword but not "compute", so calculator
            // should score higher thanks to the all-terms-hit bonus.
            ToolSearchService svc = new ToolSearchService(catalog());
            List<ToolMatch> r = svc.search("math compute");
            assertEquals("calculator", r.get(0).entry().name());
        }

        @Test
        void scoreDirectFunction() {
            CatalogEntry e = new CatalogEntry("calculator", "do math",
                    List.of("arithmetic"), "");
            // "calculator" hits the name (3.0); "math" hits description (1.0)
            assertEquals(3.0, ToolSearchService.score(e, List.of("calculator")));
            assertEquals(1.0, ToolSearchService.score(e, List.of("math")));
            // Both hit + all-terms bonus
            assertEquals(3.0 + 1.0 + 1.0,
                    ToolSearchService.score(e, List.of("calculator", "math")));
        }

        @Test
        void scoreOfEmptyQueryIsZero() {
            CatalogEntry e = CatalogEntry.of("anything", "anything");
            assertEquals(0.0, ToolSearchService.score(e, List.of()));
            assertEquals(0.0, ToolSearchService.score(e, ""));
        }

        @Test
        void tokenizerStripsPunctuationAndCase() {
            assertEquals(List.of("hello", "world"),
                    ToolSearchService.tokenize("Hello, World!"));
            assertEquals(List.of("foo", "bar", "baz"),
                    ToolSearchService.tokenize("foo-bar_baz"));
            assertEquals(List.of(), ToolSearchService.tokenize("  ! ! ! "));
        }

        @Test
        void tokenizerDropsShortTokensToReduceNoise() {
            // "What's" → ["what", "s"] — the bare "s" would substring-match many
            // tool names/descriptions and pollute relevance. Drop it.
            assertEquals(List.of(),
                    ToolSearchService.tokenize("What's"),
                    "tokens below MIN_TOKEN_LENGTH are dropped (and 'what' is a stop-word)");
            // "AI" is 2 chars — also dropped
            assertEquals(List.of("framework"),
                    ToolSearchService.tokenize("AI framework"));
        }

        @Test
        void tokenizerDropsStopWords() {
            // "the", "and", "for" are all common English stop-words filtered out
            assertEquals(List.of("dates"),
                    ToolSearchService.tokenize("the dates and"));
            assertEquals(List.of("calculator"),
                    ToolSearchService.tokenize("for the calculator"));
        }

        @Test
        void noiseTokensDoNotPollutePoorlyMatchingEntries() {
            // The original failure: query "What's the elapsed time between..."
            // produced bare "s" that substring-matched web_search via name/keyword/
            // description, giving it a higher score than date_diff. After tokenizer
            // tightening, web_search should NOT outrank date_diff for this query.
            ToolCatalog cat = new ToolCatalog();
            cat.register(new CatalogEntry("date_diff",
                    "Compute the difference between two dates in days.",
                    List.of("clock", "datetime", "math"),
                    "args: from, to"));
            cat.register(new CatalogEntry("web_search",
                    "Search the web for a query and return result titles.",
                    List.of("internet", "google", "search"),
                    "args: query"));
            ToolSearchService svc = new ToolSearchService(cat);

            List<ToolMatch> r = svc.search(
                    "What's the elapsed time between 2025-01-01 and today?");
            assertFalse(r.isEmpty(),
                    "expected at least one match for the date-arithmetic query");
            assertEquals("date_diff", r.get(0).entry().name(),
                    "date_diff should be the top match, not web_search; got order: "
                            + r.stream().map(m -> m.entry().name()).toList());
        }
    }

    // =================================================================
    // ToolSearchTool — agent-callable bridge
    // =================================================================
    @Nested
    @DisplayName("ToolSearchTool")
    class ToolBridge {

        private ToolSearchService svcWith3Tools() {
            ToolCatalog cat = new ToolCatalog();
            cat.register(new CatalogEntry("calculator",
                    "Perform arithmetic.",
                    List.of("math", "arithmetic"),
                    "args: expression (string)"));
            cat.register(new CatalogEntry("statistics",
                    "Statistical analysis.",
                    List.of("math", "stats"),
                    "args: data (array<number>)"));
            cat.register(CatalogEntry.of("web_search",
                    "Search the web.",
                    List.of("internet")));
            return new ToolSearchService(cat);
        }

        @Test
        void handleReturnsFormattedMatches() {
            ToolSearchService svc = svcWith3Tools();
            ToolSearchTool.ToolSearchInput in = new ToolSearchTool.ToolSearchInput();
            in.query = "math";

            String out = ToolSearchTool.handle(svc, in);
            assertTrue(out.startsWith("Found "), out);
            assertTrue(out.contains("of 3 catalogued tools"));
            assertTrue(out.contains("calculator"));
            assertTrue(out.contains("statistics"));
            // schemaSummary surfaces in output
            assertTrue(out.contains("args: expression"));
            // keywords surface in output
            assertTrue(out.contains("keywords: math, arithmetic"));
        }

        @Test
        void handleReportsNoMatchesGracefully() {
            ToolSearchService svc = svcWith3Tools();
            ToolSearchTool.ToolSearchInput in = new ToolSearchTool.ToolSearchInput();
            in.query = "underwater xylophone";

            String out = ToolSearchTool.handle(svc, in);
            assertTrue(out.startsWith("No catalogued tools matched"));
            assertTrue(out.contains("Searched 3 tools"));
        }

        @Test
        void handleRejectsBlankQuery() {
            ToolSearchService svc = svcWith3Tools();
            ToolSearchTool.ToolSearchInput in = new ToolSearchTool.ToolSearchInput();
            in.query = "  ";

            String out = ToolSearchTool.handle(svc, in);
            assertTrue(out.startsWith("Error:"), out);
        }

        @Test
        void handleRejectsNullInput() {
            ToolSearchService svc = svcWith3Tools();
            String out = ToolSearchTool.handle(svc, null);
            assertTrue(out.startsWith("Error:"), out);
        }

        @Test
        void maxResultsHonoured() {
            ToolSearchService svc = svcWith3Tools();
            ToolSearchTool.ToolSearchInput in = new ToolSearchTool.ToolSearchInput();
            in.query = "math";
            in.maxResults = 1;

            String out = ToolSearchTool.handle(svc, in);
            assertTrue(out.contains("Found 1 of"),
                    "expected 'Found 1 of': " + out);
        }

        @Test
        void nullMaxResultsUsesDefault() {
            ToolSearchService svc = svcWith3Tools();
            ToolSearchTool.ToolSearchInput in = new ToolSearchTool.ToolSearchInput();
            in.query = "math";
            in.maxResults = null;

            String out = ToolSearchTool.handle(svc, in);
            assertFalse(out.startsWith("Error:"), out);
            assertTrue(out.contains("calculator"));
        }

        @Test
        void buildCallbackHasExpectedFunctionName() {
            ToolSearchService svc = svcWith3Tools();
            assertEquals("tool_search",
                    ToolSearchTool.callback(svc).getToolDefinition().name());
        }

        @Test
        void buildCallbackAcceptsCustomFunctionName() {
            ToolSearchService svc = svcWith3Tools();
            assertEquals("find_tool",
                    ToolSearchTool.callback(svc, "find_tool").getToolDefinition().name());
        }
    }
}
