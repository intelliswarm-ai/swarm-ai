package ai.intelliswarm.swarmai.tool.code.build;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TestRunnerTool}'s detection logic and argv construction.
 * We don't actually invoke real test runners here — that would require mvn,
 * gradle, npm, pytest, cargo and go all installed on the box.
 */
@DisplayName("TestRunnerTool — auto-detect + argv (1.0.26)")
class TestRunnerToolTest {

    @TempDir Path tmp;

    @Test
    @DisplayName("detects mvn from pom.xml")
    void detectsMvn() throws IOException {
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");
        assertEquals("mvn", TestRunnerTool.detectRunner(tmp));
    }

    @Test
    @DisplayName("detects gradle from build.gradle or build.gradle.kts")
    void detectsGradle() throws IOException {
        Files.writeString(tmp.resolve("build.gradle"), "");
        assertEquals("gradle", TestRunnerTool.detectRunner(tmp));

        Path tmp2 = Files.createTempDirectory("kts");
        Files.writeString(tmp2.resolve("build.gradle.kts"), "");
        assertEquals("gradle", TestRunnerTool.detectRunner(tmp2));
    }

    @Test
    @DisplayName("detects npm/yarn/pnpm via package.json")
    void detectsNpm() throws IOException {
        Files.writeString(tmp.resolve("package.json"), "{}");
        assertEquals("npm", TestRunnerTool.detectRunner(tmp));
    }

    @Test
    @DisplayName("detects cargo from Cargo.toml")
    void detectsCargo() throws IOException {
        Files.writeString(tmp.resolve("Cargo.toml"), "[package]");
        assertEquals("cargo", TestRunnerTool.detectRunner(tmp));
    }

    @Test
    @DisplayName("detects pytest from pyproject.toml/setup.py/tests dir")
    void detectsPytest() throws IOException {
        Files.writeString(tmp.resolve("pyproject.toml"), "");
        assertEquals("pytest", TestRunnerTool.detectRunner(tmp));

        Path tmp2 = Files.createTempDirectory("py2");
        Files.createDirectory(tmp2.resolve("tests"));
        assertEquals("pytest", TestRunnerTool.detectRunner(tmp2));
    }

    @Test
    @DisplayName("detects go from go.mod")
    void detectsGo() throws IOException {
        Files.writeString(tmp.resolve("go.mod"), "module x");
        assertEquals("go", TestRunnerTool.detectRunner(tmp));
    }

    @Test
    @DisplayName("returns null when nothing matches")
    void detectionFailsCleanly() throws IOException {
        assertNull(TestRunnerTool.detectRunner(tmp));
    }

    @Test
    @DisplayName("mvn argv includes -B -q test and -Dtest= when target supplied")
    void mvnArgv() {
        List<String> argv = TestRunnerTool.buildArgv("mvn", null, tmp);
        assertTrue(argv.containsAll(List.of("mvn", "-B", "-q", "test")));
        argv = TestRunnerTool.buildArgv("mvn", "FooTest", tmp);
        assertTrue(argv.contains("-Dtest=FooTest"));
    }

    @Test
    @DisplayName("gradle prefers ./gradlew when present, falls back to gradle")
    void gradleArgv() throws IOException {
        // No wrapper present in tmp → falls back to 'gradle'.
        List<String> argv = TestRunnerTool.buildArgv("gradle", null, tmp);
        assertEquals("gradle", argv.get(0));
        assertTrue(argv.contains("test"));
        assertTrue(argv.contains("--console=plain"));
        // With --tests target.
        argv = TestRunnerTool.buildArgv("gradle", "com.foo.BarTest", tmp);
        assertTrue(argv.contains("--tests"));
        assertTrue(argv.contains("com.foo.BarTest"));
    }

    @Test
    @DisplayName("npm/yarn/pnpm selected by lockfile presence")
    void npmYarnPnpmSelection() throws IOException {
        // Plain npm.
        Files.writeString(tmp.resolve("package.json"), "{}");
        assertEquals("npm", TestRunnerTool.buildArgv("npm", null, tmp).get(0));

        Path tmpYarn = Files.createTempDirectory("yarn-repo");
        Files.writeString(tmpYarn.resolve("yarn.lock"), "");
        assertEquals("yarn", TestRunnerTool.buildArgv("npm", null, tmpYarn).get(0));

        Path tmpPnpm = Files.createTempDirectory("pnpm-repo");
        Files.writeString(tmpPnpm.resolve("pnpm-lock.yaml"), "");
        assertEquals("pnpm", TestRunnerTool.buildArgv("npm", null, tmpPnpm).get(0));
    }

    @Test
    @DisplayName("pytest argv uses -q --no-header and forwards target unchanged")
    void pytestArgv() {
        List<String> argv = TestRunnerTool.buildArgv("pytest", null, tmp);
        assertEquals("pytest", argv.get(0));
        assertTrue(argv.contains("-q"));
        argv = TestRunnerTool.buildArgv("pytest", "tests/test_foo.py::test_bar", tmp);
        assertTrue(argv.contains("tests/test_foo.py::test_bar"));
    }

    @Test
    @DisplayName("go test defaults to ./... unless a target overrides")
    void goArgv() {
        List<String> argv = TestRunnerTool.buildArgv("go", null, tmp);
        assertEquals(List.of("go", "test", "./..."), argv);
        argv = TestRunnerTool.buildArgv("go", "./internal/foo", tmp);
        assertEquals(List.of("go", "test", "./internal/foo"), argv);
    }

    @Test
    @DisplayName("cargo argv passes target as a single positional")
    void cargoArgv() {
        assertEquals(List.of("cargo", "test"), TestRunnerTool.buildArgv("cargo", null, tmp));
        assertEquals(List.of("cargo", "test", "my_module::tests"),
                TestRunnerTool.buildArgv("cargo", "my_module::tests", tmp));
    }

    @Test
    @DisplayName("unsupported runner name returns null")
    void unsupportedRunner() {
        assertNull(TestRunnerTool.buildArgv("mocha", null, tmp));
    }

    @Test
    @DisplayName("execute() refuses paths outside the project root")
    void refusesPathEscape() {
        TestRunnerTool tool = new TestRunnerTool(tmp);
        Object out = tool.execute(Map.of("path", "../../etc"));
        assertTrue(out.toString().contains("refusing to run outside project root"),
                "got: " + out);
    }

    @Test
    @DisplayName("execute() surfaces a clear error when nothing can be auto-detected")
    void autoDetectFailsCleanly() {
        TestRunnerTool tool = new TestRunnerTool(tmp);
        Object out = tool.execute(Map.of());
        assertTrue(out.toString().contains("could not auto-detect"),
                "got: " + out);
    }
}
