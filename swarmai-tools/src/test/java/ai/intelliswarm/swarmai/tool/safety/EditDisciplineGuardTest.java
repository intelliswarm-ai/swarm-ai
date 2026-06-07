package ai.intelliswarm.swarmai.tool.safety;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for EditDisciplineGuard (1.0.19+). */
class EditDisciplineGuardTest {

    @Nested
    @DisplayName("read-before-edit invariant")
    class ReadBeforeEdit {

        @Test
        void editWithoutReadIsRejected(@TempDir Path tmp) {
            EditDisciplineGuard guard = new EditDisciplineGuard();
            Path file = tmp.resolve("config.txt");
            assertThrows(EditDisciplineGuard.ReadBeforeEditException.class,
                    () -> guard.applyEdit(file, "hello world", "hello", "goodbye", false));
        }

        @Test
        void editAfterReadIsAllowed(@TempDir Path tmp) {
            EditDisciplineGuard guard = new EditDisciplineGuard();
            Path file = tmp.resolve("config.txt");

            guard.recordRead(file);
            String result = guard.applyEdit(file, "hello world", "hello", "goodbye", false);
            assertEquals("goodbye world", result);
        }

        @Test
        void hasReadRecordReportsCorrectly(@TempDir Path tmp) {
            EditDisciplineGuard guard = new EditDisciplineGuard();
            Path file = tmp.resolve("a.txt");

            assertFalse(guard.hasReadRecord(file));
            guard.recordRead(file);
            assertTrue(guard.hasReadRecord(file));
        }

        @Test
        void readsAreTrackedPerNormalizedPath(@TempDir Path tmp) throws IOException {
            EditDisciplineGuard guard = new EditDisciplineGuard();
            Path file = tmp.resolve("nested").resolve("..").resolve("a.txt");
            Path normalized = tmp.resolve("a.txt");

            // Create the file so toRealPath would work, but we use toAbsolutePath
            // + normalize which doesn't require existence.
            Files.createFile(normalized);

            guard.recordRead(file);
            assertTrue(guard.hasReadRecord(normalized),
                    "different path strings pointing to the same file should match");
        }

        @Test
        void recordReadAcceptsStringPath(@TempDir Path tmp) {
            EditDisciplineGuard guard = new EditDisciplineGuard();
            Path file = tmp.resolve("a.txt");

            guard.recordRead(file.toString());
            assertTrue(guard.hasReadRecord(file));
        }

        @Test
        void clearRemovesAllRecords(@TempDir Path tmp) {
            EditDisciplineGuard guard = new EditDisciplineGuard();
            guard.recordRead(tmp.resolve("a.txt"));
            guard.recordRead(tmp.resolve("b.txt"));
            assertEquals(2, guard.readPaths().size());

            guard.clear();
            assertEquals(0, guard.readPaths().size());
        }

        @Test
        void forgetRemovesSingleRecord(@TempDir Path tmp) {
            EditDisciplineGuard guard = new EditDisciplineGuard();
            Path a = tmp.resolve("a.txt");
            Path b = tmp.resolve("b.txt");
            guard.recordRead(a);
            guard.recordRead(b);

            assertTrue(guard.forgetRead(a).isPresent());
            assertFalse(guard.hasReadRecord(a));
            assertTrue(guard.hasReadRecord(b));
            assertTrue(guard.forgetRead(a).isEmpty(), "second forget returns empty");
        }
    }

    @Nested
    @DisplayName("unique-match invariant")
    class UniqueMatch {

        @Test
        void zeroMatchesThrowsNoMatch(@TempDir Path tmp) {
            EditDisciplineGuard guard = new EditDisciplineGuard();
            Path file = tmp.resolve("a.txt");
            guard.recordRead(file);

            EditDisciplineGuard.NoMatchException ex = assertThrows(
                    EditDisciplineGuard.NoMatchException.class,
                    () -> guard.applyEdit(file, "the quick brown fox", "tortoise", "wolf", false));
            assertEquals("tortoise", ex.oldString());
            assertEquals(file, ex.path());
        }

        @Test
        void multipleMatchesWithoutReplaceAllThrowsAmbiguous(@TempDir Path tmp) {
            EditDisciplineGuard guard = new EditDisciplineGuard();
            Path file = tmp.resolve("a.txt");
            guard.recordRead(file);

            EditDisciplineGuard.AmbiguousMatchException ex = assertThrows(
                    EditDisciplineGuard.AmbiguousMatchException.class,
                    () -> guard.applyEdit(file, "foo bar foo bar foo", "foo", "X", false));
            assertEquals(3, ex.matchCount());
        }

