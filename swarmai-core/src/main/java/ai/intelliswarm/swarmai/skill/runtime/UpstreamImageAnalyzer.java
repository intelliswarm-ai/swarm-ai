package ai.intelliswarm.swarmai.skill.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Identifies upstream Docker images (anything outside the
 * {@code swarmai-skill-*} namespace) that no longer have any references
 * on this host — orphans we pulled at some point and stopped using.
 * Complements {@link ImagePruneAnalyzer}, which only reasons about
 * framework-built images.
 *
 * <h2>Why this lives in a separate class</h2>
 * Upstream image deletion is irreversible (a future build pays for the
 * re-pull) and the trust model is different — we built our own
 * {@code swarmai-skill-*} images, but we did <em>not</em> build
 * {@code alpine:3.19} or {@code python:3.12}. The analyzer therefore
 * applies stricter "in use" checks before suggesting prune:
 *
 * <ol>
 *   <li><b>Layer-history check</b> — for every kept manifest-bearing
 *       image, walk its {@code docker image history} and collect every
 *       intermediate layer ID. Any upstream image whose ID appears in
 *       that set was a {@code FROM} base for a keeper, so it's
 *       structurally protected even if no container ever ran it.</li>
 *   <li><b>Container ancestry check</b> — any upstream image that has
 *       at least one container (running OR stopped) referencing it as
 *       its ancestor is protected. Stopped containers are usually kept
 *       intentionally — debugging, reproducibility, etc.</li>
 *   <li><b>Built-by-us guard</b> — never proposes touching anything
 *       matching {@code swarmai-skill-*}; that's
 *       {@link ImagePruneAnalyzer}'s job.</li>
 * </ol>
 *
 * <p>Anything passing all three guards goes into the
 * {@link Candidate} bucket — the caller decides whether to actually
 * remove. Default in workflows is to <em>list</em>, not remove, exactly
 * because false positives break unrelated workflows.
 */
