package ai.intelliswarm.swarmai.skill.runtime;

import ai.intelliswarm.swarmai.agent.subagent.DockerWorkspace;
import ai.intelliswarm.swarmai.skill.runtime.ApprovalGates.CallbackApprovalGate;
import ai.intelliswarm.swarmai.skill.runtime.ImageApprovalGate.Decision;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end integration test for the dynamic capability acquisition
 * loop: build an image with an embedded manifest, read the manifest
 * back, materialise BaseTools, smoke-validate them, and exercise one.
 *
 * <p>This is the proof-point that the six slices (approval gate,
 * manifest reader, factory, smoke validator, relevance filter, live
 * registration) wire together correctly against a real Docker daemon.
 *
 * <p>Skipped when no Docker daemon is reachable (matches the
 * {@link ContainerSkillRuntimeIT} convention).
 */
class DynamicCapabilityLoopIT {

    private ContainerSkillRuntime runtime;
    private String builtImageTag;

    /**
     * A capability manifest (raw JSON). Three small alpine-friendly tools
     * declared so we can exercise the full register → smoke-test → call
     * cycle without spinning up a 2 GB Kali image.
     */
    private static final String MANIFEST_JSON = """
        {
          "name": "loop-it-toolbox",
          "version": "1",
          "description": "Tiny self-describing image used by the dynamic-loop IT",
          "tools": [
            {
              "name": "echo_target",
              "description": "Echo back whatever target string is supplied — proves env-var wiring works",
              "triggerWhen": "When you need a quick tool roundtrip with a known input",
              "parameters": [
                {"name": "target", "type": "string", "description": "Anything", "required": true}
              ],
              "entrypoint": "echo \\"hello $TARGET\\"",
              "timeoutSeconds": 30,
              "network": "none"
            },
            {
              "name": "list_etc",
              "description": "List one expected file in /etc inside the image — proves the filesystem matches the manifest",
              "triggerWhen": "When you want to verify image contents",
              "parameters": [
                {"name": "ignored", "type": "string", "description": "ignored", "required": false}
              ],
              "entrypoint": "ls /etc/alpine-release",
              "timeoutSeconds": 15,
              "network": "none"
            }
          ]
        }
        """;

    @BeforeAll
    static void requireDocker() {
        assumeTrue(DockerWorkspace.isDockerAvailable(),
                "Docker daemon not reachable — skipping dynamic-loop IT");
    }

