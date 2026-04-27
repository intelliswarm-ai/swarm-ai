package ai.intelliswarm.swarmai.tool.os;

import ai.intelliswarm.swarmai.tool.os.shared.PathGuard;
import ai.intelliswarm.swarmai.tool.safety.AutoApproveMutationGuard;
import ai.intelliswarm.swarmai.tool.safety.MutationGuard;
import ai.intelliswarm.swarmai.tool.safety.MutationPlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FileSystemTool — cross-platform read + plan/apply mutations")
class FileSystemToolTest {

    private FileSystemTool toolFor(Path root, MutationGuard guard, boolean dryRunDefault) {
        return new FileSystemTool(new PathGuard(List.of(root.toString())), guard, dryRunDefault);
    }

    private FileSystemTool toolFor(Path root) {
        return toolFor(root, new AutoApproveMutationGuard(), true);
    }

    @Test
    @DisplayName("Tool metadata is correct + cross-platform OS list")
    void metadata() {
        FileSystemTool t = toolFor(java.nio.file.Paths.get(System.getProperty("java.io.tmpdir")));
        assertThat(t.getFunctionName()).isEqualTo("os_filesystem");
        assertThat(t.getCategory()).isEqualTo("os-control");
        assertThat(t.getTags()).contains("os-control", "filesystem", "cross-platform");
        assertThat(t.getRequirements().os()).containsExactlyInAnyOrder("windows", "darwin", "linux");
    }

    @Test
    @DisplayName("list() on a populated directory returns each entry with size + mtime")
    void listDirectory(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("a.txt"), "hello");
        Files.writeString(tmp.resolve("b.txt"), "world!");
        Files.createDirectory(tmp.resolve("sub"));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) toolFor(tmp).execute(Map.of(
                "operation", "list", "path", tmp.toString()));
        assertThat(result.get("count")).isEqualTo(3);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) result.get("entries");
        assertThat(entries).extracting(e -> e.get("name")).contains("a.txt", "b.txt", "sub");
    }

    @Test
    @DisplayName("Path outside allowlist is refused — never touches the filesystem")
    void pathOutsideAllowlist(@TempDir Path tmp) {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) toolFor(tmp).execute(Map.of(
                "operation", "list", "path", tmp.getParent().toString()));
        assertThat(result).containsKey("error");
        assertThat(result.get("error").toString()).contains("path-not-allowed");
    }

    @Test
    @DisplayName("Mutating op without apply=true returns a plan, not an action")
    void planOnly(@TempDir Path tmp) {
        Path target = tmp.resolve("new-dir");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) toolFor(tmp).execute(Map.of(
                "operation", "mkdir", "path", target.toString()));
        assertThat(result.get("planned")).isEqualTo(true);
        assertThat(result.get("risk")).isEqualTo("LOW");
        assertThat(Files.exists(target)).isFalse();
    }

    @Test
    @DisplayName("apply=true with auto-approve guard executes the op")
    void applyExecutes(@TempDir Path tmp) throws Exception {
        Path target = tmp.resolve("created-by-test");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) toolFor(tmp).execute(Map.of(
                "operation", "mkdir", "path", target.toString(), "apply", true));
        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(Files.isDirectory(target)).isTrue();
    }

    @Test
    @DisplayName("Mutating op with a rejecting guard surfaces a clear error and skips the action")
    void rejectedByGuard(@TempDir Path tmp) {
        MutationGuard reject = plan -> MutationGuard.Decision.reject("test", "denied");
        Path target = tmp.resolve("should-not-exist");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) toolFor(tmp, reject, true).execute(Map.of(
                "operation", "mkdir", "path", target.toString(), "apply", true));
        assertThat(result.get("error").toString()).contains("rejected");
        assertThat(Files.exists(target)).isFalse();
    }

    @Test
    @DisplayName("write_text + apply writes content, append_text appends to it")
    void writeAndAppend(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("notes.txt");
        FileSystemTool t = toolFor(tmp);
        t.execute(Map.of("operation", "write_text", "path", file.toString(),
                "content", "alpha\n", "apply", true));
        t.execute(Map.of("operation", "append_text", "path", file.toString(),
                "content", "beta\n", "apply", true));
        assertThat(Files.readString(file)).isEqualTo("alpha\nbeta\n");
    }

    @Test
    @DisplayName("delete is HIGH risk")
    void deleteRiskLevel(@TempDir Path tmp) {
        Path file = tmp.resolve("doomed.txt");
        try { Files.writeString(file, ""); } catch (Exception ignored) {}
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) toolFor(tmp).execute(Map.of(
                "operation", "delete", "path", file.toString()));
        assertThat(result.get("planned")).isEqualTo(true);
        assertThat(result.get("risk")).isEqualTo("HIGH");
    }

    @Test
    @DisplayName("Unknown operation returns a clear error")
    void unknownOperation(@TempDir Path tmp) {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) toolFor(tmp).execute(Map.of(
                "operation", "format_drive_c"));
        assertThat(result.get("error").toString()).contains("unknown operation");
    }

    @Test
    @DisplayName("Missing operation returns a clear error listing the available ops")
    void missingOperation(@TempDir Path tmp) {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) toolFor(tmp).execute(Map.of());
        String err = result.get("error").toString();
        assertThat(err).contains("missing 'operation'").contains("list").contains("delete");
    }
}
