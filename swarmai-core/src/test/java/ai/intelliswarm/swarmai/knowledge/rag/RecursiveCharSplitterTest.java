package ai.intelliswarm.swarmai.knowledge.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RecursiveCharSplitter Tests")
class RecursiveCharSplitterTest {

    @Nested
    @DisplayName("trivial cases")
    class TrivialTests {

        @Test
        @DisplayName("empty input returns empty list")
        void empty_returnsEmptyList() {
            assertTrue(new RecursiveCharSplitter(100, 10).split("").isEmpty());
        }

        @Test
        @DisplayName("null input returns empty list")
        void nullInput_returnsEmptyList() {
            assertTrue(new RecursiveCharSplitter(100, 10).split(null).isEmpty());
        }

        @Test
        @DisplayName("text shorter than chunk size returns single chunk")
        void shortText_singleChunk() {
            List<String> chunks = new RecursiveCharSplitter(100, 10).split("Hello world.");
            assertEquals(1, chunks.size());
            assertEquals("Hello world.", chunks.get(0));
        }
    }

    @Nested
    @DisplayName("separator priority")
    class SeparatorPriorityTests {

        @Test
        @DisplayName("prefers paragraph breaks over line breaks")
        void prefers_paragraphs_over_lines() {
            String text = "Para A line 1.\nPara A line 2.\n\nPara B line 1.\nPara B line 2.";
            // chunkSize 35 forces a split somewhere; with paragraph being the
            // first separator tried, the split should land between paragraphs.
            List<String> chunks = new RecursiveCharSplitter(35, 0).split(text);
            assertTrue(chunks.size() >= 2);
            // First chunk should contain Para A but not Para B
            assertTrue(chunks.get(0).contains("Para A"));
            assertFalse(chunks.get(0).contains("Para B"));
        }

        @Test
        @DisplayName("falls through to sentence boundary when no paragraphs fit")
        void falls_through_to_sentences() {
            String text = "Sentence one. Sentence two. Sentence three.";
            List<String> chunks = new RecursiveCharSplitter(20, 0).split(text);
            assertTrue(chunks.size() >= 2);
            // Each chunk should be at most ~20 chars (allowing slight slack from
            // separator preservation)
            for (String c : chunks) assertTrue(c.length() <= 25, "chunk too big: " + c);
        }

        @Test
        @DisplayName("hard-cuts when no separator fits")
        void hard_cuts_when_no_separator_fits() {
            String text = "abcdefghijklmnopqrstuvwxyz0123456789";  // 36 chars, no separators
            List<String> chunks = new RecursiveCharSplitter(10, 0).split(text);
            assertEquals(4, chunks.size()); // 36 / 10 = 4 chunks (last is partial)
            assertEquals(10, chunks.get(0).length());
        }
    }

    @Nested
    @DisplayName("overlap")
    class OverlapTests {

        @Test
        @DisplayName("zero overlap produces non-overlapping chunks")
        void zero_overlap_no_repeat() {
            String text = "AAA. BBB. CCC. DDD. EEE.";
            List<String> chunks = new RecursiveCharSplitter(10, 0).split(text);
            assertTrue(chunks.size() >= 2);
            // Each chunk after the first should NOT start with content from the previous
            // Verify by joining chunks doesn't blow up the original length much
            int total = chunks.stream().mapToInt(String::length).sum();
            assertTrue(total <= text.length() + 4, "too much overlap: total=" + total);
        }

        @Test
        @DisplayName("non-zero overlap repeats tail of previous chunk")
        void nonzero_overlap_repeats_tail() {
            String text = "AAAA. BBBB. CCCC. DDDD. EEEE.";
            List<String> chunks = new RecursiveCharSplitter(10, 5).split(text);
            assertTrue(chunks.size() >= 2);
            // chunk[1] should contain the last 5 chars of chunk[0]
            String prevTail = chunks.get(0).substring(Math.max(0, chunks.get(0).length() - 5));
            assertTrue(chunks.get(1).startsWith(prevTail),
                    "expected chunk[1]='" + chunks.get(1) + "' to start with tail of chunk[0]='" + chunks.get(0) + "'");
        }
    }

    @Nested
    @DisplayName("validation")
    class ValidationTests {

        @Test
        @DisplayName("rejects zero or negative chunk size")
        void rejects_invalid_chunk_size() {
            assertThrows(IllegalArgumentException.class, () -> new RecursiveCharSplitter(0, 0));
            assertThrows(IllegalArgumentException.class, () -> new RecursiveCharSplitter(-1, 0));
        }

        @Test
        @DisplayName("rejects overlap >= chunk size")
        void rejects_overlap_too_large() {
            assertThrows(IllegalArgumentException.class, () -> new RecursiveCharSplitter(100, 100));
            assertThrows(IllegalArgumentException.class, () -> new RecursiveCharSplitter(100, 200));
            assertThrows(IllegalArgumentException.class, () -> new RecursiveCharSplitter(100, -1));
        }
    }

    @Nested
    @DisplayName("realistic input")
    class RealisticTests {

        @Test
        @DisplayName("PDF-like text splits cleanly with default 800/100")
        void pdf_like_text_chunks() {
            // Roughly 2400 chars of repeated paragraphs
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 30; i++) {
                sb.append("Section ").append(i).append(": Lorem ipsum dolor sit amet, ")
                  .append("consectetur adipiscing elit. Sed do eiusmod tempor incididunt ut ")
                  .append("labore et dolore magna aliqua.\n\n");
            }
            List<String> chunks = new RecursiveCharSplitter(800, 100).split(sb.toString());
            assertTrue(chunks.size() >= 3, "expected ≥3 chunks for 2400-char input, got " + chunks.size());
            // Each chunk roughly chunkSize, allowing slack for overlap and separator
            for (String c : chunks) {
                assertTrue(c.length() <= 800 + 200,
                        "chunk too big: " + c.length() + " chars");
            }
        }
    }
}
