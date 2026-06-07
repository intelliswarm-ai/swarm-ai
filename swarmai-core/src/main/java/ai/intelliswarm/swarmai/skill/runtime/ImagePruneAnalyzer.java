package ai.intelliswarm.swarmai.skill.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;

/**
 * Manifest-aware redundancy analyser for the image catalogue. Decides
 * which images to keep (because they're the best provider of at least
 * one capability) and which are redundant (every capability they offer
 * is offered better by another image) — and optionally executes the
 * prune.
 *
 * <h2>Why this exists</h2>
 * Blind prune by name pattern is wasteful: it removes images you might
 * still want. Keeping every framework-built image is also wasteful:
 * yesterday's experimental image with one capability is dominated by
 * today's image that has the same capability plus three more, in less
 * disk space. The analyser picks the dominant set.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Build inverse index: {@code capability_name → [Entry, Entry, ...]}.</li>
 *   <li>For each capability, score every provider; the best provider
 *       wins that capability.</li>
 *   <li>An image is a {@link Decision.Keep} if it wins ≥1 capability;
 *       otherwise it's a {@link Decision.Prune} (every capability it
 *       offers has a better provider).</li>
 *   <li>Images with no manifest fall into a separate
 *       {@link Decision.Orphan} bucket — the caller decides whether to
 *       prune them (they may pre-date manifest support).</li>
 * </ol>
 *
 * <h2>Default scoring</h2>
 * <pre>
 *   score = 1.0
 *         + recencyBonus(age)        // 0..0.5 — newer wins
 *         - sizeP enalty(sizeBytes)  // 0..0.3 — smaller wins, only past 50 MB
 *         + manifestVersionBonus     // 0..0.1 — newer schema wins ties
 * </pre>
 * Override via {@link Builder#scorer(ToDoubleFunction)} for custom rankings
 * (e.g. plugging in smoke-test pass rate, last-used timestamps, hand-coded vs.
 * codex-generated source).
 */
