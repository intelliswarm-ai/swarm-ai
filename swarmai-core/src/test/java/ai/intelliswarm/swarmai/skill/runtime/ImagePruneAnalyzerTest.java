package ai.intelliswarm.swarmai.skill.runtime;

import ai.intelliswarm.swarmai.skill.runtime.CapabilityManifest.Capability;
import ai.intelliswarm.swarmai.skill.runtime.ImagePruneAnalyzer.Decision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the analyzer's pure decision logic. No Docker required.
 *
 * <p>The default scorer rewards recency and small size; these tests
 * construct synthetic catalogue entries to verify the algorithm picks
 * the dominating image when multiple provide the same capability.
 */
class ImagePruneAnalyzerTest {

    private static Capability cap(String name) {
        return new Capability(
                name,
                "tool " + name,
                "", "", "", List.of(), List.of(),
                "echo " + name, 30, "none", null);
    }

    private static ImageCatalog.Entry entry(String tag, long sizeMb, long ageDays, String... caps) {
        CapabilityManifest manifest = new CapabilityManifest(
                tag, "1", "test",
                java.util.Arrays.stream(caps).map(ImagePruneAnalyzerTest::cap).toList());
        return new ImageCatalog.Entry(
                tag, "sha256:" + tag.hashCode(),
                Optional.of(manifest),
                null,
                sizeMb * 1_000_000L,
                Instant.now().minus(Duration.ofDays(ageDays)));
    }

    private static ImageCatalog.Entry orphan(String tag, long sizeMb, long ageDays) {
        return new ImageCatalog.Entry(
                tag, "sha256:" + tag.hashCode(),
                Optional.empty(), "no manifest LABEL",
                sizeMb * 1_000_000L,
                Instant.now().minus(Duration.ofDays(ageDays)));
    }

    @Test
    @DisplayName("Single image is always kept (no comparison candidates)")
    void singleImageIsKept() {
        var analyzer = ImagePruneAnalyzer.builder().build();
        var analysis = analyzer.analyze(List.of(entry("only:1", 60, 1, "nmap_scan")));
        assertEquals(1, analysis.keepers().size());
        assertTrue(analysis.redundant().isEmpty());
        assertEquals("only:1", analysis.keepers().get(0).entry().imageTag());
    }

    @Test
    @DisplayName("Newer image dominates older image with same capability")
    void newerWinsSameCapability() {
        var analyzer = ImagePruneAnalyzer.builder().build();
        var old = entry("old:1", 60, 20, "nmap_scan");
        var fresh = entry("fresh:1", 60, 1, "nmap_scan");

        var analysis = analyzer.analyze(List.of(old, fresh));
        assertEquals(1, analysis.keepers().size());
        assertEquals("fresh:1", analysis.keepers().get(0).entry().imageTag());
        assertEquals(1, analysis.redundant().size());
        assertEquals("old:1", analysis.redundant().get(0).entry().imageTag());
        assertEquals("fresh:1", analysis.redundant().get(0).supersededBy());
    }

    @Test
    @DisplayName("Smaller image wins when ages are comparable")
    void smallerWinsAtSameAge() {
        var analyzer = ImagePruneAnalyzer.builder().build();
        // Both 1 day old, but the fat one is 10x larger → size penalty kicks in.
        var fat = entry("fat:1", 2000, 1, "nmap_scan");
        var slim = entry("slim:1", 60, 1, "nmap_scan");

        var analysis = analyzer.analyze(List.of(fat, slim));
        assertEquals("slim:1", analysis.keepers().get(0).entry().imageTag(),
                "smaller image should win at comparable age");
        assertEquals("fat:1", analysis.redundant().get(0).entry().imageTag());
    }

    @Test
    @DisplayName("Image with unique capability is kept even when dominated elsewhere")
    void uniqueCapabilityKeepsImage() {
        var analyzer = ImagePruneAnalyzer.builder().build();
        // Both are 10 days old. swiss has nmap_scan + http_check; focused
        // has only nmap_scan. focused wins nmap_scan (smaller), but swiss
        // is the only provider of http_check → both kept.
        var focused = entry("focused:1", 60, 10, "nmap_scan");
        var swiss = entry("swiss:1", 800, 10, "nmap_scan", "http_check", "dns_lookup");

        var analysis = analyzer.analyze(List.of(focused, swiss));
        assertEquals(2, analysis.keepers().size(),
                "swiss must be kept for http_check + dns_lookup uniqueness");
        assertTrue(analysis.redundant().isEmpty());
        var swissKeep = analysis.keepers().stream()
                .filter(k -> "swiss:1".equals(k.entry().imageTag()))
                .findFirst().orElseThrow();
        assertEquals(List.of("http_check", "dns_lookup"), swissKeep.wonCapabilities(),
                "swiss should win exactly the unique capabilities");
    }

