package ai.intelliswarm.swarmai.skill.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the pure helpers in {@link UpstreamImageAnalyzer}.
 * The full analyze() path needs Docker, so it lives in
 * {@link UpstreamImageAnalyzerIT}.
 */
class UpstreamImageAnalyzerTest {

    @Test
    @DisplayName("parseSizeToBytes handles GB / MB / kB / B suffixes")
    void parseSize() {
        assertEquals(1_200_000_000L, UpstreamImageAnalyzer.parseSizeToBytes("1.2GB"));
        assertEquals(456_000_000L, UpstreamImageAnalyzer.parseSizeToBytes("456MB"));
        assertEquals(78_000L, UpstreamImageAnalyzer.parseSizeToBytes("78kB"));
        assertEquals(78_000L, UpstreamImageAnalyzer.parseSizeToBytes("78KB"));
        assertEquals(123L, UpstreamImageAnalyzer.parseSizeToBytes("123B"));
    }

    @Test
    @DisplayName("parseSizeToBytes returns 0 on garbage input")
    void parseSizeGarbage() {
        assertEquals(0L, UpstreamImageAnalyzer.parseSizeToBytes(null));
        assertEquals(0L, UpstreamImageAnalyzer.parseSizeToBytes(""));
        assertEquals(0L, UpstreamImageAnalyzer.parseSizeToBytes("not a size"));
        assertEquals(0L, UpstreamImageAnalyzer.parseSizeToBytes("xyzMB"));
    }

    @Test
    @DisplayName("Empty kept-image list returns empty analysis")
    void analyzeEmptyKept() {
        var analysis = new UpstreamImageAnalyzer().analyze(java.util.List.of());
        // With no kept images, all upstream is fair game — but the test
        // only verifies the call shape; actual Docker scan covered by IT.
        assertNotNull(analysis);
        assertNotNull(analysis.candidates());
        assertNotNull(analysis.protectedBaseIds());
    }

    @Test
    @DisplayName("Analysis.bytesReclaimable sums candidate sizes")
    void bytesReclaimableMath() {
        var a = new UpstreamImageAnalyzer.Analysis(
                java.util.List.of(
                        new UpstreamImageAnalyzer.Candidate("a:1", "sha256:a", 100_000_000L, "x"),
                        new UpstreamImageAnalyzer.Candidate("b:1", "sha256:b", 250_000_000L, "y")),
                java.util.Set.of("sha256:base1"));
        assertEquals(350_000_000L, a.bytesReclaimable());
    }

    @Test
    @DisplayName("execute() with dryRun=true reports candidates without docker calls")
    void executeDryRun() {
        var analysis = new UpstreamImageAnalyzer.Analysis(
                java.util.List.of(
                        new UpstreamImageAnalyzer.Candidate("ghost:1", "sha256:x", 1_000_000L, "no refs")),
                java.util.Set.of());
        // Dry-run never invokes docker, so this works without a daemon.
        int removed = new UpstreamImageAnalyzer().execute(analysis, true);
        assertEquals(1, removed);
    }
}
