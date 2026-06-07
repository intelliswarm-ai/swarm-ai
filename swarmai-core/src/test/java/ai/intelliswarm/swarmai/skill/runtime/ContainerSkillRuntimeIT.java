package ai.intelliswarm.swarmai.skill.runtime;

import ai.intelliswarm.swarmai.agent.subagent.DockerWorkspace;
import ai.intelliswarm.swarmai.skill.ContainerSkillSpec;
import ai.intelliswarm.swarmai.skill.GeneratedSkill;
import ai.intelliswarm.swarmai.skill.SkillDefinition;
import ai.intelliswarm.swarmai.skill.SkillType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration tests for {@link ContainerSkillRuntime} that exercise the
 * full Docker pipeline. Skipped automatically when no Docker daemon is
 * reachable (matches the gating pattern used by {@code DockerWorkspaceTest}).
 *
 * <h2>What's covered</h2>
 * <ul>
 *   <li>End-to-end {@code execute()} with parameters mapped to env vars</li>
 *   <li>Image pull idempotence (second pull is a no-op)</li>
 *   <li>Inline-Dockerfile build → run → output</li>
 *   <li>Lifecycle tracking — {@code trackedImages()}, {@code trackedContainers()}</li>
 *   <li>{@code pruneImagesBuiltByMe()} only removes images we built</li>
 *   <li>{@code GeneratedSkill.execute()} dispatch path for SkillType.CONTAINER</li>
 * </ul>
 */
class ContainerSkillRuntimeIT {

    private ContainerSkillRuntime runtime;

    @BeforeAll
    static void requireDocker() {
        assumeTrue(DockerWorkspace.isDockerAvailable(),
                "Docker daemon not reachable — skipping container-runtime integration tests");
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        runtime = new ContainerSkillRuntime();
    }

    @AfterEach
    void cleanUp() {
        runtime.stopContainersStartedByMe();
        runtime.pruneImagesBuiltByMe();
        runtime.close();
    }

    @Nested
    @DisplayName("execute() with pre-built image")
    class ExecuteWithImage {

        @Test
        @DisplayName("alpine echoes a parameter via env mapping")
        void echoesEnvParam() throws Exception {
            runtime.pullImage("alpine:3.19");

            ContainerSkillSpec spec = ContainerSkillSpec.builder("alpine:3.19")
                    .command("sh", "-c", "echo hello $TARGET")
                    .envFromParam("target", "TARGET")
                    .timeout(Duration.ofSeconds(15))
                    .build();

            ContainerSkillRuntime.ExecResult r = runtime.execute(spec, Map.of("target", "world"));

            assertTrue(r.ok(), "expected exit 0, got " + r.exitCode() + " stderr=" + r.stderr());
            assertEquals("hello world", r.stdout().trim());
        }

        @Test
        @DisplayName("non-zero exit surfaces in result, not as an exception")
        void nonZeroExit() throws Exception {
            runtime.pullImage("alpine:3.19");

            ContainerSkillSpec spec = ContainerSkillSpec.builder("alpine:3.19")
                    .command("sh", "-c", "echo failing 1>&2; exit 7")
                    .timeout(Duration.ofSeconds(10))
                    .build();

            ContainerSkillRuntime.ExecResult r = runtime.execute(spec, Map.of());
            assertFalse(r.ok());
            assertEquals(7, r.exitCode());
            assertTrue(r.stderr().contains("failing"));
        }

        @Test
        @DisplayName("timeout kills runaway container")
        void timeoutKillsRunaway() throws Exception {
            runtime.pullImage("alpine:3.19");

            ContainerSkillSpec spec = ContainerSkillSpec.builder("alpine:3.19")
                    .command("sh", "-c", "sleep 30")
                    .timeout(Duration.ofSeconds(2))
                    .build();

            long start = System.currentTimeMillis();
            ContainerSkillRuntime.ExecResult r = runtime.execute(spec, Map.of());
            long elapsed = System.currentTimeMillis() - start;

            assertFalse(r.ok(), "timed-out container must report non-zero exit");
            assertTrue(elapsed < 8_000, "must kill within reasonable time, took " + elapsed + "ms");
            assertTrue(r.stderr().toLowerCase().contains("timeout"),
                    "stderr should mention the timeout, got: " + r.stderr());
        }
    }

    @Nested
    @DisplayName("buildImage(): inline Dockerfile build")
    class BuildImage {

