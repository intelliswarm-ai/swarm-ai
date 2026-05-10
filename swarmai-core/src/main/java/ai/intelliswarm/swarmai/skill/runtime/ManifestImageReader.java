package ai.intelliswarm.swarmai.skill.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Reads a {@link CapabilityManifest} from a built or pulled Docker image.
 *
 * <p>Two transports, tried in priority order:
 * <ol>
 *   <li><b>LABEL transport</b> — {@code LABEL swarmai.manifest=<base64-json>}
 *       baked into the image. Read via {@code docker inspect}; no
 *       container needs to start. Cheap, fast, works when the image won't
 *       even run on this host.</li>
 *   <li><b>Filesystem transport</b> — {@code /etc/swarmai/manifest.json}
 *       inside the image. Read by spawning a one-shot
 *       {@code docker run --rm <image> cat /etc/swarmai/manifest.json}.
 *       Slower (full container start) but doesn't require the image
 *       producer to remember the LABEL invocation.</li>
 * </ol>
 *
 * <h2>Why this is the keystone of dynamic capability acquisition</h2>
 * Once Codex emits a Dockerfile + manifest pair and the framework builds
 * the image, this reader is what closes the loop: it pulls the manifest
 * back out of the image so {@link ManifestToolFactory} can materialise
 * fresh BaseTools, which can then be hot-registered with the running
 * Agent. Without this class, the manifest only lives in the local
 * filesystem at build time — survives one process; doesn't survive a
 * cross-host transfer.
 *
 * <h2>Failure modes</h2>
 * The reader returns {@code Optional.empty()} when the image has no
 * manifest, rather than throwing. Manifests that are present but invalid
 * (bad JSON, missing required fields, etc.) <em>do</em> throw — that's
 * a real bug or a hostile image, and the caller needs to know.
 */
public final class ManifestImageReader {

    private static final Logger logger = LoggerFactory.getLogger(ManifestImageReader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Standard LABEL key carrying the base64-encoded manifest. */
    public static final String MANIFEST_LABEL = "swarmai.manifest";

    /** Standard filesystem path inside the image. */
    public static final String MANIFEST_PATH = "/etc/swarmai/manifest.json";

    private final Duration cliTimeout;

    public ManifestImageReader() { this(Duration.ofSeconds(20)); }

    public ManifestImageReader(Duration cliTimeout) {
        this.cliTimeout = cliTimeout;
    }

    /**
     * Try every available transport and return the first manifest found.
     *
     * @param imageTag the image to introspect (must already be present
     *                 locally — this reader doesn't pull)
     * @return the manifest, or empty if none present
     */
    public Optional<CapabilityManifest> read(String imageTag) {
        if (imageTag == null || imageTag.isBlank()) {
            throw new IllegalArgumentException("imageTag is required");
        }
        Optional<CapabilityManifest> fromLabel = readLabel(imageTag);
        if (fromLabel.isPresent()) return fromLabel;
        return readFilesystem(imageTag);
    }

    /**
     * Read the manifest from the {@value #MANIFEST_LABEL} LABEL via
     * {@code docker inspect}. Cheap and doesn't need the image to run.
     * Returns empty if the LABEL isn't present.
     */
    public Optional<CapabilityManifest> readLabel(String imageTag) {
        ProcResult result = runDocker("inspect", "--format", "{{json .Config.Labels}}", imageTag);
        if (result.exitCode() != 0) {
            logger.debug("docker inspect {} failed (exit {}): {}",
                    imageTag, result.exitCode(), abbreviate(result.stderr(), 200));
            return Optional.empty();
        }
        String stdout = result.stdout().trim();
        // `null` is what docker prints when the image has no labels at all.
        if (stdout.isEmpty() || stdout.equals("null")) return Optional.empty();
        try {
            JsonNode labels = MAPPER.readTree(stdout);
            JsonNode manifestB64 = labels.get(MANIFEST_LABEL);
            if (manifestB64 == null || manifestB64.isNull()) return Optional.empty();
            String b64 = manifestB64.asText();
            if (b64.isEmpty()) return Optional.empty();
            return Optional.of(ManifestParser.parseBase64(b64));
        } catch (RuntimeException | IOException e) {
            logger.warn("Failed to read LABEL manifest from {}: {}", imageTag, e.getMessage());
            // Manifest was present but malformed — that's a real error worth surfacing.
            // We rethrow as RuntimeException so callers don't silently miss bad manifests.
            if (e instanceof ManifestParser.ManifestParseException mpe) throw mpe;
            return Optional.empty();
        }
    }

    /**
     * Read the manifest from {@value #MANIFEST_PATH} inside the image.
     * Spawns a one-shot {@code docker run --rm} container with the
     * default entrypoint overridden to {@code cat}. Slower than the LABEL
     * transport (full container start) but tolerant of images that
     * forgot the LABEL.
     */
    public Optional<CapabilityManifest> readFilesystem(String imageTag) {
        ProcResult result = runDocker(
                "run", "--rm",
                "--entrypoint", "cat",
                "--network", "none",
                imageTag, MANIFEST_PATH);
        if (result.exitCode() != 0) {
            // Not having the file is the common case; only log if it's clearly
            // a different failure (image doesn't exist, etc.).
            if (result.stderr() != null && result.stderr().contains("No such file")) {
                logger.debug("no manifest file at {} in {}", MANIFEST_PATH, imageTag);
            } else {
                logger.debug("docker run cat manifest failed for {} (exit {}): {}",
                        imageTag, result.exitCode(), abbreviate(result.stderr(), 200));
            }
            return Optional.empty();
        }
        String json = result.stdout();
        if (json == null || json.isBlank()) return Optional.empty();
        return Optional.of(ManifestParser.parse(json));
    }

    // ---------------------------------------------------------------------
    // Process plumbing — small and self-contained so this class doesn't
    // need to reach into ContainerSkillRuntime's private internals.
    // ---------------------------------------------------------------------

    private ProcResult runDocker(String... args) {
        java.util.List<String> cmd = new java.util.ArrayList<>(args.length + 1);
        cmd.add("docker");
        for (String a : args) cmd.add(a);
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(false);
        Process p = null;
        try {
            p = pb.start();
            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            try (BufferedReader stdout = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                 BufferedReader stderr = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                Thread t1 = drainAsync(stdout, out);
                Thread t2 = drainAsync(stderr, err);
                boolean finished = p.waitFor(cliTimeout.toMillis(), TimeUnit.MILLISECONDS);
                if (!finished) {
                    p.destroyForcibly();
                    return new ProcResult(-1, out.toString(), err + "\n(timed out)");
                }
                t1.join(500); t2.join(500);
                return new ProcResult(p.exitValue(), out.toString(), err.toString());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            if (p != null) p.destroyForcibly();
            return new ProcResult(-1, "", "process error: " + e.getMessage());
        }
    }

    private static Thread drainAsync(BufferedReader r, StringBuilder sink) {
        Thread t = new Thread(() -> {
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    sink.append(line).append('\n');
                }
            } catch (IOException ignored) { }
        }, "manifest-image-reader-drain");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    /** Lightweight process-result holder, mirrors ContainerSkillRuntime.ProcResult. */
    private record ProcResult(int exitCode, String stdout, String stderr) { }
}