    @Test
    @DisplayName("Three-way overlap picks one keeper and prunes two")
    void threeWayOverlapKeepsOne() {
        var analyzer = ImagePruneAnalyzer.builder().build();
        var a = entry("a:1", 800, 30, "nmap_scan");
        var b = entry("b:1", 400, 14, "nmap_scan");
        var c = entry("c:1", 60, 1, "nmap_scan");

        var analysis = analyzer.analyze(List.of(a, b, c));
        assertEquals(1, analysis.keepers().size());
        assertEquals("c:1", analysis.keepers().get(0).entry().imageTag());
        assertEquals(2, analysis.redundant().size());
    }

    @Test
    @DisplayName("Orphans (no manifest) bucket separately from prune decisions")
    void orphansArePartitionedSeparately() {
        var analyzer = ImagePruneAnalyzer.builder().build();
        var managed = entry("managed:1", 60, 1, "nmap_scan");
        var legacy1 = orphan("legacy1:1", 200, 30);
        var legacy2 = orphan("legacy2:1", 300, 45);

        var analysis = analyzer.analyze(List.of(managed, legacy1, legacy2));
        assertEquals(1, analysis.keepers().size());
        assertTrue(analysis.redundant().isEmpty());
        assertEquals(2, analysis.orphans().size(),
                "manifest-less images should be partitioned into orphans");
    }

    @Test
    @DisplayName("bytesReclaimable accounts for redundant images only")
    void bytesReclaimableMath() {
        var analyzer = ImagePruneAnalyzer.builder().build();
        var redundant = entry("old:1", 800, 30, "nmap_scan");      // 800 MB redundant
        var winner = entry("new:1", 60, 1, "nmap_scan");           // kept
        var orphan = orphan("legacy:1", 300, 90);                   // 300 MB orphan

        var analysis = analyzer.analyze(List.of(redundant, winner, orphan));
        assertEquals(800_000_000L, analysis.bytesReclaimable(),
                "bytesReclaimable should count only redundant manifests");
        assertEquals(1_100_000_000L, analysis.bytesReclaimableWithOrphans(),
                "with-orphans variant should add orphan bytes");
    }

    @Test
    @DisplayName("Empty catalogue returns empty analysis")
    void emptyCatalog() {
        var analysis = ImagePruneAnalyzer.builder().build().analyze(List.of());
        assertTrue(analysis.keepers().isEmpty());
        assertTrue(analysis.redundant().isEmpty());
        assertTrue(analysis.orphans().isEmpty());
        assertEquals(0L, analysis.bytesReclaimable());
    }

    @Test
    @DisplayName("Custom scorer overrides the default — smoke pass-rate plug-in")
    void customScorer() {
        // Imagine we have smoke-test data: A is 100% reliable, B is 0% reliable.
        // Default scorer picks B because B is fresher and smaller. Custom scorer
        // ignores recency/size and uses the "trust" tag as a proxy for pass rate.
        var trustedOld = entry("trusted-but-old:1", 100, 25, "nmap_scan");
        var brokenFresh = entry("broken-but-fresh:1", 50, 1, "nmap_scan");
        java.util.Map<String, Double> trustScores = java.util.Map.of(
                "trusted-but-old:1", 10.0,    // hand-picked winner
                "broken-but-fresh:1", 0.1);

        var analyzer = ImagePruneAnalyzer.builder()
                .scorer(e -> trustScores.getOrDefault(e.imageTag(), 0.0))
                .build();
        var analysis = analyzer.analyze(List.of(trustedOld, brokenFresh));
        assertEquals("trusted-but-old:1", analysis.keepers().get(0).entry().imageTag(),
                "custom scorer should override default recency bias");
    }
}
