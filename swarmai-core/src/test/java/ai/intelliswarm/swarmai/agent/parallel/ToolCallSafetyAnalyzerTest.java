package ai.intelliswarm.swarmai.agent.parallel;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallSafetyAnalyzerTest {

    private final ToolCallSafetyAnalyzer analyzer = new ToolCallSafetyAnalyzer();

    @Test
    void emptyInputProducesEmptyOutput() {
        assertThat(analyzer.analyse(List.of())).isEmpty();
    }

    @Test
    void allReadsCollapseIntoOneGroup() {
        var calls = List.of(
                new PendingToolCall("1", "Read", Map.of("path", "/a/b.txt")),
                new PendingToolCall("2", "Read", Map.of("path", "/c/d.txt")),
                new PendingToolCall("3", "Grep", Map.of("pattern", "foo")));
        var groups = analyzer.analyse(calls);
        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).size()).isEqualTo(3);
    }

    @Test
    void clarifyIsAlwaysSerialized() {
        var calls = List.of(
                new PendingToolCall("1", "Read", Map.of("path", "/a.txt")),
                new PendingToolCall("2", "clarify", Map.of("question", "which option?")),
                new PendingToolCall("3", "Read", Map.of("path", "/b.txt")));
        var groups = analyzer.analyse(calls);
        // Read1 alone, clarify alone, Read2 alone — three groups
        assertThat(groups).hasSize(3);
        assertThat(groups.get(1).calls().get(0).toolName()).isEqualTo("clarify");
    }

    @Test
    void writesToSamePathSerialized() {
        var calls = List.of(
                new PendingToolCall("1", "Write", Map.of("path", "/a/b.txt", "content", "x")),
                new PendingToolCall("2", "Write", Map.of("path", "/a/b.txt", "content", "y")));
        var groups = analyzer.analyse(calls);
        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).size()).isEqualTo(1);
        assertThat(groups.get(1).size()).isEqualTo(1);
    }

    @Test
    void writesToOverlappingPathSerialized() {
        // Writing /a/b.txt and reading /a are overlapping; same tool-name "Edit" so they conflict.
        var calls = List.of(
                new PendingToolCall("1", "Edit", Map.of("path", "/a/b.txt")),
                new PendingToolCall("2", "Edit", Map.of("path", "/a")));
        var groups = analyzer.analyse(calls);
        assertThat(groups).hasSize(2);
    }

    @Test
    void writesToDisjointPathsParallelize() {
        var calls = List.of(
                new PendingToolCall("1", "Edit", Map.of("path", "/foo/a.txt")),
                new PendingToolCall("2", "Edit", Map.of("path", "/bar/b.txt")));
        var groups = analyzer.analyse(calls);
        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).size()).isEqualTo(2);
    }

    @Test
    void mixedReadAndWriteRespectsBoundary() {
        var calls = List.of(
                new PendingToolCall("1", "Read", Map.of("path", "/x.txt")),
                new PendingToolCall("2", "Read", Map.of("path", "/y.txt")),
                new PendingToolCall("3", "Edit", Map.of("path", "/x.txt")),
                new PendingToolCall("4", "Edit", Map.of("path", "/x.txt")));
        var groups = analyzer.analyse(calls);
        // Read1 has /x.txt → conflicts with Edit3 /x.txt. Path-overlap is path-only,
        // not "writer-vs-reader" distinguished — so the Edit gets its own group.
        // Edit4 conflicts with Edit3 → third group.
        assertThat(groups).hasSize(3);
        assertThat(groups.get(0).size()).isEqualTo(2);  // [Read1, Read2]
        assertThat(groups.get(1).size()).isEqualTo(1);  // [Edit3]
        assertThat(groups.get(2).size()).isEqualTo(1);  // [Edit4]
    }

    @Test
    void sameToolWithNoPathsSerialized() {
        // Unknown side-effects → conservative: same tool both ways → serialise
        var calls = List.of(
                new PendingToolCall("1", "ShellCommand", Map.of("command", "make build")),
                new PendingToolCall("2", "ShellCommand", Map.of("command", "make test")));
        var groups = analyzer.analyse(calls);
        assertThat(groups).hasSize(2);
    }

    @Test
    void pathsOverlapDetectsSubdirectories() {
        assertThat(ToolCallSafetyAnalyzer.pathsOverlap("/a/b", "/a/b/c")).isTrue();
        assertThat(ToolCallSafetyAnalyzer.pathsOverlap("/a/b/c", "/a/b")).isTrue();
        assertThat(ToolCallSafetyAnalyzer.pathsOverlap("/a/b", "/a/b")).isTrue();
        assertThat(ToolCallSafetyAnalyzer.pathsOverlap("/a/b", "/a/c")).isFalse();
        // /a/b is NOT inside /a/bc (no shared dir prefix)
        assertThat(ToolCallSafetyAnalyzer.pathsOverlap("/a/b", "/a/bc")).isFalse();
    }

    @Test
    void registryCanBeExtended() {
        var custom = analyzer.withAdditionalParallelSafe("MyReadOnlyTool");
        assertThat(custom.parallelSafeTools()).contains("MyReadOnlyTool");
        var calls = List.of(
                new PendingToolCall("1", "MyReadOnlyTool", Map.of()),
                new PendingToolCall("2", "MyReadOnlyTool", Map.of()));
        assertThat(custom.analyse(calls)).hasSize(1);
    }
}
