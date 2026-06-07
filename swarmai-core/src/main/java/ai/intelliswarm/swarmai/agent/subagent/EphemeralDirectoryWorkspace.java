package ai.intelliswarm.swarmai.agent.subagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Default {@link IsolatedWorkspace} backed by a temp directory. Created
 * eagerly in the constructor; reaped on {@link #close()} via a recursive
 * {@code Files.walkFileTree} delete.
 *
 * <p>The directory name uses the supplied prefix and a random suffix, so
 * concurrent workspaces don't collide. Failures during creation surface as
 * {@link IllegalStateException} from the constructor; failures during
 * reap are logged but never thrown — close must be safe even when the
 * subagent left the directory in an unusual state.
 */
public final class EphemeralDirectoryWorkspace implements IsolatedWorkspace {

    private static final Logger log = LoggerFactory.getLogger(EphemeralDirectoryWorkspace.class);

    private final Path root;
    private final AtomicBoolean open = new AtomicBoolean(true);

    public EphemeralDirectoryWorkspace() {
        this("subagent-");
    }

    public EphemeralDirectoryWorkspace(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        try {
            this.root = Files.createTempDirectory(prefix);
        } catch (IOException e) {
            throw new IllegalStateException("failed to create workspace temp dir", e);
        }
    }

    @Override
    public Path root() {
        return root;
    }

    @Override
    public boolean isOpen() {
        return open.get();
    }

    @Override
    public void close() {
        if (!open.compareAndSet(true, false)) return; // already closed
        try {
            if (!Files.exists(root)) return;
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    deleteQuietly(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    deleteQuietly(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("workspace cleanup failed at {}: {}", root, e.getMessage());
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("could not delete {} during workspace cleanup: {}", p, e.getMessage());
        }
    }
}
