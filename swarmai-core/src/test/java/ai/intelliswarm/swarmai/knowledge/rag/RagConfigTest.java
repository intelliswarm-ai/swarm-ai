package ai.intelliswarm.swarmai.knowledge.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RagConfig Tests")
class RagConfigTest {

    @Nested
    @DisplayName("defaults()")
    class DefaultsTests {

        @Test
        @DisplayName("matches the documented eval-winning values")
        void defaults_matchEvalWinningValues() {
            RagConfig c = RagConfig.defaults();
            // These exact values are referenced in
            // intellidoc/docs/internal/EVAL_2026_04_26_FULL_ITERATIONS.md
            // — changing them is a behaviour change for every existing user.
            assertEquals(800,  c.chunkSize());
            assertEquals(100,  c.chunkOverlap());
            assertEquals(5,    c.topK());
            assertEquals(2400, c.maxPassageChars());
            assertTrue(c.hybridRetrieval());
            assertTrue(c.contextualPrefix());
            assertFalse(c.mmrRerank());
            assertEquals(0.2,  c.temperature());
            assertEquals(350,  c.numPredict());
        }
    }

    @Nested
    @DisplayName("with* methods")
    class WithMethodsTests {

        @Test
        @DisplayName("withTopK changes only topK")
        void withTopK_changesOnlyTopK() {
            RagConfig orig = RagConfig.defaults();
            RagConfig changed = orig.withTopK(10);
            assertEquals(10, changed.topK());
            assertEquals(orig.chunkSize(),       changed.chunkSize());
            assertEquals(orig.chunkOverlap(),    changed.chunkOverlap());
            assertEquals(orig.maxPassageChars(), changed.maxPassageChars());
            assertEquals(orig.hybridRetrieval(), changed.hybridRetrieval());
            // and the original is untouched
            assertEquals(5, orig.topK());
        }

        @Test
        @DisplayName("withHybridRetrieval flips the flag")
        void withHybridRetrieval_flipsFlag() {
            RagConfig c = RagConfig.defaults().withHybridRetrieval(false);
            assertFalse(c.hybridRetrieval());
            assertTrue(c.contextualPrefix(), "other flags untouched");
        }

        @Test
        @DisplayName("chained with* calls compose")
        void withMethods_chain() {
            RagConfig c = RagConfig.defaults()
                    .withTopK(8)
                    .withChunkSize(1200)
                    .withMmrRerank(true)
                    .withTemperature(0.5)
                    .withNumPredict(800);
            assertEquals(8,    c.topK());
            assertEquals(1200, c.chunkSize());
            assertTrue(c.mmrRerank());
            assertEquals(0.5,  c.temperature());
            assertEquals(800,  c.numPredict());
            // unchanged
            assertEquals(100, c.chunkOverlap());
            assertTrue(c.hybridRetrieval());
        }
    }
}
