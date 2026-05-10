package ai.intelliswarm.swarmai.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SkillValidator}'s CONTAINER-type validation arm
 * (introduced in slice 5). No Docker required — pure structural checks
 * on the {@link ContainerSkillSpec} carried by the skill.
 */
class SkillValidatorContainerTest {

    private final SkillValidator validator = new SkillValidator(true); // permissive on IT

    private GeneratedSkill containerSkill(ContainerSkillSpec spec) {
        SkillDefinition def = new SkillDefinition();
        def.setName("test_container_skill");
        def.setDescription("a test container skill for validator coverage");
        def.setType(SkillType.CONTAINER);
        def.setContainerSpec(spec);
        return new GeneratedSkill(def);
    }

    @Test
    @DisplayName("valid pre-built image spec passes validation")
    void prebuiltImagePasses() {
        var spec = ContainerSkillSpec.builder("alpine:3.19")
                .timeout(Duration.ofSeconds(30)).build();
        var result = validator.validate(containerSkill(spec));
        assertTrue(result.passed(), "errors: " + result.errorsAsString());
    }

    @Test
    @DisplayName("valid Dockerfile spec passes validation")
    void dockerfilePasses() {
        var spec = ContainerSkillSpec.builderFromDockerfile(
                        "FROM alpine:3.19\nRUN apk add --no-cache nmap\n")
                .timeout(Duration.ofSeconds(30)).build();
        var result = validator.validate(containerSkill(spec));
        assertTrue(result.passed(), "errors: " + result.errorsAsString());
    }

    @Test
    @DisplayName("missing FROM directive in Dockerfile is rejected")
    void rejectsDockerfileWithoutFrom() {
        // Spec accepts a non-empty dockerfile string; validator's job is to
        // ensure it actually contains a FROM directive.
        var spec = ContainerSkillSpec.builderFromDockerfile(
                        "RUN echo hi\nCMD [\"echo\"]\n")
                .timeout(Duration.ofSeconds(30)).build();
        var result = validator.validate(containerSkill(spec));
        assertFalse(result.passed());
        assertTrue(result.errorsAsString().contains("FROM directive"));
    }

    @Test
    @DisplayName("USER root in Dockerfile is rejected")
    void rejectsUserRoot() {
        var spec = ContainerSkillSpec.builderFromDockerfile(
                        "FROM alpine:3.19\nUSER root\nRUN apk add --no-cache nmap\n")
                .timeout(Duration.ofSeconds(30)).build();
        var result = validator.validate(containerSkill(spec));
        assertFalse(result.passed());
        assertTrue(result.errorsAsString().contains("USER root"));
    }

    @Test
    @DisplayName("--privileged in Dockerfile is rejected")
    void rejectsPrivilegedFlag() {
        var spec = ContainerSkillSpec.builderFromDockerfile(
                        "FROM alpine:3.19\n# RUN docker --privileged ...\nRUN apk add --no-cache nmap\n")
                .timeout(Duration.ofSeconds(30)).build();
        var result = validator.validate(containerSkill(spec));
        assertFalse(result.passed());
        assertTrue(result.errorsAsString().toLowerCase().contains("privileged"));
    }

    @Test
    @DisplayName("missing container spec on a CONTAINER-typed skill is rejected")
    void rejectsMissingSpec() {
        SkillDefinition def = new SkillDefinition();
        def.setName("broken_skill");
        def.setDescription("a CONTAINER skill that forgot its spec");
        def.setType(SkillType.CONTAINER);
        // intentionally NOT setContainerSpec(...)
        var result = validator.validate(new GeneratedSkill(def));
        assertFalse(result.passed());
        assertTrue(result.errorsAsString().contains("no container spec"));
    }

    @Test
    @DisplayName("FROM after a comment block is parsed correctly")
    void fromAfterComment() {
        var spec = ContainerSkillSpec.builderFromDockerfile(
                        "# Build a slim alpine image\n# with curl preinstalled\n\nFROM alpine:3.19\nRUN apk add --no-cache curl\n")
                .timeout(Duration.ofSeconds(30)).build();
        var result = validator.validate(containerSkill(spec));
        assertTrue(result.passed(), "errors: " + result.errorsAsString());
    }
}
