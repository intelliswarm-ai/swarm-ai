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

@DisplayName("GlobFilesTool")
class GlobFilesToolTest {

    @TempDir Path tempDir;
    private GlobFilesTool tool;

    @BeforeEach
    void setUp() throws IOException, InterruptedException {
        tool = new GlobFilesTool(tempDir);
        // Tree:
        //   foo.py
        //   bar.py
        //   src/x.java
        //   src/y.java
        //   src/sub/z.java
        //   node_modules/index.js  (must be skipped)
        Files.writeString(tempDir.resolve("foo.py"), "");
        // Touch with later mtime so we can verify sort order
        Thread.sleep(20);
        Files.writeString(tempDir.resolve("bar.py"), "");
        Files.createDirectories(tempDir.resolve("src/sub"));
        Files.writeString(tempDir.resolve("src/x.java"), "");
        Files.writeString(tempDir.resolve("src/y.java"), "");
        Files.writeString(tempDir.resolve("src/sub/z.java"), "");
        Files.createDirectories(tempDir.resolve("node_modules"));
        Files.writeString(tempDir.resolve("node_modules/index.js"), "");
    }

    @Test
    @DisplayName("matches by glob")
    void matchesByGlob() {
        Object out = tool.execute(Map.of("pattern", "**/*.java"));
        String s = out.toString();
        assertTrue(s.contains("x.java"), s);
        assertTrue(s.contains("y.java"), s);
        assertTrue(s.contains("z.java"), s);
    }

    @Test
    @DisplayName("**/X also matches root-level X (fallback matcher)")
    void rootLevelFallbackMatcher() {
        Object out = tool.execute(Map.of("pattern", "**/foo.py"));
        assertTrue(out.toString().contains("foo.py"),
                "root-level foo.py should match **/foo.py; got: " + out);
    }

    @Test
    @DisplayName("skips well-known build/VCS directories")
    void skipsNodeModules() {
        Object out = tool.execute(Map.of("pattern", "**/*.js"));
        assertTrue(out.toString().startsWith("(no files match"),
                "node_modules must be skipped; got: " + out);
    }

    @Test
    @DisplayName("results sorted by most-recently-modified first")
    void sortedByMtime() {
        Object out = tool.execute(Map.of("pattern", "*.py"));
        String s = out.toString();
        int barIdx = s.indexOf("bar.py");
        int fooIdx = s.indexOf("foo.py");
        assertTrue(barIdx >= 0 && fooIdx >= 0, s);
        assertTrue(barIdx < fooIdx, "bar.py (newer) should appear before foo.py; got: " + s);
    }

    @Test
    @DisplayName("limit caps results")
    void limitCaps() {
        Object out = tool.execute(Map.of("pattern", "**/*.java", "limit", 1));
        String s = out.toString();
        assertTrue(s.contains("showing first 1"), s);
        // Only one filename printed in the body
        long lineCount = s.lines().filter(l -> l.endsWith(".java")).count();
        assertEquals(1, lineCount);
    }

    @Test
    @DisplayName("subdirectory `path` narrows the walk")
    void subdirectoryPath() {
        Object out = tool.execute(Map.of("pattern", "**/*.java", "path", "src/sub"));
        String s = out.toString();
        assertTrue(s.contains("z.java"), s);
        assertFalse(s.contains("x.java") || s.contains("y.java"),
                "files outside src/sub must not appear; got: " + s);
    }

    @Test
    @DisplayName("missing pattern is rejected")
    void missingPattern() {
        assertTrue(tool.execute(Map.of()).toString().contains("'pattern' is required"));
    }

    @Test
    @DisplayName("no matches returns a friendly message")
    void noMatches() {
        Object out = tool.execute(Map.of("pattern", "**/*.rs"));
        assertTrue(out.toString().startsWith("(no files match"), out.toString());
    }

    @Test
    @DisplayName("refuses to search outside project root")
    void refusesEscape() {
        Object out = tool.execute(Map.of("pattern", "**/*", "path", "../.."));
        assertTrue(out.toString().contains("outside project root"), out.toString());
    }
}
