package ai.intelliswarm.swarmai.tool.code.diff;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DiffTool — pure-Java unified diff (1.0.26)")
class DiffToolTest {

    @TempDir Path tmp;

    @Test
    @DisplayName("identical text yields the explicit 'identical' sentinel")
    void identicalSentinel() {
        DiffTool tool = new DiffTool();
        Object out = tool.execute(Map.of("text_a", "hello\n", "text_b", "hello\n"));
        assertEquals("(files are identical)", out);
    }

    @Test
    @DisplayName("single-line change produces a hunk with - and + markers")
    void singleLineChange() {
        DiffTool tool = new DiffTool();
        Object out = tool.execute(Map.of(
                "text_a", "alpha\nbeta\ngamma\n",
                "text_b", "alpha\nBETA\ngamma\n"));
        String s = out.toString();
        assertTrue(s.contains("--- a"));
        assertTrue(s.contains("+++ b"));
        assertTrue(s.contains("@@"));
        assertTrue(s.contains("-beta"), "got: " + s);
        assertTrue(s.contains("+BETA"), "got: " + s);
        // Context lines (no leading +/-) included.
        assertTrue(s.contains(" alpha"), "expected context line ' alpha', got: " + s);
        assertTrue(s.contains(" gamma"), "expected context line ' gamma', got: " + s);
    }

    @Test
    @DisplayName("pure insertion shows added lines with + prefix")
    void pureInsertion() {
        DiffTool tool = new DiffTool();
        Object out = tool.execute(Map.of(
                "text_a", "line1\nline2\n",
                "text_b", "line1\nINSERTED\nline2\n"));
        assertTrue(out.toString().contains("+INSERTED"), "got: " + out);
    }

    @Test
    @DisplayName("pure deletion shows removed lines with - prefix")
    void pureDeletion() {
        DiffTool tool = new DiffTool();
        Object out = tool.execute(Map.of(
                "text_a", "line1\ngone\nline2\n",
                "text_b", "line1\nline2\n"));
        assertTrue(out.toString().contains("-gone"), "got: " + out);
    }

    @Test
    @DisplayName("file-based comparison reads from project root")
    void fileBased() throws IOException {
        Files.writeString(tmp.resolve("a.txt"), "x\ny\nz\n");
        Files.writeString(tmp.resolve("b.txt"), "x\nY\nz\n");
        DiffTool tool = new DiffTool(tmp);
        Object out = tool.execute(Map.of("file_a", "a.txt", "file_b", "b.txt"));
        assertTrue(out.toString().contains("-y"));
        assertTrue(out.toString().contains("+Y"));
        assertTrue(out.toString().contains("a/a.txt"));
        assertTrue(out.toString().contains("b/b.txt"));
    }

    @Test
    @DisplayName("refuses paths that escape the project root")
    void refusesEscape() throws IOException {
        Files.writeString(tmp.resolve("a.txt"), "hi");
        DiffTool tool = new DiffTool(tmp);
        Object out = tool.execute(Map.of("file_a", "a.txt", "file_b", "../../../etc/passwd"));
        assertTrue(out.toString().contains("refusing to read outside project root"),
                "got: " + out);
    }

    @Test
    @DisplayName("rejects calls that mix files and texts or supply neither pair")
    void rejectsAmbiguousArgs() {
        DiffTool tool = new DiffTool();
        assertTrue(tool.execute(Map.of()).toString().contains("provide exactly one pair"));
        assertTrue(tool.execute(Map.of("file_a", "x", "text_a", "y")).toString()
                .contains("provide exactly one pair"));
        assertTrue(tool.execute(Map.of("file_a", "x")).toString()
                .contains("provide exactly one pair"));
    }

    @Test
    @DisplayName("missing source file is treated as empty (so diff vs new file works)")
    void missingFileTreatedAsEmpty() throws IOException {
        Files.writeString(tmp.resolve("new.txt"), "fresh content\n");
        DiffTool tool = new DiffTool(tmp);
        Object out = tool.execute(Map.of(
                "file_a", "does-not-exist.txt",
                "file_b", "new.txt"));
        assertTrue(out.toString().contains("+fresh content"),
                "expected the new file to appear as additions, got: " + out);
    }

    @Test
    @DisplayName("context=0 emits only the changed lines, no surrounding keeps")
    void contextZero() {
        DiffTool tool = new DiffTool();
        Object out = tool.execute(Map.of(
                "text_a", "a\nb\nc\nd\ne\n",
                "text_b", "a\nb\nCHANGED\nd\ne\n",
                "context", 0));
        // Inspect body lines individually so we don't trip over " a" appearing
        // inside the "--- a" / "+++ b" headers.
        long contextLines = out.toString().lines()
                .filter(l -> !l.startsWith("---") && !l.startsWith("+++") && !l.startsWith("@@"))
                .filter(l -> l.startsWith(" "))
                .count();
        assertEquals(0, contextLines, "context=0 should not include any ' '-prefixed lines, got: " + out);
        assertTrue(out.toString().contains("-c"));
        assertTrue(out.toString().contains("+CHANGED"));
    }

    @Test
    @DisplayName("schema declares no required fields (caller picks file or text pair)")
    void schemaShape() {
        Map<String, Object> schema = new DiffTool().getParameterSchema();
        assertEquals("object", schema.get("type"));
        assertNull(schema.get("required"));
        @SuppressWarnings("unchecked")
        Map<String, ?> props = (Map<String, ?>) schema.get("properties");
        assertTrue(props.containsKey("file_a"));
        assertTrue(props.containsKey("file_b"));
        assertTrue(props.containsKey("text_a"));
        assertTrue(props.containsKey("text_b"));
        assertTrue(props.containsKey("context"));
    }
}