        @Test
        void multipleMatchesWithReplaceAllReplacesAll(@TempDir Path tmp) {
            EditDisciplineGuard guard = new EditDisciplineGuard();
            Path file = tmp.resolve("a.txt");
            guard.recordRead(file);

            String result = guard.applyEdit(file,
                    "foo bar foo bar foo", "foo", "X", true);
            assertEquals("X bar X bar X", result);
        }

        @Test
        void singleMatchReplaces(@TempDir Path tmp) {
            EditDisciplineGuard guard = new EditDisciplineGuard();
            Path file = tmp.resolve("a.txt");
            guard.recordRead(file);

            String result = guard.applyEdit(file,
                    "alpha beta gamma", "beta", "DELTA", false);
            assertEquals("alpha DELTA gamma", result);
        }

        @Test
        void exactWhitespacePreserved(@TempDir Path tmp) {
            // Whitespace-sensitive edit — `foo  bar` (two spaces) should not match `foo bar` (one)
            EditDisciplineGuard guard = new EditDisciplineGuard();
            Path file = tmp.resolve("a.txt");
            guard.recordRead(file);

            assertThrows(EditDisciplineGuard.NoMatchException.class,
                    () -> guard.applyEdit(file, "foo  bar baz", "foo bar", "X", false));
        }

        @Test
        void multilineOldStringSupported(@TempDir Path tmp) {
            EditDisciplineGuard guard = new EditDisciplineGuard();
            Path file = tmp.resolve("a.txt");
            guard.recordRead(file);

            String content = "line1\nline2\nline3\n";
            String result = guard.applyEdit(file, content, "line1\nline2", "REPLACED", false);
            assertEquals("REPLACED\nline3\n", result);
        }

        @Test
        void emptyOldStringRejected(@TempDir Path tmp) {
            EditDisciplineGuard guard = new EditDisciplineGuard();
            Path file = tmp.resolve("a.txt");
            guard.recordRead(file);

            assertThrows(IllegalArgumentException.class,
                    () -> guard.applyEdit(file, "content", "", "X", false));
        }

        @Test
        void emptyNewStringActsAsDeletion(@TempDir Path tmp) {
            EditDisciplineGuard guard = new EditDisciplineGuard();
            Path file = tmp.resolve("a.txt");
            guard.recordRead(file);

            String result = guard.applyEdit(file, "alpha beta gamma", "beta ", "", false);
            assertEquals("alpha gamma", result);
        }

        @Test
        void countOccurrencesReturnsCorrect() {
            assertEquals(0, EditDisciplineGuard.countOccurrences("abc", "x"));
            assertEquals(1, EditDisciplineGuard.countOccurrences("abc", "b"));
            assertEquals(3, EditDisciplineGuard.countOccurrences("ababab", "ab"));
            assertEquals(0, EditDisciplineGuard.countOccurrences("anything", ""));
            assertEquals(0, EditDisciplineGuard.countOccurrences(null, "x"));
            assertEquals(0, EditDisciplineGuard.countOccurrences("anything", null));
        }

        @Test
        void countNonOverlapping() {
            // "aaaa" with needle "aa" should be 2, not 3 (non-overlapping)
            assertEquals(2, EditDisciplineGuard.countOccurrences("aaaa", "aa"));
        }
    }

    @Nested
    @DisplayName("stale-read invariant")
    class StaleRead {

        @Test
        void unchangedContentAllowsEdit(@TempDir Path tmp) {
            EditDisciplineGuard guard = new EditDisciplineGuard();
            Path file = tmp.resolve("a.txt");
            String content = "hello world";

            guard.recordRead(file, content);
            String result = guard.applyEdit(file, content, "hello", "goodbye", false);
            assertEquals("goodbye world", result);
        }

        @Test
        void changedContentRejectsEdit(@TempDir Path tmp) {
            EditDisciplineGuard guard = new EditDisciplineGuard();
            Path file = tmp.resolve("a.txt");

            guard.recordRead(file, "version A");
            // Someone else changed the file while we were thinking
            assertThrows(EditDisciplineGuard.StaleReadException.class,
                    () -> guard.applyEdit(file, "version B", "version", "release", false));
        }

