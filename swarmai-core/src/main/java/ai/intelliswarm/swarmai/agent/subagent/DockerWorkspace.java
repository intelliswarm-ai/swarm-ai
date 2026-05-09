package ai.intelliswarm.swarmai.agent.subagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * {@link IsolatedWorkspace} backed by a long-running Docker container with
 * one or more bind mounts. Pairs naturally with {@link GitWorktreeWorkspace}:
 * the worktree gives a coding-agent CLI the codebase as context, and the
 * Docker container gives it strong isolation, pinned tool versions, and
 * configurable resource caps.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@link Builder#build()} — issues {@code docker run -d ...} with the
 *       configured bind mounts, environment, and resource limits. The
 *       container's command is {@code sleep infinity} so it stays alive
 *       until {@link #close()}.</li>
 *   <li>{@link #exec(List, Duration)} — runs a command inside the container
 *       via {@code docker exec}, capturing stdout/stderr/exit code/elapsed.</li>
 *   <li>{@link #close()} — {@code docker stop} (with grace period) followed
 *       by {@code docker rm -f}. Idempotent.</li>
 * </ol>
 *
 * <h2>{@code root()} semantics</h2>
 * Returns the <b>host-side path of the primary bind mount</b>. The container
 * can't see the host's filesystem; the host can read changes the container
 * made through this path. Composes with {@link GitWorktreeWorkspace#diff()}:
 * give the worktree's root as the primary bind mount and the diff afterwards
 * lists every file the agent changed.
 *
 * <h2>Skip behavior</h2>
 * {@link #isDockerAvailable()} returns false on hosts where the {@code docker}
 * binary isn't installed or the daemon isn't reachable. Tests gate themselves
 * on this so CI without Docker still passes.
 */
public final class DockerWorkspace implements IsolatedWorkspace {

    private static final Logger logger = LoggerFactory.getLogger(DockerWorkspace.class);

    /** Default per-docker-call timeout. */
    public static final Duration DEFAULT_DOCKER_TIMEOUT = Duration.ofSeconds(60);

    private final String image;
    private final String containerName;
    private final List<BindMount> mounts;
    private final String workdir;
    private final Map<String, String> env;
    private final ResourceLimits limits;
    private final Duration startTimeout;
    private final Duration stopTimeout;
    private final Duration dockerTimeout;
    private final Path hostRootPath;

    private volatile boolean open;

    private DockerWorkspace(Builder b) throws IOException {
        this.image = b.image;
        this.containerName = (b.containerName == null)
                ? "swarmai-ws-" + UUID.randomUUID().toString().substring(0, 8)
                : b.containerName;
        this.mounts = List.copyOf(b.mounts);
        this.workdir = b.workdir;
        this.env = Map.copyOf(b.env);
        this.limits = b.limits;
        this.startTimeout = b.startTimeout;
        this.stopTimeout = b.stopTimeout;
        this.dockerTimeout = b.dockerTimeout;
        this.hostRootPath = mounts.isEmpty()
                ? null
                : mounts.get(0).hostPath();

        if (!isDockerAvailable()) {
            throw new IOException("Docker is not available on this host");
        }

        startContainer();
        this.open = true;
    }

    @Override
    public Path root() {
        if (hostRootPath == null) {
            throw new IllegalStateException(
                    "DockerWorkspace has no bind mounts; root() requires at least one");
        }
        return hostRootPath;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public synchronized void close() {
        if (!open) return;
        open = false;
        // Best-effort: stop, then force-remove. Don't throw — let resources leak
        // a little rather than break unrelated cleanup paths.
        try {
            ProcessResult stop = runDocker(dockerTimeout, "stop",
                    "--time=" + Math.max(1, (int) stopTimeout.toSeconds()),
                    containerName);
            if (!stop.ok()) {
                logger.debug("docker stop {} returned {}: {}", containerName,
                        stop.exitCode, stop.stderr.trim());
            }
        } catch (IOException e) {
            logger.debug("docker stop threw: {}", e.getMessage());
        }
        try {
            ProcessResult rm = runDocker(dockerTimeout, "rm", "-f", containerName);
            if (!rm.ok()) {
                logger.debug("docker rm -f {} returned {}: {}", containerName,
                        rm.exitCode, rm.stderr.trim());
            }
        } catch (IOException e) {
            logger.debug("docker rm -f threw: {}", e.getMessage());
        }
    }

    /**
     * Run a command inside the container. Captures stdout, stderr, exit code,
     * and elapsed time. Throws {@link IOException} on docker invocation
     * failure or timeout.
     *
     * @param command absolute command + args, e.g. {@code ["sh", "-c", "echo hi"]}
     * @param timeout per-exec deadline; the docker exec process is killed if it elapses
     */
    public ExecResult exec(List<String> command, Duration timeout) throws IOException {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(timeout, "timeout");
        if (!open) throw new IllegalStateException("workspace is closed");
        if (command.isEmpty()) throw new IllegalArgumentException("command must not be empty");

        List<String> docker = new ArrayList<>();
        docker.add("docker");
        docker.add("exec");
        if (workdir != null) {
            docker.add("-w");
            docker.add(workdir);
        }
        for (Map.Entry<String, String> e : env.entrySet()) {
            docker.add("-e");
            docker.add(e.getKey() + "=" + e.getValue());
        }
        docker.add(containerName);
        docker.addAll(command);

        Instant start = Instant.now();
        ProcessResult r = runProcess(docker, timeout);
        Duration elapsed = Duration.between(start, Instant.now());
        return new ExecResult(r.exitCode, r.stdout, r.stderr, elapsed);
    }

    /** Convenience: run a shell command via {@code sh -c}. */
    public ExecResult exec(String shellCommand, Duration timeout) throws IOException {
        return exec(List.of("sh", "-c", shellCommand), timeout);
    }

    public String containerName() { return containerName; }
    public String image() { return image; }
    public List<BindMount> mounts() { return mounts; }
    public ResourceLimits limits() { return limits; }

    /** True if the {@code docker} binary is on PATH and the daemon responds to {@code docker version}. */
    public static boolean isDockerAvailable() {
        try {
            ProcessResult r = runProcess(List.of("docker", "version", "--format", "{{.Server.Version}}"),
                    Duration.ofSeconds(5));
            return r.ok();
        } catch (IOException e) {
            return false;
        }
    }

    public static Builder builder(String image) {
        return new Builder(image);
    }

    // =================================================================
    // Internals
    // =================================================================

    private void startContainer() throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("run");
        cmd.add("-d");
        cmd.add("--name");
        cmd.add(containerName);

        for (BindMount m : mounts) {
            String spec = m.hostPath().toAbsolutePath() + ":" + m.containerPath();
            if (m.readOnly()) spec += ":ro";
            cmd.add("-v");
            cmd.add(spec);
        }
        if (workdir != null) {
            cmd.add("-w");
            cmd.add(workdir);
        }
        for (Map.Entry<String, String> e : env.entrySet()) {
            cmd.add("-e");
            cmd.add(e.getKey() + "=" + e.getValue());
        }
        if (limits.memory() != null) {
            cmd.add("--memory");
            cmd.add(limits.memory());
        }
        if (limits.cpus() != null) {
            cmd.add("--cpus");
            cmd.add(limits.cpus());
        }
        if (limits.pidsLimit() != null) {
            cmd.add("--pids-limit");
            cmd.add(String.valueOf(limits.pidsLimit()));
        }
        if (limits.networkMode() != null) {
            cmd.add("--network");
            cmd.add(limits.networkMode());
        }
        cmd.add(image);
        // Keep the container alive until close() — tested across alpine/debian/ubuntu.
        cmd.add("sh");
        cmd.add("-c");
        cmd.add("while true; do sleep 86400; done");

        ProcessResult run = runProcess(cmd, startTimeout);
        if (!run.ok()) {
            throw new IOException("docker run failed (exit " + run.exitCode
                    + "): " + run.stderr.trim());
        }

        // Verify the container is actually running before letting exec() be called.
        ProcessResult inspect = runDocker(dockerTimeout, "inspect",
                "--format", "{{.State.Running}}", containerName);
        if (!inspect.ok() || !inspect.stdout.trim().equals("true")) {
            // Try to grab logs to surface why
            String logs = "";
            try {
                ProcessResult l = runDocker(dockerTimeout, "logs", containerName);
                logs = l.stdout + l.stderr;
            } catch (IOException ignored) {}
            throw new IOException("container " + containerName
                    + " is not running after docker run; logs:\n" + logs);
        }
    }

    private static ProcessResult runDocker(Duration timeout, String... args) throws IOException {
        List<String> cmd = new ArrayList<>(args.length + 1);
        cmd.add("docker");
        cmd.addAll(Arrays.asList(args));
        return runProcess(cmd, timeout);
    }

    private static ProcessResult runProcess(List<String> command, Duration timeout) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            throw new IOException("failed to start: " + String.join(" ", command), e);
        }

        // Read both streams concurrently to avoid pipe-buffer deadlock.
        CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(() -> {
            try (var s = proc.getInputStream()) {
                return new String(s.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });
        CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(() -> {
            try (var s = proc.getErrorStream()) {
                return new String(s.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });

        try {
            if (!proc.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly();
                throw new IOException("command timed out after " + timeout
                        + ": " + String.join(" ", command));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
            throw new IOException("interrupted while running: "
                    + String.join(" ", command), e);
        }

        return new ProcessResult(proc.exitValue(), stdoutFuture.join(), stderrFuture.join());
    }

    /** Internal process-output holder. */
    private record ProcessResult(int exitCode, String stdout, String stderr) {
        boolean ok() { return exitCode == 0; }
    }

    // =================================================================
    // Public records — input and output shapes for the workspace
    // =================================================================

    /**
     * One host → container path mapping. {@code hostPath} must be absolute
     * (Docker rejects relative paths). {@code readOnly=true} adds {@code :ro}
     * to the mount spec.
     */
    public record BindMount(Path hostPath, String containerPath, boolean readOnly) {
        public BindMount {
            Objects.requireNonNull(hostPath, "hostPath");
            Objects.requireNonNull(containerPath, "containerPath");
            if (!hostPath.isAbsolute()) {
                throw new IllegalArgumentException("hostPath must be absolute: " + hostPath);
            }
            if (containerPath.isBlank()) {
                throw new IllegalArgumentException("containerPath must not be blank");
            }
        }

        public static BindMount readWrite(Path host, String container) {
            return new BindMount(host, container, false);
        }

        public static BindMount readOnly(Path host, String container) {
            return new BindMount(host, container, true);
        }
    }

    /**
     * Resource caps the container will run under. Any null field disables
     * that particular cap. {@code networkMode="none"} is the strongest
     * isolation; {@code "bridge"} (Docker default) and {@code "host"} are
     * the other common values.
     */
    public record ResourceLimits(
            String memory,        // e.g. "2g", "512m"
            String cpus,          // e.g. "1.5", "2"
            Integer pidsLimit,    // e.g. 100
            String networkMode    // e.g. "none", "bridge", "host"
    ) {
        public static ResourceLimits unrestricted() {
            return new ResourceLimits(null, null, null, null);
        }
    }

    /** Result of an {@link #exec(List, Duration)} call. */
    public record ExecResult(int exitCode, String stdout, String stderr, Duration elapsed) {
        public boolean ok() { return exitCode == 0; }
    }

    // =================================================================
    // Builder
    // =================================================================

    public static final class Builder {
        private final String image;
        private String containerName;
        private final List<BindMount> mounts = new ArrayList<>();
        private String workdir;
        private final Map<String, String> env = new LinkedHashMap<>();
        private String memory;
        private String cpus;
        private Integer pidsLimit;
        private String networkMode;
        private Duration startTimeout = Duration.ofSeconds(15);
        private Duration stopTimeout = Duration.ofSeconds(2);
        private Duration dockerTimeout = DEFAULT_DOCKER_TIMEOUT;

        Builder(String image) {
            this.image = Objects.requireNonNull(image, "image");
            if (image.isBlank()) {
                throw new IllegalArgumentException("image must not be blank");
            }
        }

        public Builder containerName(String name) {
            this.containerName = name;
            return this;
        }

        public Builder bindMount(Path host, String container) {
            return bindMount(host, container, false);
        }

        public Builder bindMount(Path host, String container, boolean readOnly) {
            this.mounts.add(new BindMount(host, container, readOnly));
            return this;
        }

        public Builder workdir(String workdir) {
            this.workdir = workdir;
            return this;
        }

        public Builder env(String key, String value) {
            this.env.put(Objects.requireNonNull(key, "key"),
                    Objects.requireNonNull(value, "value"));
            return this;
        }

        public Builder env(Map<String, String> map) {
            map.forEach(this::env);
            return this;
        }

        public Builder memoryLimit(String memory) {
            this.memory = memory;
            return this;
        }

        public Builder cpus(String cpus) {
            this.cpus = cpus;
            return this;
        }

        public Builder pidsLimit(int pids) {
            this.pidsLimit = pids;
            return this;
        }

        public Builder networkMode(String mode) {
            this.networkMode = mode;
            return this;
        }

        public Builder startTimeout(Duration timeout) {
            this.startTimeout = Objects.requireNonNull(timeout, "startTimeout");
            return this;
        }

        public Builder stopTimeout(Duration timeout) {
            this.stopTimeout = Objects.requireNonNull(timeout, "stopTimeout");
            return this;
        }

        public Builder dockerTimeout(Duration timeout) {
            this.dockerTimeout = Objects.requireNonNull(timeout, "dockerTimeout");
            return this;
        }

        // Internal field accessors for the constructor — package-private getters via direct field access.
        ResourceLimits limits() {
            return new ResourceLimits(memory, cpus, pidsLimit, networkMode);
        }

        public DockerWorkspace build() throws IOException {
            this.limits = limits();
            return new DockerWorkspace(this);
        }

        // package-private state for the constructor to read
        ResourceLimits limits;
    }
}
