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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Discovers manifest-bearing Docker images on the local host. Used by
 * {@link ImagePruneAnalyzer} to decide which images to keep, by future
 * "which image should this gap use" selection logic, and by debugging
 * tooling (e.g. {@code --list-images}).
 *
 * <p>Survives JVM restarts: unlike {@link ContainerSkillRuntime#trackedImages()}
 * which only knows about images built in the current process, this
 * catalogue rediscovers the full set of swarmai-skill images on disk
 * by scanning Docker's image index.
 *
 * <h2>Default scope</h2>
 * Restricted to images matching {@value #DEFAULT_NAME_PATTERN} so a
 * runaway prune can't accidentally touch base images, MCP server
 * images, or anything else not produced by SwarmAI.
 *
 * <h2>Cost</h2>
 * One {@code docker images} call + one {@code docker inspect} per image.
 * For 50 images that's ~2 seconds total. Catalogue is one-shot — call
 * {@link #discover} again to refresh.
 */
public final class ImageCatalog {

    private static final Logger logger = LoggerFactory.getLogger(ImageCatalog.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Default reference filter — restricts the catalogue to framework-built images. */
    public static final String DEFAULT_NAME_PATTERN = "swarmai-skill-*";

    private final Duration cliTimeout;
    private final ManifestImageReader manifestReader;

    public ImageCatalog() {
        this(Duration.ofSeconds(15), new ManifestImageReader());
    }

    public ImageCatalog(Duration cliTimeout, ManifestImageReader manifestReader) {
        this.cliTimeout = cliTimeout;
        this.manifestReader = manifestReader;
    }

    /** Discover every framework-built image that has a manifest LABEL or is just present locally. */
    public List<Entry> discover() {
        return discover(DEFAULT_NAME_PATTERN);
    }

    /**
     * Discover every image matching a Docker reference filter. Pass
     * {@code "*"} to scan everything; pass a project-specific glob to
     * narrow the scope.
     */
    public List<Entry> discover(String namePattern) {
        List<String> tags = listImageTags(namePattern);
        if (tags.isEmpty()) return List.of();

        List<Entry> entries = new ArrayList<>(tags.size());
        for (String tag : tags) {
            entries.add(inspectOne(tag));
        }
        return entries;
    }

    private List<String> listImageTags(String namePattern) {
        // Use --format to get only tags; -q gives IDs (which may resolve to multiple tags).
        ProcResult result = runDocker(List.of(
                "docker", "images",
                "--filter", "reference=" + namePattern,
                "--format", "{{.Repository}}:{{.Tag}}"));
        if (result.exitCode() != 0) {
            logger.warn("docker images failed (exit {}): {}",
                    result.exitCode(), abbreviate(result.stderr(), 200));
            return List.of();
        }
        List<String> tags = new ArrayList<>();
        for (String line : result.stdout().split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.equals("<none>:<none>")) continue;
            // Skip dangling images — they have no useful identity.
            tags.add(trimmed);
        }
        return tags;
    }

    private Entry inspectOne(String tag) {
        ProcResult inspect = runDocker(List.of(
                "docker", "inspect",
                "--format", "{{.Id}}|{{.Created}}|{{.Size}}",
                tag));
        long sizeBytes = 0;
        Instant created = Instant.EPOCH;
        String imageId = "";
        if (inspect.exitCode() == 0) {
            String[] parts = inspect.stdout().trim().split("\\|", -1);
            if (parts.length >= 3) {
                imageId = parts[0];
                try { created = Instant.parse(parts[1]); }
                catch (Exception ignored) { /* keep EPOCH */ }
                try { sizeBytes = Long.parseLong(parts[2].trim()); }
                catch (NumberFormatException ignored) { /* keep 0 */ }
            }
        }

        Optional<CapabilityManifest> manifest = Optional.empty();
        String manifestError = null;
        try {
            manifest = manifestReader.readLabel(tag);
        } catch (RuntimeException e) {
            manifestError = e.getMessage();
        }
        return new Entry(tag, imageId, manifest, manifestError, sizeBytes, created);
    }

    // ---------------------------------------------------------------------
    // process plumbing
    // ---------------------------------------------------------------------

    private ProcResult runDocker(List<String> cmd) {
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
                while ((line = r.readLine()) != null) sink.append(line).append('\n');
            } catch (IOException ignored) { }
        }, "image-catalog-drain");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    /**
     * One catalogued image.
     *
     * @param imageTag       full reference (e.g. {@code swarmai-skill-9bb...:latest})
     * @param imageId        sha256 image ID; useful for stable identity
     * @param manifest       parsed capability manifest if the LABEL is present + valid
     * @param manifestError  non-null when manifest parse failed; null when manifest absent OK
     * @param sizeBytes      on-disk size in bytes
     * @param created        UTC timestamp from {@code .Created}
     */
    public record Entry(
            String imageTag,
            String imageId,
            Optional<CapabilityManifest> manifest,
            String manifestError,
            long sizeBytes,
            Instant created) {

        public boolean hasManifest() { return manifest.isPresent(); }

        /** Capability names this image declares; empty when no manifest. */
        public List<String> capabilityNames() {
            return manifest.map(m -> m.tools().stream()
                    .map(CapabilityManifest.Capability::name).toList())
                    .orElse(List.of());
        }

        /** Image age in days, computed from {@code created}. */
        public long ageDays() {
            return Math.max(0, Duration.between(created, Instant.now()).toDays());
        }

        public String summary() {
            String caps = manifest
                    .map(m -> m.size() + " caps: " + capabilityNames())
                    .orElseGet(() -> manifestError != null
                            ? "manifest broken: " + abbreviate(manifestError, 60)
                            : "no manifest");
            return imageTag + " [" + (sizeBytes / 1_000_000) + " MB, " + ageDays() + "d, " + caps + "]";
        }
    }

    /** Lightweight process-result holder. */
    private record ProcResult(int exitCode, String stdout, String stderr) { }
}
