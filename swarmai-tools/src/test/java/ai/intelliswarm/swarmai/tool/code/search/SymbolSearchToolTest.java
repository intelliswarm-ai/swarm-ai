package ai.intelliswarm.swarmai.tool.code.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SymbolSearchTool — definition finder (1.0.25)")
class SymbolSearchToolTest {

    @TempDir Path tmp;
    private SymbolSearchTool tool;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(tmp.resolve("src/java"));
        Files.createDirectories(tmp.resolve("src/py"));
        Files.createDirectories(tmp.resolve("src/ts"));
        Files.createDirectories(tmp.resolve("src/go"));
        Files.createDirectories(tmp.resolve("src/rust"));
        tool = new SymbolSearchTool(tmp);
    }

    @Test
    @DisplayName("finds Java class declarations")
    void findsJavaClass() throws IOException {
        Files.writeString(tmp.resolve("src/java/Foo.java"),
                "package x;\n\npublic class FooBar {\n  void hi() {}\n}\n");
        Object out = tool.execute(Map.of("symbol", "FooBar"));
        assertTrue(out.toString().contains("Foo.java"), "got: " + out);
        assertTrue(out.toString().contains("public class FooBar"), "got: " + out);
    }

    @Test
    @DisplayName("finds Java method declarations with various modifier combos")
    void findsJavaMethod() throws IOException {
        Files.writeString(tmp.resolve("src/java/X.java"),
                "class X {\n  public static void doThing(int n) {}\n  private List<String> doThingAlt() { return null; }\n}\n");
        Object out = tool.execute(Map.of("symbol", "doThing"));
        // 'doThing' is a word-boundary match — must not collide with 'doThingAlt'.
        assertTrue(out.toString().contains("public static void doThing"), "got: " + out);
        assertFalse(out.toString().contains("doThingAlt"),
                "word-boundary should not match doThingAlt, got: " + out);
    }

    @Test
    @DisplayName("finds Python def and class declarations")
    void findsPython() throws IOException {
        Files.writeString(tmp.resolve("src/py/m.py"),
                "class Pipeline:\n    def run(self):\n        pass\n\nasync def fetch(url):\n    return None\n");
        assertTrue(tool.execute(Map.of("symbol", "Pipeline")).toString().contains("class Pipeline"));
        assertTrue(tool.execute(Map.of("symbol", "run")).toString().contains("def run"));
        assertTrue(tool.execute(Map.of("symbol", "fetch")).toString().contains("async def fetch"));
    }

    @Test
    @DisplayName("finds TypeScript function/class/const declarations")
    void findsTypeScript() throws IOException {
        Files.writeString(tmp.resolve("src/ts/api.ts"),
                "export function loadConfig() { return {}; }\n"
              + "export class Server {}\n"
              + "const PORT = 8080;\n");
        assertTrue(tool.execute(Map.of("symbol", "loadConfig")).toString().contains("function loadConfig"));
        assertTrue(tool.execute(Map.of("symbol", "Server")).toString().contains("class Server"));
        assertTrue(tool.execute(Map.of("symbol", "PORT")).toString().contains("const PORT"));
    }

    @Test
    @DisplayName("finds Go func and type declarations")
    void findsGo() throws IOException {
        Files.writeString(tmp.resolve("src/go/m.go"),
                "package main\n\nfunc Run(args []string) error { return nil }\n"
              + "type Server struct { port int }\n");
        assertTrue(tool.execute(Map.of("symbol", "Run")).toString().contains("func Run"));
        assertTrue(tool.execute(Map.of("symbol", "Server")).toString().contains("type Server"));
    }

    @Test
    @DisplayName("finds Rust fn, struct, trait declarations")
    void findsRust() throws IOException {
        Files.writeString(tmp.resolve("src/rust/lib.rs"),
                "pub struct Engine { ticks: u64 }\n"
              + "pub trait Run { fn run(&self) -> bool; }\n"
              + "pub fn boot() -> Engine { Engine { ticks: 0 } }\n");
        assertTrue(tool.execute(Map.of("symbol", "Engine")).toString().contains("struct Engine"));
        assertTrue(tool.execute(Map.of("symbol", "Run")).toString().contains("trait Run"));
        assertTrue(tool.execute(Map.of("symbol", "boot")).toString().contains("fn boot"));
    }

    @Test
    @DisplayName("lang filter scopes search to one language only")
    void langFilter() throws IOException {
        Files.writeString(tmp.resolve("src/java/Foo.java"), "class Marker {}\n");
        Files.writeString(tmp.resolve("src/py/foo.py"), "class Marker: pass\n");
        Object outJava = tool.execute(Map.of("symbol", "Marker", "lang", "java"));
        assertTrue(outJava.toString().contains("Foo.java"));
        assertFalse(outJava.toString().contains("foo.py"));
        Object outPy = tool.execute(Map.of("symbol", "Marker", "lang", "python"));
        assertTrue(outPy.toString().contains("foo.py"));
        assertFalse(outPy.toString().contains("Foo.java"));
    }

    @Test
    @DisplayName("missing or invalid symbol surfaces a usage error")
    void invalidInputs() {
        assertTrue(tool.execute(Map.of()).toString().contains("'symbol' is required"));
        assertTrue(tool.execute(Map.of("symbol", "with-dash")).toString()
                .contains("plain identifier"));
        assertTrue(tool.execute(Map.of("symbol", "ok", "lang", "haskell")).toString()
                .contains("unsupported lang"));
    }

    @Test
    @DisplayName("returns 'no definition' sentinel when symbol is absent")
    void noDefSentinel() throws IOException {
        Files.writeString(tmp.resolve("src/java/X.java"), "class X {}\n");
        Object out = tool.execute(Map.of("symbol", "Missing"));
        assertTrue(out.toString().contains("no definition of 'Missing' found"),
                "got: " + out);
    }

    @Test
    @DisplayName("skips common build/vendor directories")
    void skipsVendoredDirs() throws IOException {
        Files.createDirectories(tmp.resolve("node_modules/some-lib"));
        Files.writeString(tmp.resolve("node_modules/some-lib/foo.js"),
                "class Hidden {}\n");
        Files.writeString(tmp.resolve("src/ts/visible.ts"), "class Hidden {}\n");
        Object out = tool.execute(Map.of("symbol", "Hidden"));
        assertTrue(out.toString().contains("visible.ts"));
        assertFalse(out.toString().contains("node_modules"),
                "must skip node_modules, got: " + out);
    }

    @Test
    @DisplayName("refuses 'path' values that escape the project root")
    void refusesEscape() {
        Object out = tool.execute(Map.of("symbol", "X", "path", "../../etc"));
        assertTrue(out.toString().contains("refusing to search outside project root"));
    }
}
