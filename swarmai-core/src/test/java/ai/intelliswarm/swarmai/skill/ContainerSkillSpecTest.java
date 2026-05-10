package ai.intelliswarm.swarmai.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ContainerSkillSpec} — no Docker required.
 * Validates the schema contract (image required, defaults applied, immutability).
 */
class ContainerSkillSpecTest {

    @Test
    @DisplayName("either image or dockerfile must be provided — both null/blank rejected")
    void imageOrDockerfileRequired() {
        assertThrows(IllegalArgumentException.class,
                () -> new ContainerSkillSpec(null, null, null, List.of(), Map.of(), null, null, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new ContainerSkillSpec("", "", null, List.of(), Map.of(), null, null, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new ContainerSkillSpec("   ", "  ", null, List.of(), Map.of(), null, null, Duration.ofSeconds(1)));
    }

    @Test
    @DisplayName("dockerfile alone is sufficient — image becomes empty placeholder")
    void dockerfileAloneIsSufficient() {
        ContainerSkillSpec spec = ContainerSkillSpec.ofDockerfile(
                "FROM alpine:3.19\nRUN apk add --no-cache curl\n");
        assertTrue(spec.hasDockerfile());
        assertFalse(spec.hasImage());
    }

    @Test
    @DisplayName("image alone is sufficient — dockerfile becomes empty placeholder")
    void imageAloneIsSufficient() {
        ContainerSkillSpec spec = ContainerSkillSpec.of("alpine:3.19");
        assertTrue(spec.hasImage());
        assertFalse(spec.hasDockerfile());
    }

    @Test
    @DisplayName("timeout must be positive")
    void timeoutMustBePositive() {
        assertThrows(IllegalArgumentException.class,
                () -> new ContainerSkillSpec("alpine:3.19", null, null, List.of(), Map.of(), null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ContainerSkillSpec("alpine:3.19", null, null, List.of(), Map.of(), null, null, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new ContainerSkillSpec("alpine:3.19", null, null, List.of(), Map.of(), null, null, Duration.ofSeconds(-1)));
    }

    @Test
    @DisplayName("of(image) applies safe defaults")
    void ofAppliesDefaults() {
        ContainerSkillSpec spec = ContainerSkillSpec.of("alpine:3.19");
        assertEquals("alpine:3.19", spec.image());
        assertTrue(spec.command().isEmpty());
        assertTrue(spec.envFromParams().isEmpty());
        assertEquals(ContainerSkillSpec.DEFAULT_NETWORK, spec.network());
        assertEquals("none", spec.network(), "default network must be 'none' for safety");
        assertEquals(ContainerSkillSpec.DEFAULT_TIMEOUT, spec.timeout());
    }

    @Test
    @DisplayName("null command/envMap/network become safe defaults, not NPE")
    void nullsBecomeDefaults() {
        ContainerSkillSpec spec = new ContainerSkillSpec(
                "alpine:3.19", null, null, null, null, null, null, Duration.ofSeconds(5));
        assertEquals(List.of(), spec.command());
        assertEquals(Map.of(), spec.envFromParams());
        assertEquals("none", spec.network());
    }

    @Test
    @DisplayName("builderFromDockerfile composes a Dockerfile-backed spec (trimming trailing whitespace)")
    void builderFromDockerfile() {
        String df = "FROM alpine:3.19\nRUN apk add --no-cache jq\nENTRYPOINT [\"jq\"]\n";
        ContainerSkillSpec spec = ContainerSkillSpec.builderFromDockerfile(df)
                .command(".answer")
                .envFromParam("input", "INPUT")
                .timeout(Duration.ofSeconds(20))
                .build();
        assertTrue(spec.hasDockerfile());
        // Trim is part of the contract — keeps SKILL.md round-trips stable.
        assertEquals(df.trim(), spec.dockerfile());
        assertFalse(spec.hasImage());
        assertEquals(List.of(".answer"), spec.command());
        assertEquals("INPUT", spec.envFromParams().get("input"));
    }

    @Test
    @DisplayName("builder fluently composes envFromParams")
    void builderComposesEnv() {
        ContainerSkillSpec spec = ContainerSkillSpec.builder("instrumentisto/nmap:latest")
                .command("-A", "-T4", "$TARGET")
                .envFromParam("target", "TARGET")
                .envFromParam("ports", "PORTS")
                .network("bridge")
                .memory("512m")
                .timeout(Duration.ofMinutes(2))
                .build();

        assertEquals("instrumentisto/nmap:latest", spec.image());
        assertEquals(List.of("-A", "-T4", "$TARGET"), spec.command());
        assertEquals("TARGET", spec.envFromParams().get("target"));
        assertEquals("PORTS", spec.envFromParams().get("ports"));
        assertEquals("bridge", spec.network());
        assertEquals("512m", spec.memory());
        assertEquals(Duration.ofMinutes(2), spec.timeout());
    }

    @Test
    @DisplayName("envFromParams is immutable — caller can't mutate after build")
    void envMapImmutable() {
        ContainerSkillSpec spec = ContainerSkillSpec.builder("alpine:3.19")
                .envFromParam("a", "A")
                .build();
        assertThrows(UnsupportedOperationException.class,
                () -> spec.envFromParams().put("b", "B"));
    }

    @Test
    @DisplayName("command list is immutable — caller can't mutate after build")
    void commandListImmutable() {
        ContainerSkillSpec spec = ContainerSkillSpec.builder("alpine:3.19")
                .command("echo", "hi")
                .build();
        assertThrows(UnsupportedOperationException.class,
                () -> spec.command().add("oops"));
    }
}