    @BeforeEach
    void setUp() throws Exception {
        runtime = new ContainerSkillRuntime();
        runtime.setSource("dynamic-loop-it");

        // Build an image with the manifest embedded as a base64-encoded LABEL.
        // We include /bin/sh by virtue of using alpine; the manifest entries
        // run via "sh -c <entrypoint>" courtesy of ManifestToolFactory.
        String dockerfile = "FROM alpine:3.19\nCMD [\"true\"]\n";
        String b64 = Base64.getEncoder().encodeToString(MANIFEST_JSON.getBytes());
        Map<String, String> labels = Map.of(ManifestImageReader.MANIFEST_LABEL, b64);
        builtImageTag = runtime.buildImage(dockerfile, labels);
        assertNotNull(builtImageTag, "buildImage must return a tag");
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
    @DisplayName("ManifestImageReader extracts the manifest from a built image's LABEL")
    void readsManifestFromLabel() {
        ManifestImageReader reader = new ManifestImageReader();
        Optional<CapabilityManifest> manifest = reader.readLabel(builtImageTag);
        assertTrue(manifest.isPresent(), "manifest LABEL should be readable");
        assertEquals("loop-it-toolbox", manifest.get().name());
        assertEquals(2, manifest.get().size());
        assertNotNull(manifest.get().find("echo_target"));
        assertNotNull(manifest.get().find("list_etc"));
    }

    @Test
    @DisplayName("ManifestToolFactory materialises tools that actually call the image")
    void materialisedToolExecutesAgainstImage() {
        ManifestImageReader reader = new ManifestImageReader();
        CapabilityManifest manifest = reader.readLabel(builtImageTag).orElseThrow();

        ManifestToolFactory factory = ManifestToolFactory.builder()
                .runtime(runtime)
                .image(builtImageTag)
                .build();
        List<BaseTool> tools = factory.toTools(manifest);
        assertEquals(2, tools.size());

        BaseTool echoTool = tools.stream()
                .filter(t -> "echo_target".equals(t.getFunctionName()))
                .findFirst().orElseThrow();
        Object result = echoTool.execute(Map.of("target", "world"));
        assertNotNull(result);
        assertTrue(result.toString().contains("hello world"),
                "echo tool should produce 'hello world' from env var; got: " + result);
    }

    @Test
    @DisplayName("Smoke validator confirms every declared tool actually works")
    void smokeValidatorAllPass() {
        ManifestImageReader reader = new ManifestImageReader();
        CapabilityManifest manifest = reader.readLabel(builtImageTag).orElseThrow();
        ManifestToolFactory factory = ManifestToolFactory.builder()
                .runtime(runtime)
                .image(builtImageTag)
                .build();

        ManifestSmokeValidator validator = new ManifestSmokeValidator(runtime, factory);
        var results = validator.validate(manifest, Map.of());
        // echo_target needs a "target" sample (matches the default sample);
        // list_etc has only an optional param so the default-params path is empty.
        assertEquals(2, results.size());
        long passed = results.stream().filter(ManifestSmokeValidator.Result::pass).count();
        assertEquals(2, passed, "all declared tools should pass smoke; got: "
                + results.stream().map(ManifestSmokeValidator.Result::summary).toList());
    }

    @Test
    @DisplayName("Smoke validator catches a hallucinated tool whose entrypoint doesn't exist")
    void smokeValidatorCatchesHallucinatedTool() {
        // Hand-build a manifest declaring a tool that won't actually run
        // (entrypoint runs a binary that isn't in alpine).
        CapabilityManifest fake = new CapabilityManifest(
                "fake", "1", "",
                List.of(new CapabilityManifest.Capability(
                        "fake_tool",
                        "Hallucinated tool that doesn't actually exist",
                        "never", "always", "fake", List.of(),
                        List.of(new CapabilityManifest.Parameter(
                                "target", "string", "x", true, null)),
                        "/no/such/binary --target $TARGET",
                        20, "none", null)));

        ManifestToolFactory factory = ManifestToolFactory.builder()
                .runtime(runtime)
                .image(builtImageTag)
                .build();
        ManifestSmokeValidator validator = new ManifestSmokeValidator(runtime, factory);
        var results = validator.validate(fake, Map.of());
        assertEquals(1, results.size());
        assertFalse(results.get(0).pass(),
                "smoke should reject a tool whose entrypoint is missing; result: "
                + results.get(0).summary());
        assertNotNull(results.get(0).failureReason());
    }

    @Test
    @DisplayName("Approval gate fires once per new image build, not for cached re-uses")
    void approvalGateFiresOnceForNewBuild() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        runtime.setApprovalGate(new CallbackApprovalGate(req -> {
            calls.incrementAndGet();
            return Decision.APPROVE;
        }));

        // First build of a different Dockerfile → gate should fire.
        String df = "FROM alpine:3.19\nLABEL fresh=\"" + System.nanoTime() + "\"\nCMD [\"true\"]\n";
        String tag = runtime.buildImage(df);
        assertNotNull(tag);
        assertEquals(1, calls.get(), "gate should fire once for a brand-new image build");

        // Second build of the SAME Dockerfile → cached, no rebuild, no gate.
        runtime.buildImage(df);
        assertEquals(1, calls.get(), "cached image should not re-trigger the gate");

        // Cleanup the extra image we built.
        runtime.pruneImagesBuiltByMe();
    }

    @Test
    @DisplayName("Approval gate DENY blocks a build")
    void approvalGateDenyBlocksBuild() {
        runtime.setApprovalGate(new CallbackApprovalGate(req -> Decision.DENY));
        String df = "FROM alpine:3.19\nLABEL deny=\"" + System.nanoTime() + "\"\nCMD [\"true\"]\n";
        ImageApprovalGate.ImageApprovalDeniedException ex = assertThrows(
                ImageApprovalGate.ImageApprovalDeniedException.class,
                () -> runtime.buildImage(df));
        assertTrue(ex.getMessage().contains("denied"));
    }

    @Test
    @DisplayName("Approval request carries the FROM image extracted from the Dockerfile")
    void approvalRequestCarriesBaseImage() {
        AtomicInteger seen = new AtomicInteger();
        runtime.setApprovalGate(new CallbackApprovalGate(req -> {
            assertEquals("alpine:3.19", req.baseImage(),
                    "the gate should see the FROM image, not the resolved build tag");
            assertTrue(req.isBuild());
            assertEquals("dynamic-loop-it", req.source());
            seen.incrementAndGet();
            return Decision.APPROVE;
        }));
        String df = "FROM alpine:3.19\nLABEL seen=\"" + System.nanoTime() + "\"\nCMD [\"true\"]\n";
        try {
            runtime.buildImage(df);
        } catch (Exception e) {
            fail("build should have proceeded after approval", e);
        }
        assertEquals(1, seen.get());
        runtime.pruneImagesBuiltByMe();
    }
}
