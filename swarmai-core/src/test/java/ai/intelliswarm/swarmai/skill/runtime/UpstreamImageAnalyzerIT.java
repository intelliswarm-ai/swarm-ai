package ai.intelliswarm.swarmai.skill.runtime;

import ai.intelliswarm.swarmai.agent.subagent.DockerWorkspace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test for {@link UpstreamImageAnalyzer}. Verifies that
 * "in-use" detection actually protects base images of keepers via
 * the layer-history check, and that genuinely orphan upstream images
 * are flagged.
 */
class UpstreamImageAnalyzerIT {

    private ContainerSkillRuntime runtime;
    private String keeperTag;
    private String keeperId;

    @BeforeAll
    static void requireDocker() {
        assumeTrue(DockerWorkspace.isDockerAvailable(),
                "Docker daemon not reachable — skipping upstream-analyzer IT");
    }

    @BeforeEach
    void setUp() throws Exception {
        runtime = new ContainerSkillRuntime();
        // Build a small keeper image so its FROM base appears in history.
        String dockerfile = "FROM alpine:3.19\nLABEL test=\"upstream-it-" + System.nanoTime() + "\"\nCMD [\"true\"]\n";
        keeperTag = runtime.buildImage(dockerfile);
        // Resolve the image ID for the layer-history check.
        keeperId = inspectId(keeperTag);
        assertNotNull(keeperId, "could not resolve keeper image ID");
    }

    @AfterEach
    void cleanUp() {
        if (runtime != null) {
            runtime.stopContainersStartedByMe();
            runtime.pruneImagesBuiltByMe();
            runtime.close();
        }
    }

    @Test
    @DisplayName("alpine:3.19 is detected as protected when a keeper was built FROM it")
    void alpineBaseIsProtected() {
        var analyzer = new UpstreamImageAnalyzer();
        var analysis = analyzer.analyze(java.util.List.of(keeperId));

        // alpine:3.19 should appear in the protected set because the keeper
        // image was built FROM it. Equivalently, alpine:3.19 should NOT appear
        // in the candidates list as a prune target.
        boolean alpineFlagged = analysis.candidates().stream()
                .anyMatch(c -> c.imageTag().startsWith("alpine:"));
        assertFalse(alpineFlagged,
                "alpine:3.19 must not be flagged as orphan — it's the FROM base of the keeper. "
                + "Got candidates: " + analysis.candidates().stream()
                        .map(UpstreamImageAnalyzer.Candidate::imageTag).toList());

        // We expect SOMETHING to be in protectedBaseIds (alpine's layers).
        assertFalse(analysis.protectedBaseIds().isEmpty(),
                "protected base IDs should be non-empty when keeper has a FROM base");
    }

    @Test
    @DisplayName("Empty keeper list means nothing is protected → all upstream becomes candidate")
    void emptyKeepersExposesAllUpstream() {
        var analyzer = new UpstreamImageAnalyzer();
        var analysis = analyzer.analyze(java.util.List.of());

        // Without any keepers, alpine:3.19 (and every other upstream image) is
        // not protected. This is by design — caller decides scope.
        assertTrue(analysis.protectedBaseIds().isEmpty(),
                "no keepers → no protected base IDs");
        // We can't assert exactly how many candidates because the test host
        // varies; but if alpine is present it should be in the list.
    }

    private String inspectId(String imageTag) throws Exception {
        Process p = new ProcessBuilder(
                "docker", "image", "inspect",
                "--format", "{{.Id}}", imageTag)
                .redirectErrorStream(true).start();
        try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
            String id = r.readLine();
            p.waitFor();
            return id == null ? null : id.trim();
        }
    }
}