public final class ImagePruneAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(ImagePruneAnalyzer.class);

    private final ToDoubleFunction<ImageCatalog.Entry> scorer;
    private final Duration cliTimeout;

    private ImagePruneAnalyzer(Builder b) {
        this.scorer = b.scorer != null ? b.scorer : ImagePruneAnalyzer::defaultScore;
        this.cliTimeout = b.cliTimeout;
    }

    /** Default scorer: recency + size + manifest schema version. */
    public static double defaultScore(ImageCatalog.Entry e) {
        double recency = Math.max(0, 1.0 - (e.ageDays() / 30.0));   // 1.0 fresh → 0.0 at 30 days
        double sizeMb = e.sizeBytes() / 1_000_000.0;
        double sizePenalty = Math.max(0, Math.min(0.3, (sizeMb - 50) / 3000.0));
        double schemaBonus = e.manifest()
                .map(m -> "1".equals(m.version()) ? 0.05 : 0.10)    // future schemas score slightly higher
                .orElse(0.0);
        return 1.0 + 0.5 * recency - sizePenalty + schemaBonus;
    }

    /**
     * Analyse a catalogue snapshot and produce keep/prune decisions per image.
     * Pure function — no Docker calls, no side effects. Run
     * {@link #execute(Analysis, boolean)} to actually prune.
     */
    public Analysis analyze(List<ImageCatalog.Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            return new Analysis(List.of(), List.of(), List.of());
        }

        // Split off images without manifests up front — they need a
        // separate decision because we don't know what they're good for.
        List<ImageCatalog.Entry> withManifest = new ArrayList<>();
        List<Decision.Orphan> orphans = new ArrayList<>();
        for (var e : entries) {
            if (e.hasManifest()) withManifest.add(e);
            else orphans.add(new Decision.Orphan(e,
                    e.manifestError() == null ? "no manifest LABEL" : e.manifestError()));
        }

        // Build inverse index: capability name → list of providers.
        Map<String, List<ImageCatalog.Entry>> byCapability = new LinkedHashMap<>();
        for (var e : withManifest) {
            for (var capName : e.capabilityNames()) {
                byCapability.computeIfAbsent(capName, k -> new ArrayList<>()).add(e);
            }
        }

        // For each capability, score providers and pick the winner.
        // Track which capabilities each image wins.
        Map<ImageCatalog.Entry, List<String>> winsByImage = new LinkedHashMap<>();
        Map<String, ImageCatalog.Entry> winnerByCapability = new LinkedHashMap<>();
        for (var entry : byCapability.entrySet()) {
            String cap = entry.getKey();
            List<ImageCatalog.Entry> providers = entry.getValue();
            ImageCatalog.Entry winner = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (var p : providers) {
                double s = scorer.applyAsDouble(p);
                if (s > bestScore) {
                    bestScore = s;
                    winner = p;
                }
            }
            if (winner != null) {
                winnerByCapability.put(cap, winner);
                winsByImage.computeIfAbsent(winner, k -> new ArrayList<>()).add(cap);
            }
        }

        // Classify each image: keeper if it wins anything, redundant otherwise.
        List<Decision.Keep> keepers = new ArrayList<>();
        List<Decision.Prune> redundant = new ArrayList<>();
        for (var e : withManifest) {
            List<String> wins = winsByImage.getOrDefault(e, List.of());
            if (!wins.isEmpty()) {
                keepers.add(new Decision.Keep(e, wins, scorer.applyAsDouble(e)));
            } else {
                // Find the best image that supersedes this one (first capability
                // it offers, see who won that capability).
                String supersededBy = null;
                String reason = "all capabilities have a better provider";
                for (var capName : e.capabilityNames()) {
                    var w = winnerByCapability.get(capName);
                    if (w != null) { supersededBy = w.imageTag(); break; }
                }
                redundant.add(new Decision.Prune(e, supersededBy, reason));
            }
        }
        return new Analysis(keepers, redundant, orphans);
    }

    /**
     * Execute the prune. When {@code dryRun} is true, just logs what
     * would happen; when false, runs {@code docker rmi -f} for each
     * redundant image. Orphans are NOT pruned automatically — the caller
     * must decide via {@link #pruneOrphans(List, boolean)}.
     *
     * @return number of images actually removed (or that would be in dry run)
     */
    public int execute(Analysis analysis, boolean dryRun) {
        return prune(
                analysis.redundant().stream()
                        .map(Decision.Prune::entry)
                        .map(ImageCatalog.Entry::imageTag).toList(),
                dryRun);
    }

    /**
     * Prune the orphan bucket — images without manifests. Some are legacy
     * framework builds; some may be hand-built. Caller's choice.
     *
     * @return number of images removed (or planned in dry run)
     */
    public int pruneOrphans(List<Decision.Orphan> orphans, boolean dryRun) {
        return prune(orphans.stream()
                .map(Decision.Orphan::entry)
                .map(ImageCatalog.Entry::imageTag).toList(),
                dryRun);
    }

    private int prune(List<String> tags, boolean dryRun) {
        int removed = 0;
        for (String tag : tags) {
            if (dryRun) {
                logger.info("[dry-run] would remove {}", tag);
                removed++;
                continue;
            }
            ProcResult r = runDocker(List.of("docker", "rmi", "-f", tag));
            if (r.exitCode() == 0) {
                logger.info("removed {}", tag);
                removed++;
            } else {
                logger.warn("failed to remove {}: {}",
                        tag, r.stderr().trim().lines().findFirst().orElse(""));
            }
        }
        return removed;
    }

    // ---------------------------------------------------------------------
    // process plumbing — small, no-frills wrapper around `docker rmi`
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

    // ---------------------------------------------------------------------
    // builder + result types
    // ---------------------------------------------------------------------

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private ToDoubleFunction<ImageCatalog.Entry> scorer;
        private Duration cliTimeout = Duration.ofSeconds(30);

        public Builder scorer(ToDoubleFunction<ImageCatalog.Entry> scorer) {
            this.scorer = scorer;
            return this;
        }
        public Builder cliTimeout(Duration t) { this.cliTimeout = t; return this; }
        public ImagePruneAnalyzer build() { return new ImagePruneAnalyzer(this); }
    }

    /**
     * The result of analysing a catalogue. Pure data — caller decides
     * whether to {@link #execute(Analysis, boolean) execute} the prune.
     */
    public record Analysis(
            List<Decision.Keep> keepers,
            List<Decision.Prune> redundant,
            List<Decision.Orphan> orphans) {

        public long bytesReclaimable() {
            return redundant.stream()
                    .mapToLong(p -> p.entry().sizeBytes())
                    .sum();
        }

        public long bytesReclaimableWithOrphans() {
            return bytesReclaimable() + orphans.stream()
                    .mapToLong(o -> o.entry().sizeBytes())
                    .sum();
        }

        public String summary() {
            return String.format(
                    "Catalog: %d keepers, %d redundant (%.1f MB), %d orphans (%.1f MB extra)",
                    keepers.size(),
                    redundant.size(), bytesReclaimable() / 1_000_000.0,
                    orphans.size(),
                    (bytesReclaimableWithOrphans() - bytesReclaimable()) / 1_000_000.0);
        }
    }

    /** Per-image decision types. */
    public sealed interface Decision {
        ImageCatalog.Entry entry();

        record Keep(ImageCatalog.Entry entry,
                    List<String> wonCapabilities,
                    double score) implements Decision {}

        record Prune(ImageCatalog.Entry entry,
                     String supersededBy,
                     String reason) implements Decision {}

        record Orphan(ImageCatalog.Entry entry,
                      String reason) implements Decision {}
    }

    private record ProcResult(int exitCode, String stdout, String stderr) { }
}
