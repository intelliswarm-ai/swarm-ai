package ai.intelliswarm.swarmai.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link SkillType#CONTAINER} skills survive a full
 * {@link SkillRegistry#save} → {@link SkillRegistry#load} round-trip with
 * their {@link ContainerSkillSpec} (including the inline Dockerfile)
 * intact. Without this, persisted CONTAINER skills couldn't be reused
 * across process restarts and every framework restart would force the
 * agent to re-generate them.
 */
class ContainerSkillRegistryRoundtripTest {

    @Test
    @DisplayName("CONTAINER skill with Dockerfile survives save → load round-trip")
    void dockerfileSkillRoundtrip(@TempDir Path tmp) {
        SkillDefinition def = new SkillDefinition();
        def.setName("port_scan_skill");
        def.setDescription("Scans a target host for open ports using nmap inside a container");
        def.setType(SkillType.CONTAINER);
        def.setCategory("security");
        def.setTags(java.util.List.of("network", "port-scan", "nmap"));
        def.setContainerSpec(ContainerSkillSpec.builderFromDockerfile(
                "FROM alpine:3.19\nRUN apk add --no-cache nmap nmap-scripts\nENTRYPOINT [\"sh\", \"-c\", \"nmap -F $TARGET\"]\n")
                .envFromParam("target", "TARGET")
                .network("bridge")
                .memory("256m")
                .timeout(Duration.ofMinutes(5))
                .build());
        GeneratedSkill original = new GeneratedSkill(def);
        original.setStatus(SkillStatus.VALIDATED);

        SkillRegistry r1 = new SkillRegistry();
        r1.register(original);
        assertEquals(1, r1.size());

        try {
            r1.save(tmp);
        } catch (java.io.IOException e) {
            fail("save threw: " + e.getMessage());
        }
        Path skillDir = tmp.resolve("port_scan_skill");
        assertTrue(java.nio.file.Files.isDirectory(skillDir),
                "skill directory should exist: " + skillDir);
        assertTrue(java.nio.file.Files.exists(skillDir.resolve("SKILL.md")),
                "SKILL.md should exist on disk");

        SkillRegistry r2 = new SkillRegistry();
        int loaded = r2.load(tmp);
        assertEquals(1, loaded, "should load exactly 1 skill");
        assertEquals(1, r2.size());

        GeneratedSkill restored = r2.getActiveSkills().get(0);
        assertEquals("port_scan_skill", restored.getName());
        assertEquals(SkillType.CONTAINER, restored.getDefinition().getType());

        ContainerSkillSpec spec = restored.getDefinition().getContainerSpec();
        assertNotNull(spec, "container spec must survive the round-trip");
        assertTrue(spec.hasDockerfile(), "Dockerfile must survive the round-trip");
        assertTrue(spec.dockerfile().contains("FROM alpine:3.19"));
        assertTrue(spec.dockerfile().contains("apk add --no-cache nmap"));
        assertEquals("TARGET", spec.envFromParams().get("target"));
        assertEquals("bridge", spec.network());
        assertEquals("256m", spec.memory());
        assertEquals(Duration.ofMinutes(5), spec.timeout());
    }

    @Test
    @DisplayName("CONTAINER skill with pre-built image (no Dockerfile) round-trips")
    void prebuiltImageSkillRoundtrip(@TempDir Path tmp) {
        SkillDefinition def = new SkillDefinition();
        def.setName("nmap_via_prebuilt");
        def.setDescription("Uses the instrumentisto/nmap pre-built image");
        def.setType(SkillType.CONTAINER);
        def.setCategory("security");
        def.setContainerSpec(ContainerSkillSpec.builder("instrumentisto/nmap:latest")
                .entrypoint("sh")
                .command("-c", "nmap -F $TARGET")
                .envFromParam("target", "TARGET")
                .network("bridge")
                .timeout(Duration.ofMinutes(2))
                .build());
        GeneratedSkill original = new GeneratedSkill(def);
        original.setStatus(SkillStatus.VALIDATED);

        SkillRegistry r1 = new SkillRegistry();
        r1.register(original);
        try { r1.save(tmp); } catch (java.io.IOException e) { fail(e.getMessage()); }

        SkillRegistry r2 = new SkillRegistry();
        int loaded = r2.load(tmp);
        assertEquals(1, loaded);

        ContainerSkillSpec spec = r2.getActiveSkills().get(0).getDefinition().getContainerSpec();
        assertNotNull(spec);
        assertTrue(spec.hasImage());
        assertEquals("instrumentisto/nmap:latest", spec.image());
        assertFalse(spec.hasDockerfile(), "no Dockerfile means hasDockerfile=false after round-trip");
    }

    @Test
    @DisplayName("findSimilar matches a CONTAINER skill against a related gap description")
    void findSimilarMatchesContainerSkill(@TempDir Path tmp) {
        SkillDefinition def = new SkillDefinition();
        def.setName("network_port_scanner");
        def.setDescription("Scans hosts for open ports, identifies services, returns a port/service report");
        def.setType(SkillType.CONTAINER);
        def.setCategory("security");
        def.setTags(java.util.List.of("network", "port-scan", "scanner", "host-discovery"));
        def.setContainerSpec(ContainerSkillSpec.builderFromDockerfile(
                "FROM alpine:3.19\nRUN apk add --no-cache nmap\nENTRYPOINT [\"sh\", \"-c\", \"nmap $TARGET\"]\n")
                .envFromParam("target", "TARGET")
                .network("bridge")
                .timeout(Duration.ofMinutes(5))
                .build());
        GeneratedSkill skill = new GeneratedSkill(def);
        skill.setStatus(SkillStatus.VALIDATED);

        SkillRegistry registry = new SkillRegistry();
        registry.register(skill);

        java.util.List<SkillRegistry.SimilarSkill> matches = registry.findSimilar(
                "Need a way to scan hosts for open ports and identify services",
                0.20);
        assertFalse(matches.isEmpty(),
                "registry must surface the registered CONTAINER skill for a related gap");
        assertEquals("network_port_scanner", matches.get(0).skill().getName());
    }
}
