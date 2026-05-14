package ai.intelliswarm.swarmai.observability.trajectory;

import ai.intelliswarm.swarmai.api.PublicApi;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Splits agent runs into success/failure JSONL streams — turning every run
 * into RL/SFT training data as a byproduct of normal operation.
 *
 * <p>Output layout:
 * <pre>{@code
 * baseDir/
 *   trajectory_samples.jsonl      ← successful runs
 *   failed_trajectories.jsonl     ← unsuccessful runs
 * }</pre>
 *
 * <p>One JSON object per line (ShareGPT-compatible — role + content turns +
 * metadata). Append-only; thread-safe under concurrent writers via
 * {@code synchronized} on a per-file lock. For high-throughput environments,
 * point the directory at a tmpfs and run a separate periodic upload job.
 */
@PublicApi(since = "1.0.24")
public final class TrajectoryWriter {

    private static final Logger log = LoggerFactory.getLogger(TrajectoryWriter.class);

    public static final String SUCCESS_FILE = "trajectory_samples.jsonl";
    public static final String FAILURE_FILE = "failed_trajectories.jsonl";

    private final Path baseDir;
    private final ObjectMapper mapper;
    private final Object successLock = new Object();
    private final Object failureLock = new Object();
    private final AtomicLong successCount = new AtomicLong();
    private final AtomicLong failureCount = new AtomicLong();

    public TrajectoryWriter(Path baseDir) {
        this.baseDir = Objects.requireNonNull(baseDir, "baseDir must not be null").toAbsolutePath().normalize();
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /** Append the entry to the success or failure file based on {@link TrajectoryEntry#success()}. */
    public void write(TrajectoryEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        String line;
        try {
            line = mapper.writeValueAsString(entry);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialise trajectory entry {}: {}", entry.runId(), e.getMessage());
            return;
        }
        Path target = entry.success() ? baseDir.resolve(SUCCESS_FILE) : baseDir.resolve(FAILURE_FILE);
        Object lock = entry.success() ? successLock : failureLock;
        synchronized (lock) {
            try {
                Files.createDirectories(baseDir);
                Files.writeString(target, line + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                if (entry.success()) {
                    successCount.incrementAndGet();
                } else {
                    failureCount.incrementAndGet();
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to append trajectory to " + target, e);
            }
        }
    }

    public long successCount() { return successCount.get(); }
    public long failureCount() { return failureCount.get(); }

    public Path successFile() { return baseDir.resolve(SUCCESS_FILE); }
    public Path failureFile() { return baseDir.resolve(FAILURE_FILE); }
    public Path baseDir() { return baseDir; }
}