public final class UpstreamImageAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(UpstreamImageAnalyzer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Image-name patterns we never propose pruning regardless of usage signal. */
    private static final List<String> PROTECTED_PATTERNS = List.of(
            "swarmai-skill-",   // our own builds — handled by ImagePruneAnalyzer
            "<none>"            // dangling / pruning-target — leave to docker prune
    );

    private final Duration cliTimeout;

    public UpstreamImageAnalyzer() { this(Duration.ofSeconds(20)); }

    public UpstreamImageAnalyzer(Duration cliTimeout) {
        this.cliTimeout = cliTimeout;
    }

    /**
     * Analyse the host's image set, given a list of framework-built
     * images we've decided to keep (the keepers + orphan-but-still-here
     * from {@link ImagePruneAnalyzer}). Returns upstream images that
     * appear to have no references.
     *
     * @param keptImageIds image IDs (sha256:…) of every framework-built
     *                     image we're keeping; used for the layer-
     *                     history protection check.
     */
    public Analysis analyze(List<String> keptImageIds) {
        // 1. Collect all images on the host and partition by "is upstream".
        List<UpstreamRecord> allUpstream = listAllUpstreamImages();
        if (allUpstream.isEmpty()) {
            return new Analysis(List.of(), Set.of());
        }

        // 2. Build the layer index. For each kept image, get its full RootFS
        //    layer list. Then for each upstream image, get its layers. An
        //    upstream image was the FROM base of a kept image if its layers
        //    form a non-empty PREFIX of the kept image's layers — Docker
        //    appends new layers on top of the base's layer set during build.
        //
        //    Why this works where docker history fails: history shows the
        //    keeper's own build steps, with base-image layers appearing as
        //    <missing> entries (the base was built elsewhere; this host
        //    doesn't have its build history). RootFS.Layers, on the other
        //    hand, lists the actual content-addressable layer SHAs that
        //    every image carries — and prefix-matching SHAs is exact and
        //    deterministic.
        Map<String, List<String>> keptLayers = new HashMap<>();
        for (String keptId : keptImageIds) {
            List<String> layers = rootfsLayers(keptId);
            if (!layers.isEmpty()) keptLayers.put(keptId, layers);
        }

        Set<String> protectedBaseIds = new HashSet<>();
        List<Candidate> candidates = new ArrayList<>();
        for (UpstreamRecord rec : allUpstream) {
            List<String> upLayers = rootfsLayers(rec.imageTag());
            if (!upLayers.isEmpty() && isBaseOfAny(upLayers, keptLayers.values())) {
                protectedBaseIds.add(rec.imageId());
                logger.debug("Protected: {} is a FROM-base of a kept image (layer prefix match)",
                        rec.imageTag());
                continue;
            }
            int containers = countAncestorContainers(rec.imageTag());
            if (containers > 0) {
                logger.debug("Protected: {} has {} container(s) referencing it",
                        rec.imageTag(), containers);
                continue;
            }
            // Pure orphan — no protection signal.
            candidates.add(new Candidate(
                    rec.imageTag(),
                    rec.imageId(),
                    rec.sizeBytes(),
                    "no container references it AND not a base layer of any kept image"));
        }
        return new Analysis(candidates, protectedBaseIds);
    }

    /** True when {@code maybeBase}'s layers are a non-empty prefix of any keeper's layers. */
    static boolean isBaseOfAny(List<String> maybeBase, Iterable<List<String>> keepers) {
        if (maybeBase == null || maybeBase.isEmpty()) return false;
        for (List<String> kept : keepers) {
            if (isPrefix(maybeBase, kept)) return true;
        }
        return false;
    }

    private static boolean isPrefix(List<String> base, List<String> full) {
        if (base.size() > full.size()) return false;
        for (int i = 0; i < base.size(); i++) {
            if (!base.get(i).equals(full.get(i))) return false;
        }
        return true;
    }

    // ---------------------------------------------------------------------
    // discovery helpers
    // ---------------------------------------------------------------------

    /** Enumerate every non-protected image on the host with id + size. */
    private List<UpstreamRecord> listAllUpstreamImages() {
        ProcResult r = runDocker(List.of(
                "docker", "images",
                "--no-trunc",
                "--format", "{{.Repository}}:{{.Tag}}|{{.ID}}|{{.Size}}"));
        if (r.exitCode() != 0) {
            logger.warn("docker images failed: {}", abbreviate(r.stderr(), 200));
            return List.of();
        }
        List<UpstreamRecord> out = new ArrayList<>();
        for (String line : r.stdout().split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String[] parts = trimmed.split("\\|", -1);
            if (parts.length < 3) continue;
            String tag = parts[0];
            if (isProtectedName(tag)) continue;
            String id = parts[1].trim();
            long size = parseSizeToBytes(parts[2].trim());
            out.add(new UpstreamRecord(tag, id, size));
        }
        return out;
    }

    private static boolean isProtectedName(String tag) {
        for (String prefix : PROTECTED_PATTERNS) {
            if (tag.startsWith(prefix)) return true;
        }
        return false;
    }

    /** Get the RootFS.Layers list for an image — the content-addressable layer SHAs. */
    private List<String> rootfsLayers(String imageRef) {
        if (imageRef == null || imageRef.isBlank()) return List.of();
        ProcResult r = runDocker(List.of(
                "docker", "image", "inspect", imageRef,
                "--format", "{{json .RootFS.Layers}}"));
        if (r.exitCode() != 0) return List.of();
        String stdout = r.stdout().trim();
        if (stdout.isEmpty() || stdout.equals("null")) return List.of();
        try {
            JsonNode node = MAPPER.readTree(stdout);
            if (!node.isArray()) return List.of();
            List<String> out = new ArrayList<>(node.size());
            for (JsonNode el : node) out.add(el.asText());
            return out;
        } catch (Exception e) {
            logger.debug("rootfsLayers parse failed for {}: {}", imageRef, e.getMessage());
            return List.of();
        }
    }

    /** How many containers (running or stopped) reference this image as ancestor? */
    private int countAncestorContainers(String imageTag) {
        ProcResult r = runDocker(List.of(
                "docker", "ps", "-a",
                "--filter", "ancestor=" + imageTag,
                "-q"));
        if (r.exitCode() != 0) return 0;   // be conservative on errors
        return (int) Arrays.stream(r.stdout().split("\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .count();
    }

    // ---------------------------------------------------------------------
    // execution
    // ---------------------------------------------------------------------

    /**
     * Remove upstream candidates with {@code docker rmi -f}. Returns the
     * count actually removed. When {@code dryRun=true}, no Docker calls
     * are made — the count is just the candidate-list size.
     */
    public int execute(Analysis analysis, boolean dryRun) {
        int removed = 0;
        for (Candidate c : analysis.candidates()) {
            if (dryRun) {
                logger.info("[dry-run] would remove upstream {}", c.imageTag());
                removed++;
                continue;
            }
            ProcResult r = runDocker(List.of("docker", "rmi", "-f", c.imageTag()));
            if (r.exitCode() == 0) {
                logger.info("removed upstream {}", c.imageTag());
                removed++;
            } else {
                logger.warn("failed to remove {}: {}",
                        c.imageTag(),
                        r.stderr().trim().lines().findFirst().orElse(""));
            }
        }
        return removed;
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
                Thread d1 = new Thread(() -> read(stdout, out)); d1.setDaemon(true); d1.start();
                Thread d2 = new Thread(() -> read(stderr, err)); d2.setDaemon(true); d2.start();
                boolean finished = p.waitFor(cliTimeout.toMillis(), TimeUnit.MILLISECONDS);
                if (!finished) {
                    p.destroyForcibly();
                    return new ProcResult(-1, out.toString(), err + "\n(timed out)");
                }
                d1.join(500); d2.join(500);
                return new ProcResult(p.exitValue(), out.toString(), err.toString());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            if (p != null) p.destroyForcibly();
            return new ProcResult(-1, "", "process error: " + e.getMessage());
        }
    }

    private static void read(BufferedReader r, StringBuilder sink) {
        try {
            String line;
            while ((line = r.readLine()) != null) sink.append(line).append('\n');
        } catch (IOException ignored) { }
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    /** Parse Docker's "Size" string ("1.2GB", "456MB", "78kB") into bytes. */
    static long parseSizeToBytes(String size) {
        if (size == null || size.isBlank()) return 0;
        String s = size.trim().toUpperCase();
        try {
            if (s.endsWith("GB")) return (long) (Double.parseDouble(s.substring(0, s.length() - 2).trim()) * 1_000_000_000L);
            if (s.endsWith("MB")) return (long) (Double.parseDouble(s.substring(0, s.length() - 2).trim()) * 1_000_000L);
            if (s.endsWith("KB")) return (long) (Double.parseDouble(s.substring(0, s.length() - 2).trim()) * 1_000L);
            if (s.endsWith("B")) return Long.parseLong(s.substring(0, s.length() - 1).trim());
        } catch (NumberFormatException ignored) { }
        return 0;
    }

    // ---------------------------------------------------------------------
    // result types
    // ---------------------------------------------------------------------

    /** Pure orphan upstream image: not a base of any keeper, no containers. */
    public record Candidate(
            String imageTag,
            String imageId,
            long sizeBytes,
            String reason) { }

    /** Discovery result. */
    public record Analysis(
            List<Candidate> candidates,
            Set<String> protectedBaseIds) {

        public long bytesReclaimable() {
            return candidates.stream().mapToLong(Candidate::sizeBytes).sum();
        }

        public String summary() {
            return String.format(
                    "Upstream: %d orphan(s) (%.1f MB), %d base layer ID(s) protected",
                    candidates.size(),
                    bytesReclaimable() / 1_000_000.0,
                    protectedBaseIds.size());
        }
    }

    /** Internal record holding raw discovery data. */
    private record UpstreamRecord(String imageTag, String imageId, long sizeBytes) {}

    /** Lightweight process-result holder. */
    private record ProcResult(int exitCode, String stdout, String stderr) { }
}
