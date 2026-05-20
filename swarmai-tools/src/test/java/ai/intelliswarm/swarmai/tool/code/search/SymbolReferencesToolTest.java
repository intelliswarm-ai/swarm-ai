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

@DisplayName("SymbolReferencesTool — usage finder (1.0.26)")
class SymbolReferencesToolTest {

    @TempDir Path tmp;
    private SymbolReferencesTool tool;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(tmp.resolve("src/java"));
        Files.createDirectories(tmp.resolve("src/py"));
        Files.createDirectories(tmp.resolve("src/ts"));
        tool = new SymbolReferencesTool(tmp);
    }

    @Test
    @DisplayName("finds usages across multiple files and groups by file")
    void findsAcrossFiles() throws IOException {
        Files.writeString(tmp.resolve("src/java/A.java"),
                "class A {\n  void use() { B.doThing(); }\n}\n");
        Files.writeString(tmp.resolve("src/java/C.java"),
                "class C {\n  void also() { new B(); B.doThing(); }\n}\n");

        Object out = tool.execute(Map.of("symbol", "B"));
        String s = out.toString();
        assertTrue(s.contains("A.java"), "got: " + s);
        assertTrue(s.contains("C.java"), "got: " + s);
        assertTrue(s.contains("Found"));
        assertTrue(s.contains("across 2 file"));
    }

    @Test
    @DisplayName("by default excludes the definition line")
    void excludesDefByDefault() throws IOException {
        Files.writeString(tmp.resolve("src/java/A.java"),
                "class Widget {\n  void grow() {}\n}\n");
        Files.writeString(tmp.resolve("src/java/B.java"),
                "class B {\n  Widget w = new Widget();\n}\n");

        Object out = tool.execute(Map.of("symbol", "Widget"));
        String s = out.toString();
        // Definition line in A.java should NOT appear.
        assertFalse(s.contains("class Widget"),
                "definition line should be excluded by default, got: " + s);
        // Usage in B.java SHOULD appear.
        assertTrue(s.contains("B.java"));
        assertTrue(s.contains("new Widget"));
    }

    @Test
    @DisplayName("include_definitions=true keeps declaration sites")
    void includeDefinitionsTrue() throws IOException {
        Files.writeString(tmp.resolve("src/java/A.java"),
                "class Widget {}\n");
        Files.writeString(tmp.resolve("src/java/B.java"),
                "class B { Widget w; }\n");

        Object out = tool.execute(Map.of(
                "symbol", "Widget",
                "include_definitions", true));
        String s = out.toString();
        assertTrue(s.contains("class Widget"),
                "include_definitions=true should include declaration line, got: " + s);
        assertTrue(s.contains("Widget w"));
        assertTrue(s.contains("incl. definitions"));
    }

    @Test
    @DisplayName("word-boundary excludes substring matches")
    void wordBoundary() throws IOException {
        Files.writeString(tmp.resolve("src/java/A.java"),
                "class Foo {}\nclass FooBar {}\nclass MyFoo {}\n");
        // "Foo" should match only the standalone Foo, not FooBar/MyFoo.
        Object out = tool.execute(Map.of("symbol", "Foo", "include_definitions", true));
        String s = out.toString();
        long fooHits = s.lines()
                .filter(l -> l.contains("class Foo {"))
                .count();
        assertEquals(1, fooHits, "expected exactly one match for 'class Foo {', got: " + s);
        assertFalse(s.contains("FooBar"));
        assertFalse(s.contains("MyFoo"));
    }

    @Test
    @DisplayName("lang filter limits search to that language's extensions")
    void langFilter() throws IOException {
        Files.writeString(tmp.resolve("src/java/A.java"),
                "class A { Marker m; }\n");
        Files.writeString(tmp.resolve("src/py/m.py"),
                "from x import Marker\n");
        Files.writeString(tmp.resolve("src/ts/m.ts"),
                "const Marker = require('x');\n");

        Object out = tool.execute(Map.of("symbol", "Marker", "lang", "python"));
        String s = out.toString();
        assertTrue(s.contains("m.py"));
        assertFalse(s.contains("A.java"));
        assertFalse(s.contains("m.ts"));
    }

    @Test
    @DisplayName("returns the no-references sentinel when symbol is absent")
    void noRefsSentinel() throws IOException {
        Files.writeString(tmp.resolve("src/java/A.java"), "class A {}\n");
        Object out = tool.execute(Map.of("symbol", "Nope"));
        assertTrue(out.toString().contains("no references to 'Nope' found"),
                "got: " + out);
    }

    @Test
    @DisplayName("missing or invalid symbol surfaces a usage error")
    void invalidInputs() {
        assertTrue(tool.execute(Map.of()).toString().contains("'symbol' is required"));
        assertTrue(tool.execute(Map.of("symbol", "has space")).toString()
                .contains("plain identifier"));
    }

    @Test
    @DisplayName("skips common build/vendored directories")
    void skipsVendoredDirs() throws IOException {
        Files.createDirectories(tmp.resolve("node_modules/x"));
        Files.writeString(tmp.resolve("node_modules/x/y.js"),
                "const Hidden = 1;\n");
        Files.writeString(tmp.resolve("src/ts/visible.ts"),
                "use(Hidden);\n");

        Object out = tool.execute(Map.of("symbol", "Hidden"));
        assertTrue(out.toString().contains("visible.ts"));
        assertFalse(out.toString().contains("node_modules"));
    }

    @Test
    @DisplayName("limit caps the result count")
    void limitWorks() throws IOException {
        StringBuilder sb = new StringBuilder("class A {\n");
        for (int i = 0; i < 50; i++) sb.append("  X x").append(i).append(";\n");
        sb.append("}\n");
        Files.writeString(tmp.resolve("src/java/A.java"), sb.toString());
        Object out = tool.execute(Map.of("symbol", "X", "limit", 5));
        long lineCount = out.toString().lines()
                .filter(l -> l.contains(":") && l.contains("X x"))
                .count();
        assertTrue(lineCount <= 5, "limit should cap to 5 hits, got " + lineCount + "\n" + out);
    }

    @Test
    @DisplayName("refuses paths escaping the project root")
    void refusesEscape() {
        Object out = tool.execute(Map.of("symbol", "X", "path", "../../etc"));
        assertTrue(out.toString().contains("refusing to search outside project root"));
    }
}
