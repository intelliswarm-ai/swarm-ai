package ai.intelliswarm.swarmai.state.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/**
 * File-backed {@link BanditStateStore} using Jackson JSON. Saves are atomic
 * via the standard temp-file + atomic-rename pattern: any concurrent reader
 * sees either the previous state or the new state, never a half-written
 * file.
 *
 * <p>The file lives at {@link #path}. Parent directories are created on
 * first save. A missing file on load returns {@link BanditState#empty()} —
 * the natural "first-run" behaviour.
 *
 * <p>Pretty-prints the JSON by default; flip {@link #FileBanditStateStore(Path, boolean)}
 * to compact mode for production storage.
 */
public final class FileBanditStateStore implements BanditStateStore {

    private static final Logger log = LoggerFactory.getLogger(FileBanditStateStore.class);

    private final Path path;
    private final ObjectMapper mapper;
    private final Object writeLock = new Object();

    public FileBanditStateStore(Path path) {
        this(path, /*prettyPrint*/ true);
    }

    public FileBanditStateStore(Path path, boolean prettyPrint) {
        this.path = Objects.requireNonNull(path, "path");
        this.mapper = new ObjectMapper();
        if (prettyPrint) {
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
        }
    }

    public Path path() {
        return path;
    }

    @Override
    public BanditState load() throws IOException {
        if (!Files.exists(path)) {
            return BanditState.empty();
        }
        try {
            return mapper.readValue(path.toFile(), BanditState.class);
        } catch (IOException e) {
            log.warn("failed to load bandit state from {}: {}", path, e.getMessage());
            throw e;
        }
    }

    @Override
    public void save(BanditState state) throws IOException {
        Objects.requireNonNull(state, "state");
        synchronized (writeLock) {
            ensureParent();
            Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");
            try {
                mapper.writeValue(tmp.toFile(), state);
                Files.move(tmp, path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                // Best-effort cleanup of the temp file
                Files.deleteIfExists(tmp);
                throw e;
            }
        }
    }

    private void ensureParent() throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null && !Files.isDirectory(parent)) {
            Files.createDirectories(parent);
        }
    }
}
