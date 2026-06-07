package ai.intelliswarm.swarmai.tool.os.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PathGuard allowlist + traversal defense")
class PathGuardTest {

    @Test
    @DisplayName("Empty allowlist denies every path")
    void emptyAllowlistDenies(@TempDir Path tmp) {
        PathGuard g = new PathGuard(List.of());
        assertThat(g.isAllowed(tmp)).isFalse();
        assertThatThrownBy(() -> g.require(tmp)).isInstanceOf(PathNotAllowedException.class);
    }

    @Test
    @DisplayName("Path under an allowed root is permitted")
    void allowsUnderRoot(@TempDir Path tmp) {
        PathGuard g = new PathGuard(List.of(tmp.toString()));
        Path child = tmp.resolve("subdir/file.txt");
        assertThat(g.isAllowed(child)).isTrue();
    }

    @Test
    @DisplayName("'../' traversal escaping the root is denied after normalization")
    void traversalDenied(@TempDir Path tmp) {
        PathGuard g = new PathGuard(List.of(tmp.toString()));
        // tmp/sub/../../outside.txt resolves to a parent of tmp — outside the allowlist
        Path escaping = tmp.resolve("sub").resolve("..").resolve("..").resolve("outside.txt");
        assertThat(g.isAllowed(escaping)).isFalse();
    }

    @Test
    @DisplayName("expand() resolves ${user.home} and ${java.io.tmpdir}")
    void expandsSystemProps() {
        String home = PathGuard.expand("${user.home}/Desktop");
        assertThat(home).isEqualTo(System.getProperty("user.home") + "/Desktop");
        String tmp = PathGuard.expand("${java.io.tmpdir}/swarmai");
        assertThat(tmp).isEqualTo(System.getProperty("java.io.tmpdir") + "/swarmai");
    }

    @Test
    @DisplayName("expand() leaves unknown placeholders untouched")
    void expandKeepsUnknown() {
        assertThat(PathGuard.expand("${no.such.prop.zzz}/x")).isEqualTo("${no.such.prop.zzz}/x");
    }

    @Test
    @DisplayName("expand() handles strings with no placeholders")
    void expandPlainString() {
        assertThat(PathGuard.expand("/literal/path/no/vars")).isEqualTo("/literal/path/no/vars");
    }

    @Test
    @DisplayName("Null candidate to require() throws")
    void requireNull() {
        PathGuard g = new PathGuard(List.of("/tmp"));
        assertThatThrownBy(() -> g.require((Path) null)).isInstanceOf(PathNotAllowedException.class);
    }

    @Test
    @DisplayName("Legacy windows.internal.PathGuard subclass is API-compatible")
    void legacyAliasWorks(@TempDir Path tmp) {
        ai.intelliswarm.swarmai.tool.windows.internal.PathGuard legacy =
                new ai.intelliswarm.swarmai.tool.windows.internal.PathGuard(List.of(tmp.toString()));
        assertThat(legacy.isAllowed(tmp.resolve("x"))).isTrue();
        // Static expand is also re-exported from the legacy package
        assertThat(ai.intelliswarm.swarmai.tool.windows.internal.PathGuard.expand("${user.home}/x"))
                .startsWith(System.getProperty("user.home"));
    }
}
