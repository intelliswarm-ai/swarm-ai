package ai.intelliswarm.swarmai.tool.code.edit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link FileEditTool}. */
@DisplayName("FileEditTool")
class FileEditToolTest {

    @TempDir Path tempDir;
    private FileEditTool tool;

    @BeforeEach
    void setUp() {
        tool = new FileEditTool(tempDir);
    }

    @Test
    @DisplayName("edits a unique occurrence")
    void editsUniqueOccurrence() throws IOException {
        Path file = tempDir.resolve("hello.py");
        Files.writeString(file, "def greet(name):\n    print(\"Hello, \" + name)\n");
        String result = (String) tool.execute(Map.of(
                "path", "hello.py",
                "old_string", "Hello",
                "new_string", "Howdy"));
        assertTrue(result.startsWith("Edited"), result);
        assertEquals("def greet(name):\n    print(\"Howdy, \" + name)\n",
                Files.readString(file));
    }

    @Test
    @DisplayName("refuses when old_string is not unique without replace_all")
    void refusesAmbiguousMatch() throws IOException {
        Path file = tempDir.resolve("x.txt");
        Files.writeString(file, "foo\nfoo\nfoo\n");
        Object result = tool.execute(Map.of(
                "path", "x.txt",
                "old_string", "foo",
                "new_string", "bar"));
        assertTrue(result.toString().contains("occurs 3 times"), result.toString());
        assertEquals("foo\nfoo\nfoo\n", Files.readString(file),
                "file should be untouched when match is ambiguous");
    }

    @Test
    @DisplayName("replace_all replaces every occurrence")
    void replaceAllWorks() throws IOException {
        Path file = tempDir.resolve("x.txt");
        Files.writeString(file, "foo\nfoo\nfoo\n");
        Object result = tool.execute(Map.of(
                "path", "x.txt",
                "old_string", "foo",
                "new_string", "bar",
                "replace_all", true));
        assertTrue(result.toString().startsWith("Edited"), result.toString());
        assertEquals("bar\nbar\nbar\n", Files.readString(file));
    }

    @Test
    @DisplayName("returns error when old_string not found")
    void notFound() throws IOException {
        Files.writeString(tempDir.resolve("x.txt"), "hello world");
        Object result = tool.execute(Map.of(
                "path", "x.txt",
                "old_string", "not-there",
                "new_string", "anything"));
        assertTrue(result.toString().contains("not found"), result.toString());
    }

    @Test
    @DisplayName("rejects identical old/new strings")
    void rejectsIdenticalStrings() throws IOException {
        Files.writeString(tempDir.resolve("x.txt"), "foo");
        Object result = tool.execute(Map.of(
                "path", "x.txt",
                "old_string", "foo",
                "new_string", "foo"));
        assertTrue(result.toString().contains("identical"), result.toString());
    }

    @Test
    @DisplayName("refuses edits outside the project root")
    void refusesEscape() throws IOException {
        Path outside = tempDir.getParent().resolve("../leaked.txt");
        Files.writeString(tempDir.resolve("a.txt"), "x");
        Object result = tool.execute(Map.of(
                "path", "../../outside.txt",
                "old_string", "x",
                "new_string", "y"));
        assertTrue(result.toString().contains("outside the project root"), result.toString());
    }

    @Test
    @DisplayName("refuses sensitive filenames")
    void refusesSensitive() throws IOException {
        Files.writeString(tempDir.resolve(".env"), "API_KEY=secret");
        Object result = tool.execute(Map.of(
                "path", ".env",
                "old_string", "secret",
                "new_string", "leaked"));
        assertTrue(result.toString().contains("sensitive"), result.toString());
        assertEquals("API_KEY=secret", Files.readString(tempDir.resolve(".env")));
    }

    @Test
    @DisplayName("requires path / old_string / new_string")
    void requiresAllFields() {
        assertTrue(tool.execute(Map.of()).toString().contains("'path' is required"));
        assertTrue(tool.execute(Map.of("path", "x")).toString().contains("'old_string' is required"));
        assertTrue(tool.execute(Map.of("path", "x", "old_string", "a")).toString().contains("'new_string' is required"));
    }

    @Test
    @DisplayName("counts lines correctly for diff reporting")
    void diffReporting() throws IOException {
        Path file = tempDir.resolve("x.py");
        Files.writeString(file, "a\nb\nc\n");
        Object result = tool.execute(Map.of(
                "path", "x.py",
                "old_string", "b\n",
                "new_string", "b1\nb2\n"));
        assertTrue(result.toString().contains("+1 lines"), result.toString());
    }

    @Test
    @DisplayName("no-arg constructor allows absolute paths (no sandbox)")
    void noArgCtorUnsandboxed() throws IOException {
        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "before");
        FileEditTool unsandboxed = new FileEditTool();
        Object result = unsandboxed.execute(Map.of(
                "path", file.toString(),
                "old_string", "before",
                "new_string", "after"));
        assertTrue(result.toString().startsWith("Edited"), result.toString());
        assertEquals("after", Files.readString(file));
    }
}
