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

@DisplayName("LintTool — auto-detect + argv (1.0.25)")
class LintToolTest {

    @TempDir Path tmp;

    @Test
    @DisplayName("detects eslint from any .eslintrc* variant")
    void detectsEslint() throws IOException {
        Files.writeString(tmp.resolve(".eslintrc.json"), "{}");
        assertEquals("eslint", LintTool.detectLinter(tmp));
    }

    @Test
    @DisplayName("detects eslint flat config too")
    void detectsEslintFlat() throws IOException {
        Files.writeString(tmp.resolve("eslint.config.mjs"), "");
        assertEquals("eslint", LintTool.detectLinter(tmp));
    }

    @Test
    @DisplayName("detects ruff from ruff.toml or pyproject [tool.ruff]")
    void detectsRuff() throws IOException {
        Files.writeString(tmp.resolve("ruff.toml"), "");
        assertEquals("ruff", LintTool.detectLinter(tmp));

        Path tmp2 = Files.createTempDirectory("ruff2");
        Files.writeString(tmp2.resolve("pyproject.toml"),
                "[tool.ruff]\nline-length = 100\n");
        assertEquals("ruff", LintTool.detectLinter(tmp2));
    }

    @Test
    @DisplayName("detects flake8 from .flake8 file")
    void detectsFlake8() throws IOException {
        Files.writeString(tmp.resolve(".flake8"), "");
        assertEquals("flake8", LintTool.detectLinter(tmp));
    }

    @Test
    @DisplayName("detects golangci-lint, clippy, rubocop, checkstyle")
    void detectsOthers() throws IOException {
        Path g = Files.createTempDirectory("g"); Files.writeString(g.resolve(".golangci.yml"), "");
        assertEquals("golangci-lint", LintTool.detectLinter(g));
        Path r = Files.createTempDirectory("r"); Files.writeString(r.resolve("Cargo.toml"), "");
        assertEquals("clippy", LintTool.detectLinter(r));
        Path ru = Files.createTempDirectory("ru"); Files.writeString(ru.resolve(".rubocop.yml"), "");
        assertEquals("rubocop", LintTool.detectLinter(ru));
        Path c = Files.createTempDirectory("c"); Files.writeString(c.resolve("checkstyle.xml"), "");
        assertEquals("checkstyle", LintTool.detectLinter(c));
    }

    @Test
    @DisplayName("eslint argv adds --fix when fix=true")
    void eslintArgv() {
        assertEquals(List.of("eslint", "."), LintTool.buildArgv("eslint", false, null));
        assertEquals(List.of("eslint", "--fix", "."), LintTool.buildArgv("eslint", true, null));
        assertEquals(List.of("eslint", "--fix", "src/"), LintTool.buildArgv("eslint", true, "src/"));
    }

    @Test
    @DisplayName("ruff argv is 'ruff check ...' and adds --fix when requested")
    void ruffArgv() {
        assertEquals(List.of("ruff", "check", "."), LintTool.buildArgv("ruff", false, null));
        assertEquals(List.of("ruff", "check", "--fix", "."), LintTool.buildArgv("ruff", true, null));
    }

    @Test
    @DisplayName("clippy denies warnings and supports --fix flag")
    void clippyArgv() {
        List<String> argv = LintTool.buildArgv("clippy", false, null);
        assertEquals(List.of("cargo", "clippy", "--", "-D", "warnings"), argv);
        argv = LintTool.buildArgv("clippy", true, null);
        assertTrue(argv.contains("--fix"));
        assertTrue(argv.contains("--allow-dirty"));
        assertTrue(argv.contains("-D"));
    }

    @Test
    @DisplayName("rubocop autocorrect uses --autocorrect (not --fix)")
    void rubocopArgv() {
        assertEquals(List.of("rubocop"), LintTool.buildArgv("rubocop", false, null));
        assertEquals(List.of("rubocop", "--autocorrect"), LintTool.buildArgv("rubocop", true, null));
    }

    @Test
    @DisplayName("checkstyle dispatches to mvn checkstyle:check")
    void checkstyleArgv() {
        assertEquals(List.of("mvn", "-B", "-q", "checkstyle:check"),
                LintTool.buildArgv("checkstyle", false, null));
    }

    @Test
    @DisplayName("unsupported linter returns null")
    void unsupported() {
        assertNull(LintTool.buildArgv("biome", false, null));
    }

    @Test
    @DisplayName("execute() refuses paths escaping the project root")
    void refusesEscape() {
        LintTool tool = new LintTool(tmp);
        Object out = tool.execute(Map.of("path", "../../etc"));
        assertTrue(out.toString().contains("refusing to run outside project root"));
    }

    @Test
    @DisplayName("execute() reports clearly when nothing detectable")
    void autoDetectFails() {
        LintTool tool = new LintTool(tmp);
        Object out = tool.execute(Map.of());
        assertTrue(out.toString().contains("could not auto-detect"));
    }
}