        @Test
        @DisplayName("builds, runs, and tags the image with the swarmai-skill- prefix")
        void buildAndRun() throws Exception {
            String dockerfile = """
                    FROM alpine:3.19
                    RUN apk add --no-cache jq
                    ENTRYPOINT ["sh", "-c", "echo '{\\"answer\\": '$INPUT'}' | jq -c '.'"]
                    """;
            String tag = runtime.buildImage(dockerfile);
            assertTrue(tag.startsWith(ContainerSkillRuntime.BUILT_IMAGE_PREFIX),
                    "built tag should be namespaced for cleanup safety: " + tag);
            assertTrue(runtime.trackedImages().contains(tag),
                    "runtime must track every image it builds");

            ContainerSkillSpec spec = ContainerSkillSpec.builder(tag)
                    .envFromParam("input", "INPUT")
                    .timeout(Duration.ofSeconds(30))
                    .build();
            ContainerSkillRuntime.ExecResult r = runtime.execute(spec, Map.of("input", 42));
            assertTrue(r.ok(), "exit=" + r.exitCode() + " stderr=" + r.stderr());
            assertEquals("{\"answer\":42}", r.stdout().trim());
        }

        @Test
        @DisplayName("re-building the same Dockerfile is idempotent (deterministic tag)")
        void reBuildIsIdempotent() throws Exception {
            String dockerfile = "FROM alpine:3.19\nLABEL test=idempotent\n";
            String firstTag = runtime.buildImage(dockerfile);
            String secondTag = runtime.buildImage(dockerfile);
            assertEquals(firstTag, secondTag,
                    "same Dockerfile content must produce the same tag");
        }
    }

    @Nested
    @DisplayName("lifecycle: tracking + cleanup")
    class Lifecycle {

        @Test
        @DisplayName("pruneImagesBuiltByMe() removes only what this runtime built")
        void pruneOnlyOurs() throws Exception {
            String dockerfile = "FROM alpine:3.19\nLABEL test=prune-target\n";
            String tag = runtime.buildImage(dockerfile);
            assertTrue(runtime.trackedImages().contains(tag));

            List<String> removed = runtime.pruneImagesBuiltByMe();
            assertTrue(removed.contains(tag), "expected " + tag + " in " + removed);
            assertTrue(runtime.trackedImages().isEmpty(),
                    "tracked set should be empty after prune");
        }

        @Test
        @DisplayName("trackedContainers grows during execute() and shrinks after exit")
        void containerTrackingFollowsLifecycle() throws Exception {
            runtime.pullImage("alpine:3.19");
            ContainerSkillSpec spec = ContainerSkillSpec.builder("alpine:3.19")
                    .command("echo", "ok")
                    .timeout(Duration.ofSeconds(5))
                    .build();

            ContainerSkillRuntime.ExecResult r = runtime.execute(spec, Map.of());
            assertTrue(r.ok());
            // After --rm exits, tracking drops the entry.
            assertTrue(runtime.trackedContainers().isEmpty(),
                    "container set should be empty once the process exits");
        }
    }

    @Nested
    @DisplayName("GeneratedSkill dispatch — SkillType.CONTAINER end-to-end")
    class GeneratedSkillDispatch {

        @Test
        @DisplayName("a CONTAINER skill executes through GeneratedSkill.execute()")
        void containerSkillDispatchedThroughGeneratedSkill() throws Exception {
            runtime.pullImage("alpine:3.19");

            SkillDefinition def = new SkillDefinition();
            def.setName("greet_via_container");
            def.setDescription("test skill that runs alpine to echo a greeting via env");
            def.setType(SkillType.CONTAINER);
            def.setContainerSpec(ContainerSkillSpec.builder("alpine:3.19")
                    .command("sh", "-c", "echo \"hi $WHO\"")
                    .envFromParam("who", "WHO")
                    .timeout(Duration.ofSeconds(15))
                    .build());

            GeneratedSkill skill = new GeneratedSkill(def);
            Object out = skill.execute(Map.of("who", "swarmai"));
            assertEquals("hi swarmai", out.toString().trim(),
                    "GeneratedSkill must dispatch CONTAINER skills via executeContainer()");
        }

        @Test
        @DisplayName("CONTAINER skill with no spec returns a clean error string")
        void missingSpecReturnsErrorString() {
            SkillDefinition def = new SkillDefinition();
            def.setName("broken_container_skill");
            def.setDescription("a CONTAINER-typed skill that forgot its containerSpec");
            def.setType(SkillType.CONTAINER);
            // intentionally NOT calling setContainerSpec(...)
            GeneratedSkill skill = new GeneratedSkill(def);

            Object out = skill.execute(Map.of());
            assertNotNull(out);
            assertTrue(out.toString().startsWith("Error:"),
                    "missing spec must return a clean error, not an exception: " + out);
        }
    }
}
