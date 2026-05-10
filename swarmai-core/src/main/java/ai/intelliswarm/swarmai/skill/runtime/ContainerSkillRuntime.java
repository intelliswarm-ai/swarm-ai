package ai.intelliswarm.swarmai.skill.runtime;

import ai.intelliswarm.swarmai.skill.ContainerSkillSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs {@link ai.intelliswarm.swarmai.skill.SkillType#CONTAINER} skills via
 * the local Docker daemon.
 *
 * <h2>Responsibilities</h2>
 * <ol>
 *   <li><b>Image acquisition</b> — pull a pre-built image, or build one from
 *       an inline {@code Dockerfile} into a deterministically-named tag.</li>
 *   <li><b>Container execution</b> — start a one-shot {@code docker run --rm}
 *       with parameter→env mapping, the configured network mode, and a
 *       hard timeout enforced by the JVM (kills the container on overrun).</li>
 *   <li><b>Cleanup</b> — track every image we built and every container we
 *       started; expose explicit {@link #pruneImagesBuiltByMe()} and
 *       {@link #stopContainersStartedByMe()} so callers can reclaim space
 *       without disturbing other Docker resources on the host.</li>
 * </ol>
 *
 * <h2>What this runtime does NOT do (deliberate non-goals for v1)</h2>
 * <ul>
 *   <li>Cache-aware builds. Every {@link #buildImage} produces a fresh tag.
 *       Re-running an unchanged Dockerfile re-builds; Docker's layer cache
 *       still kicks in inside the daemon, but we don't dedupe at this layer.</li>
 *   <li>Long-running containers. Every skill invocation is one-shot. Faster
 *       reuse via container caching is a Phase-2 optimisation.</li>
 *   <li>Privileged execution. {@code --privileged} is permanently rejected;
 *       no opt-in flag exists. Out of scope for self-improving workflows.</li>
 * </ul>
 *
 * <h2>Trust model</h2>
 * Container skills carry meaningful blast radius — Codex-generated Dockerfiles
 * pull base images from public registries, then run inside your Docker daemon.
 * The trust controls available here are: image-tag pinning (caller's job),
 * default {@code --network none} unless the spec explicitly opts in, mandatory
 * timeout, optional memory cap, and a complete audit log of every image/container
 * the runtime touched. Ban-list of {@code --privileged} is enforced.
 */
public final class ContainerSkillRuntime implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ContainerSkillRuntime.class);

    /** Prefix every image we build so {@code docker images | grep swarmai-skill-} surfaces them all. */
    public static final String BUILT_IMAGE_PREFIX = "swarmai-skill-";

    /** Same convention for container names so cleanup is unambiguous. */
    public static final String CONTAINER_PREFIX = "swarmai-skill-";

    /** Cap on docker CLI subcommands themselves (build/run/stop). Distinct from the skill timeout. */
    private final Duration dockerCliTimeout;

    /** Image refs we built (or pulled) — caller owns lifecycle via the prune API. */
    private final Set<String> imagesWeBuilt = ConcurrentHashMap.newKeySet();

    /** Container names we started — used by stopContainersStartedByMe(). */
    private final Set<String> containersWeStarted = ConcurrentHashMap.newKeySet();

    /**
     * Policy gate consulted before every {@link #execute} call. Defaults to
     * {@link ContainerPolicy#permissive()} so existing call sites keep working;
     * production deployments should swap in {@link ContainerPolicy#strict()}
     * or a hand-tuned policy via {@link #setPolicy}.
     */
    private volatile ContainerPolicy policy = ContainerPolicy.permissive();

    /** Append-only audit log of every execute() call (allowed or denied). */
    private final java.util.List<ContainerAuditEntry> auditLog =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /**
     * Optional disk persistence for the audit log. When non-null, every
     * entry appended to {@link #auditLog} is also written as a JSON line
     * to the configured file. Off by default — opt in via
     * {@link #setAuditPersistence}.
     */
    private volatile ContainerAuditPersistence auditPersistence;

    /**
     * Human-in-the-loop approval gate consulted before every <em>new</em>
     * image build or pull. Defaults to {@link ApprovalGates.AutoApprovalGate}
     * (always APPROVE) so existing tests and offline pipelines keep working.
     * Production deployments and self-improving CLI workflows should swap
     * in {@link ApprovalGates.ConsoleApprovalGate} or
     * {@link ApprovalGates.RememberingApprovalGate}.
     *
     * <p>The gate fires only when the image isn't already present locally —
     * already-cached images are reused without re-prompting.
     */
    private volatile ImageApprovalGate approvalGate = new ApprovalGates.AutoApprovalGate();

    /**
     * Source label attached to subsequent build/pull approval requests,
     * giving the gate context like {@code "codex-generated:gap-X"} or
     * {@code "kali-toolbox"}. Reset between distinct workflows.
     */
    private volatile String currentSource = "unspecified";

    public ContainerSkillRuntime() {
        this(Duration.ofMinutes(5));
    }

    public ContainerSkillRuntime(Duration dockerCliTimeout) {
        this.dockerCliTimeout = dockerCliTimeout;
    }

    /** Replace the active policy. Effective on the next {@link #execute} call. */
    public ContainerSkillRuntime setPolicy(ContainerPolicy newPolicy) {
        this.policy = newPolicy != null ? newPolicy : ContainerPolicy.permissive();
        return this;
    }

    /** The currently-active policy (for tests + introspection). */
    public ContainerPolicy policy() {
        return policy;
    }

    /** Snapshot of audit entries collected so far. Newest last. */
    public java.util.List<ContainerAuditEntry> auditLog() {
        synchronized (auditLog) {
            return java.util.List.copyOf(auditLog);
        }
    }

    /** Clear the audit log — useful in tests. Does not affect Docker state. */
    public void clearAuditLog() {
        auditLog.clear();
    }

    /**
     * Enable on-disk persistence for the audit log. Pass {@code null} to
     * disable. Once enabled, every audit entry is appended as a JSON line
     * to the configured file. The in-memory log is unaffected — both are
     * available.
     */
    public ContainerSkillRuntime setAuditPersistence(ContainerAuditPersistence persistence) {
        this.auditPersistence = persistence;
        return this;
    }

    /** The configured persistence, or null if persistence is disabled. */
    public ContainerAuditPersistence auditPersistence() {
        return auditPersistence;
    }

    /** Replace the active approval gate. Effective on the next build/pull. */
    public ContainerSkillRuntime setApprovalGate(ImageApprovalGate gate) {
        this.approvalGate = gate != null ? gate : new ApprovalGates.AutoApprovalGate();
        return this;
    }

    /** The currently-active approval gate (for tests + introspection). */
    public ImageApprovalGate approvalGate() {
        return approvalGate;
    }

    /**
     * Tag the source string included in subsequent approval requests. Use
     * this from a workflow to give the user/gate context about WHY a new
     * image is being requested (e.g. {@code "codex:gap-network-scan"}).
     */
    public ContainerSkillRuntime setSource(String source) {
        this.currentSource = source == null || source.isBlank() ? "unspecified" : source;
        return this;
    }

    /**
     * Run a container skill and return the captured stdout.
     *
     * <p>End-to-end pipeline:
     * <ol>
     *   <li>Resolve image — already-present tag or build-from-Dockerfile.</li>
     *   <li>Build {@code docker run} command with env, network, memory, name.</li>
     *   <li>Wait up to {@code spec.timeout()}, killing the container on overrun.</li>
     *   <li>Capture stdout + stderr; bubble stderr into the result on non-zero exit.</li>
     * </ol>
     *
     * @param spec   container configuration
     * @param params skill invocation parameters; values are stringified for env
     * @return stdout when exit code is 0; an {@code Error: ...} string otherwise
     */
    public ExecResult execute(ContainerSkillSpec spec, Map<String, Object> params) {
        if (spec == null) {
            throw new IllegalArgumentException("ContainerSkillSpec is required");
        }

        // Policy gate — admit, deny, or audit before touching Docker.
        ContainerPolicy.PolicyResult verdict = policy.validate(spec);
        if (!verdict.allowed()) {
            ContainerAuditEntry denial = ContainerAuditEntry.ofPolicyDenial(spec, verdict);
            auditLog.add(denial);
            persistAudit(denial);
            logger.warn("Container skill rejected by policy: {}", verdict.summary());
            return new ExecResult(
                    -1,
                    "",
                    "policy denied: " + String.join("; ", verdict.violations()),
                    Duration.ZERO,
                    "(rejected)");
        }

        ensureDockerAvailable();

        String containerName = CONTAINER_PREFIX + UUID.randomUUID().toString().substring(0, 8);

        List<String> cmd = new ArrayList<>(List.of(
                "docker", "run",
                "--rm",
                "--name", containerName,
                "--network", spec.network()
        ));
        if (spec.memory() != null && !spec.memory().isBlank()) {
            cmd.add("--memory");
            cmd.add(spec.memory());
            // pids cap is cheap defence-in-depth; protects against fork bombs.
            cmd.add("--pids-limit");
            cmd.add("1024");
        }
        // Override the image's default ENTRYPOINT when the spec provides one.
        // Critical for images whose ENTRYPOINT is the tool itself (e.g.
        // instrumentisto/nmap, jq images): without this the command list gets
        // appended as args to that tool, not interpreted as a shell line.
        if (spec.hasEntrypoint()) {
            cmd.add("--entrypoint");
            cmd.add(spec.entrypoint());
        }
        // Apply param→env mapping. Values null-coerced to empty so the env
        // var is always set (skill scripts can default).
        for (Map.Entry<String, String> entry : spec.envFromParams().entrySet()) {
            String paramName = entry.getKey();
            String envName = entry.getValue();
            Object value = params != null ? params.get(paramName) : null;
            cmd.add("-e");
            cmd.add(envName + "=" + (value == null ? "" : String.valueOf(value)));
        }
        // Image + optional command override.
        cmd.add(spec.image());
        cmd.addAll(spec.command());

        containersWeStarted.add(containerName);
        Instant start = Instant.now();
        ExecResult result;
        try {
            ProcResult procResult = runProcess(cmd, spec.timeout(), null);
            result = new ExecResult(
                    procResult.exitCode(),
                    procResult.stdout(),
                    procResult.stderr(),
                    Duration.between(start, Instant.now()),
                    containerName);
        } catch (TimeoutException e) {
            forceStop(containerName);
            result = new ExecResult(
                    -1,
                    "",
                    "container exceeded timeout " + spec.timeout(),
                    Duration.between(start, Instant.now()),
                    containerName);
        } catch (Exception e) {
            result = new ExecResult(
                    -1,
                    "",
                    "execution failed: " + e.getMessage(),
                    Duration.between(start, Instant.now()),
                    containerName);
        } finally {
            // --rm above means the container removes itself on exit; we still
            // drop it from our tracking set so stopContainersStartedByMe()
            // doesn't try to stop something already gone.
            containersWeStarted.remove(containerName);
        }

        // Append audit entry for every run that policy admitted, regardless of
        // outcome. The policyOutcome field reads "OK" for these (denials are
        // logged earlier with the violation list).
        ContainerAuditEntry entry = ContainerAuditEntry.ofExecution(
                containerName, spec, spec.image().isEmpty() ? "(dockerfile)" : spec.image(),
                result.exitCode(), result.stderr(), result.elapsed());
        auditLog.add(entry);
        persistAudit(entry);
        return result;
    }

    /** Best-effort write to the configured audit file (if any). Never throws. */
    private void persistAudit(ContainerAuditEntry entry) {
        ContainerAuditPersistence p = this.auditPersistence;
        if (p != null) {
            p.append(entry);
        }
    }

    /**
     * Build an image from an inline Dockerfile string and return the tag.
     *
     * <p>The tag is deterministic from the Dockerfile content — same content
     * → same tag → Docker's layer cache hits naturally.
     *
     * @param dockerfile literal Dockerfile content
     * @return the built image tag (always prefixed with {@link #BUILT_IMAGE_PREFIX})
     */
    public String buildImage(String dockerfile) throws IOException, InterruptedException, TimeoutException {
        return buildImage(dockerfile, java.util.Map.of());
    }

    /**
     * Build an image from an inline Dockerfile + a map of labels that are
     * injected as {@code LABEL key="value"} lines right after the {@code FROM}
     * instruction. Labels are visible via {@code docker inspect} and
     * queryable via {@code docker images --filter label=key=value}, so the
     * resulting image is self-describing — anyone (or the framework itself)
     * can discover what skill an image implements without the SKILL.md
     * catalog being available.
     *
     * <p>Conventional namespace: {@code ai.swarmai.skill.*}. Standard keys:
     * <ul>
     *   <li>{@code ai.swarmai.skill.name} — the skill's slug</li>
     *   <li>{@code ai.swarmai.skill.description} — one-line summary</li>
     *   <li>{@code ai.swarmai.skill.category} — security, data-io, etc.</li>
     *   <li>{@code ai.swarmai.skill.tags} — comma-separated tag list</li>
     *   <li>{@code ai.swarmai.skill.framework-version} — runtime version</li>
     * </ul>
     */
    public String buildImage(String dockerfile, java.util.Map<String, String> labels)
            throws IOException, InterruptedException, TimeoutException {
        if (dockerfile == null || dockerfile.isBlank()) {
            throw new IllegalArgumentException("dockerfile content is required");
        }
        ensureDockerAvailable();

        // Inject LABEL lines after the first FROM directive so the image is
        // self-describing. Labels become part of the Dockerfile content and
        // therefore part of the SHA1, which is intentional: a different
        // skill (even with the same RUN layers) gets a distinct tag, but
        // the underlying RUN layers still cache via Docker's layer cache.
        String augmented = (labels == null || labels.isEmpty())
                ? dockerfile
                : injectLabels(dockerfile, labels);
        String hash = sha1Short(augmented);
        String tag = BUILT_IMAGE_PREFIX + hash;

        // Skip if already exists — `docker image inspect` is the cheap probe.
        ProcResult inspect = runProcess(List.of("docker", "image", "inspect", tag),
                Duration.ofSeconds(10), null);
        if (inspect.exitCode() == 0) {
            imagesWeBuilt.add(tag);
            logger.debug("image {} already present; skipping build", tag);
            return tag;
        }

        // Approval gate — fires only for genuinely new images. Already-present
        // tags above don't re-prompt because the user has implicitly approved
        // by virtue of the image already being on the host.
        ImageApprovalGate.Request approvalReq = new ImageApprovalGate.Request(
                tag, augmented, currentSource, extractFromImage(augmented));
        ImageApprovalGate.Decision decision = approvalGate.approve(approvalReq);
        if (decision == ImageApprovalGate.Decision.DENY) {
            logger.warn("Image build denied by approval gate: tag={}, source={}",
                    tag, currentSource);
            throw new ImageApprovalGate.ImageApprovalDeniedException(
                    "user/gate denied image build for tag " + tag);
        }

        Path ctx = Files.createTempDirectory("swarmai-skill-build-");
        try {
            Files.writeString(ctx.resolve("Dockerfile"), augmented, StandardCharsets.UTF_8);
            ProcResult result = runProcess(
                    List.of("docker", "build", "-t", tag, "."),
                    dockerCliTimeout,
                    ctx);
            if (result.exitCode() != 0) {
                throw new IOException("docker build exited " + result.exitCode()
                        + ": " + abbreviate(result.stderr(), 500));
            }
            imagesWeBuilt.add(tag);
            logger.info("built image {} ({} bytes Dockerfile, {} labels)",
                    tag, augmented.length(), labels == null ? 0 : labels.size());
            return tag;
        } finally {
            deleteRecursively(ctx);
        }
    }

    /**
     * Insert {@code LABEL key="value"} lines after the first {@code FROM}
     * instruction. Each label value is escaped for double-quote safety.
     * Multi-stage builds: only the first FROM is annotated; sufficient for
     * the runtime's current single-stage convention.
     */
    static String injectLabels(String dockerfile, java.util.Map<String, String> labels) {
        StringBuilder labelBlock = new StringBuilder();
        for (var entry : labels.entrySet()) {
            String value = entry.getValue() == null ? "" : entry.getValue();
            // Escape backslashes first, then double quotes.
            value = value.replace("\\", "\\\\").replace("\"", "\\\"");
            // Cap individual labels at 64 KB. Docker's documented per-image
            // metadata limit is ~256 KB across all labels, so 64 KB per label
            // is safe headroom; this also gives us room for a real
            // capability manifest (base64-encoded JSON, often 1-4 KB) without
            // truncation, while still bounding pathological growth.
            if (value.length() > 65536) value = value.substring(0, 65533) + "...";
            labelBlock.append("LABEL ").append(entry.getKey()).append("=\"")
                     .append(value).append("\"\n");
        }
        String[] lines = dockerfile.split("\n", -1);
        StringBuilder out = new StringBuilder();
        boolean injected = false;
        for (String line : lines) {
            out.append(line).append("\n");
            if (!injected && line.trim().toUpperCase().startsWith("FROM ")) {
                out.append(labelBlock);
                injected = true;
            }
        }
        // If the Dockerfile somehow lacks a FROM (parser tolerance), prepend.
        if (!injected) {
            return labelBlock + dockerfile;
        }
        return out.toString();
    }

    /**
     * Extract the {@code FROM} image from a Dockerfile so the approval
     * gate can show the user (and policy can match against) the actual
     * base image rather than just the resolved build tag. Returns the
     * literal text after {@code FROM } on the first matching line, or
     * an empty string if no FROM is present.
     */
    static String extractFromImage(String dockerfile) {
        if (dockerfile == null || dockerfile.isBlank()) return "";
        for (String line : dockerfile.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.toUpperCase().startsWith("FROM ")) {
                String rest = trimmed.substring(5).trim();
                // Strip trailing AS-alias (multi-stage builds).
                int asIdx = rest.toUpperCase().indexOf(" AS ");
                if (asIdx >= 0) rest = rest.substring(0, asIdx).trim();
                return rest;
            }
        }
        return "";
    }

    /**
     * Pull a pre-built image from the local registry mirror or remote. No-op
     * if already present locally.
     */
    public void pullImage(String image) throws IOException, InterruptedException, TimeoutException {
        if (image == null || image.isBlank()) {
            throw new IllegalArgumentException("image is required");
        }
        ensureDockerAvailable();
        // Cheap presence check.
        ProcResult inspect = runProcess(List.of("docker", "image", "inspect", image),
                Duration.ofSeconds(10), null);
        if (inspect.exitCode() == 0) {
            logger.debug("image {} already present; skipping pull", image);
            return;
        }

        // Approval gate — pull-only path. dockerfile is null on this branch.
        ImageApprovalGate.Request approvalReq = new ImageApprovalGate.Request(
                image, null, currentSource, image);
        ImageApprovalGate.Decision decision = approvalGate.approve(approvalReq);
        if (decision == ImageApprovalGate.Decision.DENY) {
            logger.warn("Image pull denied by approval gate: image={}, source={}",
                    image, currentSource);
            throw new ImageApprovalGate.ImageApprovalDeniedException(
                    "user/gate denied image pull for " + image);
        }

        ProcResult result = runProcess(List.of("docker", "pull", image),
                dockerCliTimeout, null);
        if (result.exitCode() != 0) {
            throw new IOException("docker pull " + image + " failed: "
                    + abbreviate(result.stderr(), 500));
        }
        logger.info("pulled image {}", image);
    }

    /**
     * Stop and remove every container this runtime started that is still
     * alive. Containers run with {@code --rm} so this only matters for
     * runaway/timed-out cases.
     */
    public List<String> stopContainersStartedByMe() {
        List<String> stopped = new ArrayList<>();
        for (String name : new LinkedHashSet<>(containersWeStarted)) {
            if (forceStop(name)) {
                stopped.add(name);
            }
        }
        containersWeStarted.clear();
        return stopped;
    }

    /**
     * Remove every image this runtime built (or pulled via {@link #pullImage}).
     * Does not touch images present before this runtime started, and does not
     * touch images other code on the host owns. Returns the tags actually
     * removed.
     */
    public List<String> pruneImagesBuiltByMe() {
        List<String> removed = new ArrayList<>();
        for (String tag : new LinkedHashSet<>(imagesWeBuilt)) {
            try {
                ProcResult r = runProcess(List.of("docker", "rmi", "-f", tag),
                        dockerCliTimeout, null);
                if (r.exitCode() == 0) {
                    removed.add(tag);
                    logger.info("removed image {}", tag);
                } else {
                    logger.debug("docker rmi {} failed: {}", tag, abbreviate(r.stderr(), 200));
                }
            } catch (Exception e) {
                logger.debug("rmi {} threw: {}", tag, e.getMessage());
            }
        }
        imagesWeBuilt.clear();
        return removed;
    }

    /** Snapshot of currently-tracked images (for tests + introspection). */
    public Set<String> trackedImages() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(imagesWeBuilt));
    }

    /** Snapshot of currently-tracked containers (for tests + introspection). */
    public Set<String> trackedContainers() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(containersWeStarted));
    }

    /**
     * Stop everything we tracked, then forget. Idempotent. Safe to call
     * multiple times. Pruning images is opt-in via {@link #pruneImagesBuiltByMe()}
     * because users may want to keep cached images across runs.
     */
    @Override
    public void close() {
        stopContainersStartedByMe();
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private void ensureDockerAvailable() {
        try {
            ProcResult r = runProcess(List.of("docker", "version", "--format", "{{.Server.Version}}"),
                    Duration.ofSeconds(5), null);
            if (r.exitCode() != 0) {
                throw new IllegalStateException(
                        "Docker daemon not reachable. `docker version` exited "
                                + r.exitCode() + ": " + abbreviate(r.stderr(), 200));
            }
        } catch (TimeoutException | InterruptedException | IOException e) {
            throw new IllegalStateException(
                    "Docker daemon not reachable: " + e.getMessage(), e);
        }
    }

    private boolean forceStop(String containerName) {
        try {
            ProcResult r = runProcess(List.of("docker", "rm", "-f", containerName),
                    Duration.ofSeconds(10), null);
            return r.exitCode() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Run an external process with a hard timeout, capturing stdout + stderr
     * concurrently on virtual threads (avoids pipe-buffer deadlock).
     */
    private static ProcResult runProcess(List<String> command, Duration timeout, Path cwd)
            throws IOException, InterruptedException, TimeoutException {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(false);
        if (cwd != null) {
            pb.directory(cwd.toFile());
        }
        Process process = pb.start();
        // Always close stdin so the child sees EOF immediately (cross-platform,
        // no /dev/null path).
        try { process.getOutputStream().close(); } catch (IOException ignored) {}

        CompletableFuture<String> outFuture = new CompletableFuture<>();
        CompletableFuture<String> errFuture = new CompletableFuture<>();
        Thread.startVirtualThread(() -> {
            try (var s = process.getInputStream()) {
                outFuture.complete(new String(s.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                outFuture.completeExceptionally(e);
            }
        });
        Thread.startVirtualThread(() -> {
            try (var s = process.getErrorStream()) {
                errFuture.complete(new String(s.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                errFuture.completeExceptionally(e);
            }
        });

        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new TimeoutException("process exceeded timeout " + timeout
                    + ": " + String.join(" ", command));
        }
        String out = readQuietly(outFuture);
        String err = readQuietly(errFuture);
        return new ProcResult(process.exitValue(), out, err);
    }

    private static String readQuietly(CompletableFuture<String> f) {
        try {
            return f.get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "";
        }
    }

    private static void deleteRecursively(Path root) {
        try {
            if (!Files.exists(root)) return;
            Files.walk(root)
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }

    private static String sha1Short(String s) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-1")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", hash[i]));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-1 ships with every JDK; this is unreachable.
            throw new IllegalStateException(e);
        }
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // ---------------------------------------------------------------------
    // Result types
    // ---------------------------------------------------------------------

    /** Result of one container skill invocation. */
    public record ExecResult(
            int exitCode,
            String stdout,
            String stderr,
            Duration elapsed,
            String containerName) {
        public boolean ok() { return exitCode == 0; }
    }

    /** Internal subprocess result — not exposed. */
    private record ProcResult(int exitCode, String stdout, String stderr) {}

}
