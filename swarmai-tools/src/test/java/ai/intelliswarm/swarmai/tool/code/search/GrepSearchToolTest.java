package ai.intelliswarm.swarmai.tool.code.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("GrepSearchTool")
class GrepSearchToolTest {

    @TempDir Path tempDir;
    private GrepSearchTool tool;

    @BeforeEach
    void setUp() throws IOException {
        tool = new GrepSearchTool(tempDir);
        // tree:
        //   foo.py            -> contains "def hello"
        //   sub/bar.py        -> contains "def hello"
        //   sub/baz.txt       -> contains "hello"
        //   target/skipme.py  -> contains "def hello" (skipped subtree)
        //   big.bin           -> contains "def hello" but > 5 MB (would be skipped — we don't create it here)
        Files.writeString(tempDir.resolve("foo.py"), "def hello():\n    return 1\n");
        Files.createDirectories(tempDir.resolve("sub"));
        Files.writeString(tempDir.resolve("sub/bar.py"), "def hello():\n    return 2\n");
        Files.writeString(tempDir.resolve("sub/baz.txt"), "hello world\nanother line\n");
        Files.createDirectories(tempDir.resolve("target"));
        Files.writeString(tempDir.resolve("target/skipme.py"), "def hello():\n    skipped()\n");
    }

    @Test
    @DisplayName("finds matches across files with file:line prefix")
    void findsMatchesWithPrefix() {
        Object out = tool.execute(Map.of("pattern", "def hello"));
        String s = out.toString();
        assertTrue(s.contains("foo.py:1:def hello"), s);
        assertTrue(s.contains("sub/bar.py:1:def hello") || s.contains("sub\\bar.py:1:def hello"), s);
    }

    @Test
    @DisplayName("skips well-known build-output directories")
    void skipsSkipDirs() {
        Object out = tool.execute(Map.of("pattern", "def hello"));
        assertFalse(out.toString().contains("target/skipme.py"),
                "target/ must be skipped; got: " + out);
    }

    @Test
    @DisplayName("path_glob restricts the search to matching files")
    void pathGlobFilter() {
        Object out = tool.execute(Map.of("pattern", "hello", "path_glob", "**/*.txt"));
        String s = out.toString();
        assertTrue(s.contains("sub/baz.txt") || s.contains("sub\\baz.txt"), s);
        assertFalse(s.contains("foo.py") || s.contains("bar.py"));
    }

    @Test
    @DisplayName("**/X glob also matches X at project root (fallback matcher)")
    void rootLevelFallbackMatcher() {
        Object out = tool.execute(Map.of("pattern", "def hello", "path_glob", "**/foo.py"));
        assertTrue(out.toString().contains("foo.py:1:def hello"),
                "root-level foo.py should match **/foo.py via fallback; got: " + out);
    }

    @Test
    @DisplayName("case_insensitive controls matching")
    void caseInsensitive() {
        Object sensitive = tool.execute(Map.of("pattern", "HELLO"));
        assertTrue(sensitive.toString().startsWith("(no matches"), sensitive.toString());
        Object insensitive = tool.execute(Map.of("pattern", "HELLO", "case_insensitive", true));
        assertTrue(insensitive.toString().contains("foo.py"), insensitive.toString());
    }

    @Test
    @DisplayName("context_lines includes surrounding lines")
    void contextLines() {
        Object out = tool.execute(Map.of(
                "pattern", "def hello",
                "context_lines", 1,
                "path_glob", "**/foo.py"));
        String s = out.toString();
        assertTrue(s.contains("foo.py:1:def hello"), s);
        assertTrue(s.contains("foo.py-2-    return 1"), s);
    }

    @Test
    @DisplayName("invalid regex returns a clean error")
    void invalidRegex() {
        Object out = tool.execute(Map.of("pattern", "[unclosed"));
        assertTrue(out.toString().startsWith("Error: invalid regex"), out.toString());
    }

    @Test
    @DisplayName("missing pattern is rejected")
    void missingPattern() {
        assertTrue(tool.execute(Map.of()).toString().contains("'pattern' is required"));
    }

    @Test
    @DisplayName("no matches returns a friendly message")
    void noMatches() {
        Object out = tool.execute(Map.of("pattern", "nonexistent_zzz"));
        assertTrue(out.toString().startsWith("(no matches"), out.toString());
    }

    @Test
    @DisplayName("max_matches caps the result")
    void maxMatchesCap() {
        Object out = tool.execute(Map.of("pattern", "hello", "max_matches", 1));
        String s = out.toString();
        assertTrue(s.contains("Found 1 match"), s);
        assertTrue(s.contains("(truncated)"), s);
    }
}
