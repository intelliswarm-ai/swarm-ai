package ai.intelliswarm.swarmai.tool.os.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OsContext OS detection")
class OsContextTest {

    @Test
    @DisplayName("detect() returns a known OS for the current JVM (sanity)")
    void detectsCurrentJvm() {
        OsType type = OsContext.detect();
        // Whatever CI runs us on, we should pick one of the three. UNKNOWN means
        // the heuristic missed a real OS and we have a bug.
        assertThat(type).isIn(OsType.WINDOWS, OsType.MACOS, OsType.LINUX);
    }

    @Test
    @DisplayName("isPosix returns true for Mac and Linux only")
    void posixGroup() {
        assertThat(OsType.MACOS.isPosix()).isTrue();
        assertThat(OsType.LINUX.isPosix()).isTrue();
        assertThat(OsType.WINDOWS.isPosix()).isFalse();
        assertThat(OsType.UNKNOWN.isPosix()).isFalse();
    }

    @Test
    @DisplayName("Test seam — OsContext(OsType) lets tests inject any OS")
    void testSeam() {
        OsContext win = new OsContext(OsType.WINDOWS);
        assertThat(win.isWindows()).isTrue();
        assertThat(win.isMacOs()).isFalse();
        assertThat(win.isLinux()).isFalse();
        OsContext mac = new OsContext(OsType.MACOS);
        assertThat(mac.isMacOs()).isTrue();
    }

    @Test
    @DisplayName("detect() is private, but the public no-arg constructor uses it")
    void noArgConstructor() {
        OsContext ctx = new OsContext();
        assertThat(ctx.type()).isNotNull().isNotEqualTo(OsType.UNKNOWN);
    }
}
