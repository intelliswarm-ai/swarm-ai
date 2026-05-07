package ai.intelliswarm.swarmai.agent.subagent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("EphemeralDirectoryWorkspace Tests")
class EphemeralDirectoryWorkspaceTest {

    @Test
    @DisplayName("constructor materialises a fresh directory; isOpen is true; root exists")
    void materialisesOnConstruct() throws IOException {
        EphemeralDirectoryWorkspace ws = new EphemeralDirectoryWorkspace();
        try {
            assertTrue(ws.isOpen());
            assertTrue(Files.isDirectory(ws.root()));
        } finally {
            ws.close();
        }
    }

    @Test
    @DisplayName("two workspaces use distinct root directories (no collision)")
    void distinctRoots() {
        EphemeralDirectoryWorkspace a = new EphemeralDirectoryWorkspace();
        EphemeralDirectoryWorkspace b = new EphemeralDirectoryWorkspace();
        try {
            assertNotEquals(a.root(), b.root());
        } finally {
            a.close();
            b.close();
        }
    }

    @Test
    @DisplayName("close() reaps the directory and any contents (recursive delete)")
    void closeReapsContents() throws IOException {
        EphemeralDirectoryWorkspace ws = new EphemeralDirectoryWorkspace();
        Path root = ws.root();
        Path file = root.resolve("nested/file.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "hello");
        assertTrue(Files.exists(file));

        ws.close();
        assertFalse(ws.isOpen());
        assertFalse(Files.exists(root), "root should be gone after close");
    }

    @Test
    @DisplayName("close() is idempotent — second close is a no-op")
    void closeIsIdempotent() {
        EphemeralDirectoryWorkspace ws = new EphemeralDirectoryWorkspace();
        ws.close();
        ws.close(); // must not throw
        assertFalse(ws.isOpen());
    }

    @Test
    @DisplayName("custom prefix is applied to the temp directory name")
    void customPrefix() {
        EphemeralDirectoryWorkspace ws = new EphemeralDirectoryWorkspace("test-prefix-");
        try {
            assertTrue(ws.root().getFileName().toString().startsWith("test-prefix-"));
        } finally {
            ws.close();
        }
    }
}
