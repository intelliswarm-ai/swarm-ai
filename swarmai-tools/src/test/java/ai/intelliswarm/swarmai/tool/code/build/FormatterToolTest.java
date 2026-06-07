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

@DisplayName("FormatterTool — auto-detect + argv (1.0.26)")
class FormatterToolTest {

    @TempDir Path tmp;

    @Test
    @DisplayName("detects prettier from .prettierrc")
    void detectsPrettier() throws IOException {
        Files.writeString(tmp.resolve(".prettierrc"), "{}");
        assertEquals("prettier", FormatterTool.detectFormatter(tmp));
    }

    @Test
    @DisplayName("detects black from pyproject [tool.black]")
    void detectsBlack() throws IOException {
        Files.writeString(tmp.resolve("pyproject.toml"),
                "[tool.black]\nline-length = 100\n");
        assertEquals("black", FormatterTool.detectFormatter(tmp));
    }

    @Test
    @DisplayName("ruff.format wins over black when both present")
    void ruffFormatBeatsBlack() throws IOException {
        Files.writeString(tmp.resolve("pyproject.toml"),
                "[tool.ruff.format]\nquote-style = \"double\"\n\n[tool.black]\nline-length = 88\n");
        assertEquals("ruff", FormatterTool.detectFormatter(tmp));
    }

    @Test
    @DisplayName("detects gofmt, cargo-fmt, rubocop, spotless")
    void detectsOthers() throws IOException {
        Path g = Files.createTempDirectory("g"); Files.writeString(g.resolve("go.mod"), "");
        assertEquals("gofmt", FormatterTool.detectFormatter(g));
        Path r = Files.createTempDirectory("r"); Files.writeString(r.resolve("Cargo.toml"), "");
        assertEquals("cargo-fmt", FormatterTool.detectFormatter(r));
        Path ru = Files.createTempDirectory("ru"); Files.writeString(ru.resolve(".rubocop.yml"), "");
        assertEquals("rubocop", FormatterTool.detectFormatter(ru));
        Path m = Files.createTempDirectory("m"); Files.writeString(m.resolve("pom.xml"), "");
        assertEquals("spotless", FormatterTool.detectFormatter(m));
    }

    @Test
    @DisplayName("prettier default mode is --check, write=true switches to --write")
    void prettierArgv() {
        assertEquals(List.of("prettier", "--check", "."),
                FormatterTool.buildArgv("prettier", false, null, tmp));
        assertEquals(List.of("prettier", "--write", "src/"),
                FormatterTool.buildArgv("prettier", true, "src/", tmp));
    }

    @Test
    @DisplayName("black default = --check, write=true drops the flag")
    void blackArgv() {
        assertEquals(List.of("black", "--check", "."),
                FormatterTool.buildArgv("black", false, null, tmp));
        assertEquals(List.of("black", "."),
                FormatterTool.buildArgv("black", true, null, tmp));
    }

    @Test
    @DisplayName("gofmt uses -l in check mode and -w in write mode")
    void gofmtArgv() {
        assertEquals(List.of("gofmt", "-l", "."),
                FormatterTool.buildArgv("gofmt", false, null, tmp));
        assertEquals(List.of("gofmt", "-w", "src/"),
                FormatterTool.buildArgv("gofmt", true, "src/", tmp));
    }

    @Test
    @DisplayName("cargo fmt uses --check in check mode")
    void cargoFmtArgv() {
        assertEquals(List.of("cargo", "fmt", "--check"),
                FormatterTool.buildArgv("cargo-fmt", false, null, tmp));
        assertEquals(List.of("cargo", "fmt"),
                FormatterTool.buildArgv("cargo-fmt", true, null, tmp));
    }

    @Test
    @DisplayName("spotless dispatches to spotless:check / spotless:apply")
    void spotlessArgv() {
        assertEquals(List.of("mvn", "-B", "-q", "spotless:check"),
                FormatterTool.buildArgv("spotless", false, null, tmp));
        assertEquals(List.of("mvn", "-B", "-q", "spotless:apply"),
                FormatterTool.buildArgv("spotless", true, null, tmp));
    }

    @Test
    @DisplayName("ruff format honours write/check mode")
    void ruffFormatArgv() {
        assertEquals(List.of("ruff", "format", "--check", "."),
                FormatterTool.buildArgv("ruff", false, null, tmp));
        assertEquals(List.of("ruff", "format", "."),
                FormatterTool.buildArgv("ruff", true, null, tmp));
    }

    @Test
    @DisplayName("unsupported formatter returns null")
    void unsupported() {
        assertNull(FormatterTool.buildArgv("biome", false, null, tmp));
    }

    @Test
    @DisplayName("execute() refuses paths escaping the project root")
    void refusesEscape() {
        FormatterTool tool = new FormatterTool(tmp);
        Object out = tool.execute(Map.of("path", "../../etc"));
        assertTrue(out.toString().contains("refusing to run outside project root"));
    }

    @Test
    @DisplayName("execute() reports clearly when nothing detectable in dir")
    void autoDetectFails() {
        FormatterTool tool = new FormatterTool(tmp);
        Object out = tool.execute(Map.of());
        assertTrue(out.toString().contains("could not auto-detect"));
    }
}