        @Test
        void recordReadWithoutContentSkipsStaleness(@TempDir Path tmp) {
            EditDisciplineGuard guard = new EditDisciplineGuard();
            Path file = tmp.resolve("a.txt");

            guard.recordRead(file);  // no content
            // Even though we never saw the content, the edit is allowed because
            // staleness tracking is opt-in.
            assertDoesNotThrow(() ->
                    guard.applyEdit(file, "any content with foo", "foo", "X", false));
        }

        @Test
        void successfulEditUpdatesRecordedContent(@TempDir Path tmp) {
            EditDisciplineGuard guard = new EditDisciplineGuard();
            Path file = tmp.resolve("a.txt");

            guard.recordRead(file, "hello world");
            String first = guard.applyEdit(file, "hello world", "hello", "goodbye", false);
            assertEquals("goodbye world", first);

            // Now apply a follow-up edit using the new content. The recorded
            // hash should have updated, so this still passes the staleness check.
            String second = guard.applyEdit(file, first, "world", "earth", false);
            assertEquals("goodbye earth", second);
        }
    }

    @Nested
    @DisplayName("input contracts")
    class Inputs {

        @Test
        void nullPathRejected(@TempDir Path tmp) {
            EditDisciplineGuard guard = new EditDisciplineGuard();
            assertThrows(NullPointerException.class,
                    () -> guard.recordRead((Path) null));
            assertThrows(NullPointerException.class,
                    () -> guard.recordRead((String) null));
            assertThrows(NullPointerException.class,
                    () -> guard.applyEdit(null, "content", "x", "y", false));
        }

        @Test
        void nullContentOldOrNewRejected(@TempDir Path tmp) {
            EditDisciplineGuard guard = new EditDisciplineGuard();
            Path file = tmp.resolve("a.txt");
            guard.recordRead(file);

            assertThrows(NullPointerException.class,
                    () -> guard.applyEdit(file, null, "x", "y", false));
            assertThrows(NullPointerException.class,
                    () -> guard.applyEdit(file, "content", null, "y", false));
            assertThrows(NullPointerException.class,
                    () -> guard.applyEdit(file, "content", "x", null, false));
        }
    }

    @Nested
    @DisplayName("exception messages give the LLM enough to recover")
    class ExceptionMessages {

        @Test
        void readBeforeEditMessageMentionsReadFirst(@TempDir Path tmp) {
            EditDisciplineGuard guard = new EditDisciplineGuard();
            Path file = tmp.resolve("a.txt");
            try {
                guard.applyEdit(file, "x", "y", "z", false);
                fail();
            } catch (EditDisciplineGuard.ReadBeforeEditException e) {
                assertTrue(e.getMessage().contains("Read the file first"),
                        "should tell the LLM to read first: " + e.getMessage());
            }
        }

        @Test
        void noMatchMessageMentionsReReading(@TempDir Path tmp) {
            EditDisciplineGuard guard = new EditDisciplineGuard();
            Path file = tmp.resolve("a.txt");
            guard.recordRead(file);
            try {
                guard.applyEdit(file, "actual content", "missing", "X", false);
                fail();
            } catch (EditDisciplineGuard.NoMatchException e) {
                assertTrue(e.getMessage().contains("Re-read"),
                        "should suggest re-reading: " + e.getMessage());
            }
        }

        @Test
        void ambiguousMatchSuggestsLongerStringOrReplaceAll(@TempDir Path tmp) {
            EditDisciplineGuard guard = new EditDisciplineGuard();
            Path file = tmp.resolve("a.txt");
            guard.recordRead(file);
            try {
                guard.applyEdit(file, "foo foo foo", "foo", "X", false);
                fail();
            } catch (EditDisciplineGuard.AmbiguousMatchException e) {
                String msg = e.getMessage();
                assertTrue(msg.contains("longer oldString"));
                assertTrue(msg.contains("replaceAll=true"));
            }
        }

        @Test
        void staleReadSuggestsRereading(@TempDir Path tmp) {
            EditDisciplineGuard guard = new EditDisciplineGuard();
            Path file = tmp.resolve("a.txt");
            guard.recordRead(file, "v1");
            try {
                guard.applyEdit(file, "v2", "v", "X", false);
                fail();
            } catch (EditDisciplineGuard.StaleReadException e) {
                assertTrue(e.getMessage().contains("Re-read"),
                        "should suggest re-reading: " + e.getMessage());
            }
        }
    }
}
