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

@DisplayName("DependencyTool — auto-detect + argv (1.0.25)")
class DependencyToolTest {

    @TempDir Path tmp;

    @Test
    @DisplayName("detects mvn from pom.xml")
    void detectsMvn() throws IOException {
        Files.writeString(tmp.resolve("pom.xml"), "");
        assertEquals("mvn", DependencyTool.detectManager(tmp));
    }

    @Test
    @DisplayName("pnpm wins over npm when both lockfiles imply it")
    void pnpmWinsOverNpm() throws IOException {
        Files.writeString(tmp.resolve("package.json"), "{}");
        Files.writeString(tmp.resolve("pnpm-lock.yaml"), "");
        assertEquals("pnpm", DependencyTool.detectManager(tmp));
    }

    @Test
    @DisplayName("yarn wins over npm when yarn.lock is present")
    void yarnWinsOverNpm() throws IOException {
        Files.writeString(tmp.resolve("package.json"), "{}");
        Files.writeString(tmp.resolve("yarn.lock"), "");
        assertEquals("yarn", DependencyTool.detectManager(tmp));
    }

    @Test
    @DisplayName("falls back to plain npm on package.json without lockfile")
    void plainNpm() throws IOException {
        Files.writeString(tmp.resolve("package.json"), "{}");
        assertEquals("npm", DependencyTool.detectManager(tmp));
    }

    @Test
    @DisplayName("detects poetry from poetry.lock and pip from requirements.txt")
    void detectsPython() throws IOException {
        Path poetry = Files.createTempDirectory("poetry");
        Files.writeString(poetry.resolve("poetry.lock"), "");
        assertEquals("poetry", DependencyTool.detectManager(poetry));

        Path pip = Files.createTempDirectory("pip");
        Files.writeString(pip.resolve("requirements.txt"), "");
        assertEquals("pip", DependencyTool.detectManager(pip));
    }

    @Test
    @DisplayName("returns null when nothing detectable")
    void noMarker() {
        assertNull(DependencyTool.detectManager(tmp));
    }

    @Test
    @DisplayName("mvn list/tree/why argv shapes")
    void mvnArgv() {
        assertEquals(List.of("mvn", "-B", "-q", "dependency:list"),
                DependencyTool.buildArgv("mvn", "list", null));
        assertEquals(List.of("mvn", "-B", "-q", "dependency:tree"),
                DependencyTool.buildArgv("mvn", "tree", null));
        assertEquals(List.of("mvn", "-B", "-q", "dependency:tree", "-Dincludes=com.foo:bar"),
                DependencyTool.buildArgv("mvn", "why", "com.foo:bar"));
    }

    @Test
    @DisplayName("npm list/tree/why argv shapes")
    void npmArgv() {
        assertEquals(List.of("npm", "ls", "--depth=0"),
                DependencyTool.buildArgv("npm", "list", null));
        assertEquals(List.of("npm", "ls"),
                DependencyTool.buildArgv("npm", "tree", null));
        assertEquals(List.of("npm", "explain", "lodash"),
                DependencyTool.buildArgv("npm", "why", "lodash"));
    }

    @Test
    @DisplayName("go why dispatches to 'go mod why' (not 'go list')")
    void goWhyArgv() {
        assertEquals(List.of("go", "mod", "why", "k8s.io/api"),
                DependencyTool.buildArgv("go", "why", "k8s.io/api"));
    }

    @Test
    @DisplayName("cargo why uses 'cargo tree -i'")
    void cargoWhyArgv() {
        assertEquals(List.of("cargo", "tree", "-i", "serde"),
                DependencyTool.buildArgv("cargo", "why", "serde"));
    }

    @Test
    @DisplayName("unsupported operation/manager returns null")
    void unsupported() {
        assertNull(DependencyTool.buildArgv("mvn", "audit", null));
        assertNull(DependencyTool.buildArgv("bower", "list", null));
    }

    @Test
    @DisplayName("execute() rejects 'why' without a target")
    void whyRequiresTarget() throws IOException {
        Files.writeString(tmp.resolve("pom.xml"), "");
        DependencyTool tool = new DependencyTool(tmp);
        Object out = tool.execute(Map.of("operation", "why"));
        assertTrue(out.toString().contains("operation=why requires 'target'"), "got: " + out);
    }

    @Test
    @DisplayName("execute() rejects malformed target names")
    void rejectsMalformedTarget() throws IOException {
        Files.writeString(tmp.resolve("pom.xml"), "");
        DependencyTool tool = new DependencyTool(tmp);
        Object out = tool.execute(Map.of("operation", "why", "target", "foo; rm -rf /"));
        assertTrue(out.toString().contains("must look like a package name"));
    }

    @Test
    @DisplayName("execute() refuses paths escaping the project root")
    void refusesEscape() {
        DependencyTool tool = new DependencyTool(tmp);
        Object out = tool.execute(Map.of("path", "../../etc"));
        assertTrue(out.toString().contains("refusing to run outside project root"));
    }

    @Test
    @DisplayName("execute() reports clearly when nothing detectable in dir")
    void autoDetectFails() {
        DependencyTool tool = new DependencyTool(tmp);
        Object out = tool.execute(Map.of());
        assertTrue(out.toString().contains("could not auto-detect"));
    }
}
