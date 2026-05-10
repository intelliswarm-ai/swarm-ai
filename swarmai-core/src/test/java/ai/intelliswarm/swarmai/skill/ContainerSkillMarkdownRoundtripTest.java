package ai.intelliswarm.swarmai.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip tests: SKILL.md text ⇆ {@link SkillDefinition} containing a
 * {@link ContainerSkillSpec}. Validates that:
 * <ul>
 *   <li>{@code fromSkillMd} parses {@code container:} frontmatter into a spec</li>
 *   <li>{@code fromSkillMd} pulls inline Dockerfile from a {@code ## Dockerfile} body section</li>
 *   <li>{@code toSkillMd} re-serialises both</li>
 *   <li>parse → serialise → parse is stable</li>
 * </ul>
 *
 * No Docker required — pure text → object → text.
 */
class ContainerSkillMarkdownRoundtripTest {

    @Nested
    @DisplayName("frontmatter parsing — container: block")
    class FrontmatterParsing {

        @Test
        @DisplayName("pre-built image with command + env mapping + network=none")
        void preBuiltImageMinimal() {
            String md = """
                    ---
                    name: alpine_echo
                    description: Echoes a target string back via env-mapped param
                    type: CONTAINER
                    category: generated
                    tags: [test, container]
                    container:
                      image: alpine:3.19
                      command: [sh, -c, "echo $TARGET"]
                      inputs:
                        target: TARGET
                      network: none
                      timeoutSeconds: 30
                    ---

                    # Alpine Echo
                    """;
            SkillDefinition def = SkillDefinition.fromSkillMd(md);
            assertEquals(SkillType.CONTAINER, def.getType());
            assertEquals("alpine_echo", def.getName());

            ContainerSkillSpec spec = def.getContainerSpec();
            assertNotNull(spec, "container: block must produce a ContainerSkillSpec");
            assertTrue(spec.hasImage());
            assertEquals("alpine:3.19", spec.image());
            assertEquals(java.util.List.of("sh", "-c", "\"echo $TARGET\""), spec.command());
            assertEquals("TARGET", spec.envFromParams().get("target"));
            assertEquals("none", spec.network());
            assertEquals(Duration.ofSeconds(30), spec.timeout());
        }

        @Test
        @DisplayName("network=bridge + memory cap")
        void bridgeNetworkAndMemory() {
            String md = """
                    ---
                    name: net_scanner
                    description: Scans a target host for open ports
                    type: CONTAINER
                    category: security
                    container:
                      image: instrumentisto/nmap:latest
                      command: [-sn, $TARGET]
                      inputs:
                        target: TARGET
                      network: bridge
                      memory: 512m
                      timeoutSeconds: 120
                    ---
                    """;
            SkillDefinition def = SkillDefinition.fromSkillMd(md);
            ContainerSkillSpec spec = def.getContainerSpec();
            assertEquals("bridge", spec.network());
            assertEquals("512m", spec.memory());
            assertEquals(Duration.ofSeconds(120), spec.timeout());
        }
    }

    @Nested
    @DisplayName("body parsing — ## Dockerfile section")
    class BodyDockerfile {

        @Test
        @DisplayName("dockerfile-only spec (no image: in frontmatter)")
        void dockerfileOnly() {
            String md = """
                    ---
                    name: jq_skill
                    description: A skill that uses jq to transform JSON inputs
                    type: CONTAINER
                    category: data-io
                    container:
                      command: [.answer]
                      inputs:
                        input: INPUT
                      network: none
                      timeoutSeconds: 20
                    ---

                    # jq Skill

                    Uses jq inside a custom alpine image.

                    ## Dockerfile
                    ```dockerfile
                    FROM alpine:3.19
                    RUN apk add --no-cache jq
                    ENTRYPOINT ["jq"]
                    ```
                    """;
            SkillDefinition def = SkillDefinition.fromSkillMd(md);
            ContainerSkillSpec spec = def.getContainerSpec();
            assertNotNull(spec);
            assertTrue(spec.hasDockerfile(), "## Dockerfile body section must populate dockerfile field");
            assertTrue(spec.dockerfile().contains("apk add --no-cache jq"));
            assertEquals(java.util.List.of(".answer"), spec.command());
        }

        @Test
        @DisplayName("dockerfile coexists with image: (image is the build target tag)")
        void dockerfilePlusImage() {
            String md = """
                    ---
                    name: combo
                    description: A skill where image: is the build-target tag
                    type: CONTAINER
                    category: generated
                    container:
                      image: my-built:v1
                      network: none
                      timeoutSeconds: 30
                    ---

                    # Combo

                    ## Dockerfile
                    ```dockerfile
                    FROM alpine:3.19
                    RUN echo combo > /tmp/x
                    ```
                    """;
            SkillDefinition def = SkillDefinition.fromSkillMd(md);
            ContainerSkillSpec spec = def.getContainerSpec();
            assertNotNull(spec);
            assertTrue(spec.hasImage());
            assertEquals("my-built:v1", spec.image());
            assertTrue(spec.hasDockerfile());
            assertTrue(spec.dockerfile().contains("FROM alpine:3.19"));
        }
    }

    @Nested
    @DisplayName("toSkillMd serialisation")
    class Serialisation {

        @Test
        @DisplayName("pre-built image spec serialises to a parseable container: block")
        void serialiseImageOnly() {
            SkillDefinition def = new SkillDefinition();
            def.setName("alpine_echo");
            def.setDescription("Echoes a target string");
            def.setType(SkillType.CONTAINER);
            def.setCategory("generated");
            def.setContainerSpec(ContainerSkillSpec.builder("alpine:3.19")
                    .command("sh", "-c", "echo $TARGET")
                    .envFromParam("target", "TARGET")
                    .timeout(Duration.ofSeconds(30))
                    .build());

            String md = def.toSkillMd();
            assertTrue(md.contains("type: CONTAINER"), md);
            assertTrue(md.contains("container:"), md);
            assertTrue(md.contains("image: alpine:3.19"), md);
            assertTrue(md.contains("network: none"), md);
            assertTrue(md.contains("timeoutSeconds: 30"), md);
            assertTrue(md.contains("target: TARGET"), md);
        }

        @Test
        @DisplayName("inline-Dockerfile spec serialises a ## Dockerfile body section")
        void serialiseDockerfile() {
            String df = "FROM alpine:3.19\nRUN apk add --no-cache jq\nENTRYPOINT [\"jq\"]";
            SkillDefinition def = new SkillDefinition();
            def.setName("jq_skill");
            def.setDescription("Uses jq inside a custom alpine image");
            def.setType(SkillType.CONTAINER);
            def.setCategory("data-io");
            def.setContainerSpec(ContainerSkillSpec.builderFromDockerfile(df)
                    .envFromParam("input", "INPUT")
                    .timeout(Duration.ofSeconds(20))
                    .build());

            String md = def.toSkillMd();
            assertTrue(md.contains("## Dockerfile"), md);
            assertTrue(md.contains("```dockerfile"), md);
            assertTrue(md.contains("apk add --no-cache jq"), md);
        }
    }

    @Nested
    @DisplayName("full round-trip stability")
    class RoundTrip {

        @Test
        @DisplayName("parse → toSkillMd → parse yields the same spec fields")
        void roundTripStable() {
            String df = "FROM alpine:3.19\nRUN apk add --no-cache curl\n";
            SkillDefinition original = new SkillDefinition();
            original.setName("curl_get");
            original.setDescription("Fetches a URL with curl and returns the body");
            original.setType(SkillType.CONTAINER);
            original.setCategory("web");
            original.setContainerSpec(ContainerSkillSpec.builderFromDockerfile(df)
                    .command("curl", "-sSL", "$URL")
                    .envFromParam("url", "URL")
                    .network("bridge")
                    .memory("128m")
                    .timeout(Duration.ofSeconds(45))
                    .build());

            String md1 = original.toSkillMd();
            SkillDefinition reparsed = SkillDefinition.fromSkillMd(md1);
            ContainerSkillSpec spec = reparsed.getContainerSpec();

            assertEquals(SkillType.CONTAINER, reparsed.getType());
            assertNotNull(spec);
            assertTrue(spec.hasDockerfile(), "Dockerfile must survive the round-trip");
            assertTrue(spec.dockerfile().contains("apk add --no-cache curl"));
            assertEquals("URL", spec.envFromParams().get("url"));
            assertEquals("bridge", spec.network());
            assertEquals("128m", spec.memory());
            assertEquals(Duration.ofSeconds(45), spec.timeout());

            // Second round-trip must produce identical text.
            String md2 = reparsed.toSkillMd();
            assertEquals(md1, md2, "round-trip must be idempotent");
        }
    }
}
