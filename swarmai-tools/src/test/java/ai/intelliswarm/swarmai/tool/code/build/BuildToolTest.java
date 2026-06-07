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

@DisplayName("BuildTool — auto-detect + argv (1.0.26)")
class BuildToolTest {

    @TempDir Path tmp;

    @Test
    @DisplayName("mvn wins over everything when pom.xml is present")
    void mvnPriority() throws IOException {
        Files.writeString(tmp.resolve("pom.xml"), "");
        Files.writeString(tmp.resolve("package.json"), "{}");
        assertEquals("mvn", BuildTool.detectBuilder(tmp));
    }

    @Test
    @DisplayName("gradle detected by either .gradle or .gradle.kts")
    void gradleDetect() throws IOException {
        Files.writeString(tmp.resolve("build.gradle.kts"), "");
        assertEquals("gradle", BuildTool.detectBuilder(tmp));
    }

    @Test
    @DisplayName("cargo, go, tsc, npm, make each detected by their markers")
    void otherDetection() throws IOException {
        Path c = Files.createTempDirectory("c"); Files.writeString(c.resolve("Cargo.toml"), "");
        assertEquals("cargo", BuildTool.detectBuilder(c));
        Path g = Files.createTempDirectory("g"); Files.writeString(g.resolve("go.mod"), "");
        assertEquals("go", BuildTool.detectBuilder(g));
        Path n = Files.createTempDirectory("n"); Files.writeString(n.resolve("package.json"), "{}");
        assertEquals("npm", BuildTool.detectBuilder(n));
        Path t = Files.createTempDirectory("t"); Files.writeString(t.resolve("tsconfig.json"), "");
        assertEquals("tsc", BuildTool.detectBuilder(t));
        Path m = Files.createTempDirectory("m"); Files.writeString(m.resolve("Makefile"), "");
        assertEquals("make", BuildTool.detectBuilder(m));
    }

    @Test
    @DisplayName("returns null when no marker present")
    void noMarker() {
        assertNull(BuildTool.detectBuilder(tmp));
    }

    @Test
    @DisplayName("mvn argv = [mvn, -B, -q, -DskipTests, compile]")
    void mvnArgv() {
        assertEquals(List.of("mvn", "-B", "-q", "-DskipTests", "compile"),
                BuildTool.buildArgv("mvn", tmp));
    }

    @Test
    @DisplayName("npm argv uses run build --silent")
    void npmArgv() {
        List<String> argv = BuildTool.buildArgv("npm", tmp);
        assertEquals("npm", argv.get(0));
        assertTrue(argv.contains("run"));
        assertTrue(argv.contains("build"));
    }

    @Test
    @DisplayName("yarn/pnpm wrappers replace npm when their lockfile is present")
    void yarnPnpmWrappers() throws IOException {
        Path y = Files.createTempDirectory("y"); Files.writeString(y.resolve("yarn.lock"), "");
        assertEquals("yarn", BuildTool.buildArgv("npm", y).get(0));
        Path p = Files.createTempDirectory("p"); Files.writeString(p.resolve("pnpm-lock.yaml"), "");
        assertEquals("pnpm", BuildTool.buildArgv("npm", p).get(0));
    }

    @Test
    @DisplayName("go argv builds the full module tree")
    void goArgv() {
        assertEquals(List.of("go", "build", "./..."), BuildTool.buildArgv("go", tmp));
    }

    @Test
    @DisplayName("tsc argv runs in --noEmit mode (type-check only)")
    void tscArgv() {
        assertEquals(List.of("tsc", "--noEmit"), BuildTool.buildArgv("tsc", tmp));
    }

    @Test
    @DisplayName("unsupported builder returns null")
    void unknownBuilder() {
        assertNull(BuildTool.buildArgv("ant", tmp));
    }

    @Test
    @DisplayName("execute() refuses paths escaping the project root")
    void refusesEscape() {
        BuildTool tool = new BuildTool(tmp);
        Object out = tool.execute(Map.of("path", "../../etc"));
        assertTrue(out.toString().contains("refusing to run outside project root"));
    }

    @Test
    @DisplayName("execute() reports clearly when nothing detectable is in the directory")
    void autoDetectFails() {
        BuildTool tool = new BuildTool(tmp);
        Object out = tool.execute(Map.of());
        assertTrue(out.toString().contains("could not auto-detect"), "got: " + out);
    }
}
