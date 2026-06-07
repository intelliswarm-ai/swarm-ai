package ai.intelliswarm.swarmai.tool.code.git;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@DisplayName("GitTool — read-only git operations (1.0.26)")
@EnabledOnOs({OS.LINUX, OS.MAC, OS.WINDOWS})
class GitToolTest {

    @TempDir
    Path tmp;

    private GitTool tool;

    @BeforeEach
    void setUp() throws Exception {
        // Skip cleanly if git isn't installed (e.g. minimal CI images).
        assumeTrue(gitInstalled(), "git binary not available — skipping");
        // Initialize a tiny repo with one commit so the read-only commands
        // have something to look at.
        runShell("git init -q -b main", tmp);
        runShell("git config user.email test@example.com", tmp);
        runShell("git config user.name Test", tmp);
        Files.writeString(tmp.resolve("hello.txt"), "Hello world\n");
        runShell("git add hello.txt", tmp);
        runShell("git commit -q -m initial", tmp);
        tool = new GitTool(tmp);
    }

    @Test
    @DisplayName("status reports a clean working tree after the initial commit")
    void status_clean() {
        Object out = tool.execute(Map.of("subcommand", "status"));
        assertTrue(out.toString().contains("nothing to commit") || out.toString().contains("working tree clean"),
                "expected clean status, got: " + out);
    }

    @Test
    @DisplayName("log --oneline returns the commit just made")
    void log_oneline_showsCommit() {
        Object out = tool.execute(Map.of("subcommand", "log", "args", "--oneline -n 5"));
        assertTrue(out.toString().contains("initial"), "expected 'initial' in log, got: " + out);
    }

    @Test
    @DisplayName("diff returns empty body on a clean tree")
    void diff_emptyOnClean() {
        Object out = tool.execute(Map.of("subcommand", "diff"));
        String s = out.toString();
        // body is everything after the "git diff:\n" header — should be empty/whitespace
        int idx = s.indexOf(":\n");
        assertTrue(idx >= 0);
        assertTrue(s.substring(idx + 2).isBlank(), "expected empty diff body, got: " + s);
    }

    @Test
    @DisplayName("diff shows added text after modifying a tracked file")
    void diff_showsChanges() throws IOException {
        Files.writeString(tmp.resolve("hello.txt"), "Hello world\nA new line\n");
        Object out = tool.execute(Map.of("subcommand", "diff"));
        assertTrue(out.toString().contains("A new line"),
                "expected 'A new line' in diff, got: " + out);
    }

    @Test
    @DisplayName("rejects subcommands not on the allow-list (e.g. push)")
    void rejectsDisallowedSubcommand() {
        Object out = tool.execute(Map.of("subcommand", "push", "args", "origin main"));
        assertTrue(out.toString().toLowerCase().contains("not allowed"),
                "expected refusal, got: " + out);
    }

    @Test
    @DisplayName("rejects shell metacharacters in args")
    void rejectsShellMeta() {
        for (String bad : List.of(";", "&&", "||", "|", "`", "$(", ">", "<")) {
            String args = "HEAD " + bad + " rm -rf";
            Object out = tool.execute(Map.of("subcommand", "log", "args", args));
            assertTrue(out.toString().contains("forbidden shell metacharacter"),
                    "expected refusal of '" + bad + "', got: " + out);
        }
    }

    @Test
    @DisplayName("returns an error when not inside a git repository")
    void notInsideGitRepo() throws IOException {
        Path notARepo = Files.createTempDirectory("not-a-repo");
        try {
            GitTool offRepo = new GitTool(notARepo);
            Object out = offRepo.execute(Map.of("subcommand", "status"));
            String s = out.toString();
            // Accept either git's own "fatal: not a git repository" or the
            // tool's pre-flight check phrasing — version + locale matter.
            boolean reportsAbsenceOfRepo =
                    s.contains("not inside a git repository")
                            || s.contains("not a git repository");
            assertTrue(reportsAbsenceOfRepo,
                    "expected a 'not (inside) a git repository' message, got: " + s);
        } finally {
            Files.deleteIfExists(notARepo);
        }
    }

    @Test
    @DisplayName("refuses 'path' values that escape the project root")
    void refusesPathEscapingRoot() {
        Object out = tool.execute(Map.of("subcommand", "status", "path", "../../etc"));
        assertTrue(out.toString().contains("refusing to run outside project root"),
                "expected refusal, got: " + out);
    }

    @Test
    @DisplayName("missing or blank subcommand surfaces a usage error")
    void missingSubcommand() {
        assertTrue(tool.execute(Map.of()).toString().contains("'subcommand' is required"));
        assertTrue(tool.execute(Map.of("subcommand", "  ")).toString().contains("'subcommand' is required"));
    }

    @Test
    @DisplayName("schema declares subcommand as required and lists the allow-list as enum")
    void schemaShape() {
        Map<String, Object> schema = tool.getParameterSchema();
        assertEquals("object", schema.get("type"));
        String[] required = (String[]) schema.get("required");
        assertEquals(1, required.length);
        assertEquals("subcommand", required[0]);

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> props = (Map<String, Map<String, Object>>) schema.get("properties");
        Object enumValue = props.get("subcommand").get("enum");
        assertTrue(enumValue instanceof List<?>);
        assertTrue(((List<?>) enumValue).contains("status"));
        assertTrue(((List<?>) enumValue).contains("diff"));
        assertTrue(((List<?>) enumValue).contains("log"));
    }

    // --- helpers ---------------------------------------------------------

    private static boolean gitInstalled() {
        try {
            return new ProcessBuilder("git", "--version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void runShell(String cmd, Path cwd) throws Exception {
        String[] parts = cmd.split("\\s+");
        Process p = new ProcessBuilder(parts).directory(cwd.toFile())
                .redirectErrorStream(true).start();
        int exit = p.waitFor();
        if (exit != 0) {
            String out = new String(p.getInputStream().readAllBytes());
            fail("setup command failed: '" + cmd + "' → exit=" + exit + "\n" + out);
        }
    }
}
