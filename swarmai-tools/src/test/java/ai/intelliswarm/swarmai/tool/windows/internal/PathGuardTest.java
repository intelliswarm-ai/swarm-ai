package ai.intelliswarm.swarmai.tool.windows.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PathGuard Unit Tests")
class PathGuardTest {

    @Test
    @DisplayName("paths inside an allowed root are permitted; equal-to-root is permitted too")
    void insideRootIsAllowed(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectory(tmp.resolve("desktop"));
        Files.createFile(root.resolve("a.txt"));

        PathGuard guard = new PathGuard(List.of(root.toString()));

        assertTrue(guard.isAllowed(root));
        assertTrue(guard.isAllowed(root.resolve("a.txt")));
        assertTrue(guard.isAllowed(root.resolve("nested/dir/file.txt")));
    }

    @Test
    @DisplayName("a sibling of an allowed root is rejected")
    void siblingIsRejected(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectory(tmp.resolve("desktop"));
        Path sibling = Files.createDirectory(tmp.resolve("documents"));

        PathGuard guard = new PathGuard(List.of(root.toString()));

        assertFalse(guard.isAllowed(sibling.resolve("x.txt")));
    }

    @Test
    @DisplayName("'..' traversal that escapes the root is rejected")
    void traversalEscapeIsRejected(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectory(tmp.resolve("desktop"));

        PathGuard guard = new PathGuard(List.of(root.toString()));

        Path attempt = root.resolve("../../etc/passwd");
        assertFalse(guard.isAllowed(attempt));
        assertThrows(PathNotAllowedException.class, () -> guard.require(attempt));
    }

    @Test
    @DisplayName("'..' traversal that stays within the root is permitted and normalised")
    void traversalThatStaysInsideIsAllowed(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectory(tmp.resolve("desktop"));
        Files.createDirectories(root.resolve("a/b"));

        PathGuard guard = new PathGuard(List.of(root.toString()));

        Path attempt = root.resolve("a/b/../c.txt"); // resolves to root/a/c.txt
        Path resolved = guard.require(attempt);
        assertEquals(root.resolve("a").resolve("c.txt"), resolved);
    }

    @Test
    @DisplayName("require() rejects null and blank paths and rejects paths outside the allowlist")
    void requireRejects(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectory(tmp.resolve("desktop"));
        PathGuard guard = new PathGuard(List.of(root.toString()));

        assertThrows(PathNotAllowedException.class, () -> guard.require((Path) null));
        assertThrows(PathNotAllowedException.class, () -> guard.require(""));
        assertThrows(PathNotAllowedException.class, () -> guard.require((String) null));
        assertThrows(PathNotAllowedException.class,
                () -> guard.require(tmp.resolve("not-allowed").toString()));
    }

    @Test
    @DisplayName("an empty allowlist rejects every path")
    void emptyAllowlistRejectsEverything(@TempDir Path tmp) {
        PathGuard guard = new PathGuard(List.of());

        assertFalse(guard.isAllowed(tmp));
        assertFalse(guard.isAllowed("/anywhere"));
        assertThrows(PathNotAllowedException.class, () -> guard.require(tmp));
    }

    @Test
    @DisplayName("string roots with ${user.home} placeholders are expanded against system properties")
    void userHomePlaceholderIsExpanded(@TempDir Path tmp) {
        String original = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tmp.toString());
            PathGuard guard = new PathGuard(List.of("${user.home}/Desktop"));

            assertTrue(guard.allowedRoots().get(0).startsWith(tmp));
            assertTrue(guard.isAllowed(tmp.resolve("Desktop/file.txt")));
        } finally {
            System.setProperty("user.home", original);
        }
    }

    @Test
    @DisplayName("expand() leaves unknown placeholders in place rather than swallowing them")
    void unknownPlaceholderIsLeftInPlace() {
        String result = PathGuard.expand("/x/${swarmai.does.not.exist}/y");
        assertTrue(result.contains("${swarmai.does.not.exist}"), result);
    }

    @Test
    @DisplayName("expand() handles multiple placeholders in one string")
    void multiplePlaceholdersAreExpanded() {
        String original = System.getProperty("user.home");
        try {
            System.setProperty("user.home", "/u");
            System.setProperty("swarmai.test.dir", "/t");
            String result = PathGuard.expand("${user.home}/x/${swarmai.test.dir}/y");
            assertEquals("/u/x//t/y", result);
        } finally {
            System.setProperty("user.home", original);
            System.clearProperty("swarmai.test.dir");
        }
    }

    @Test
    @DisplayName("blank or null candidate strings are treated as not-allowed")
    void blankCandidatesAreRejected(@TempDir Path tmp) {
        PathGuard guard = new PathGuard(List.of(tmp.toString()));
        assertFalse(guard.isAllowed((String) null));
        assertFalse(guard.isAllowed(""));
        assertFalse(guard.isAllowed("   "));
    }
}
