package ai.intelliswarm.swarmai.skill.disclosure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SkillLoaderTest {

    @Test
    void loadsSkillMdFromConfiguredDirectory(@TempDir Path tempDir) throws Exception {
        Path skillDir = tempDir.resolve("project-skills");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: dcf-model
                description: Builds a discounted cash-flow valuation
                type: PROMPT
                category: finance
                triggerWhen: User asks for DCF or intrinsic valuation
                ---
                # DCF Model

                Walk the user through assumptions before computing.
                """);

        SkillCatalog catalog = new SkillCatalog();
        SkillLoader loader = new SkillLoader(catalog).scan(SkillScope.PROJECT, skillDir);
        int loaded = loader.loadAll();

        assertThat(loaded).isEqualTo(1);
        assertThat(catalog.size()).isEqualTo(1);
        var entry = catalog.view("dcf-model");
        assertThat(entry).isPresent();
        assertThat(entry.get().frontmatter().scope()).isEqualTo(SkillScope.PROJECT);
        assertThat(entry.get().frontmatter().whenToUse()).contains("DCF");
        assertThat(entry.get().frontmatter().extras()).containsKey("loadedFrom");
    }

    @Test
    void recursivelyDiscoversNestedSkills(@TempDir Path tempDir) throws Exception {
        // .swarm/skills/dcf-model/SKILL.md  AND  .swarm/skills/lbo-model/SKILL.md
        Path skillsRoot = tempDir.resolve("skills");
        for (String name : new String[]{"dcf-model", "lbo-model"}) {
            Path dir = skillsRoot.resolve(name);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("SKILL.md"), String.format("""
                    ---
                    name: %s
                    description: skill %s
                    ---
                    body
                    """, name, name));
        }
        SkillCatalog catalog = new SkillCatalog();
        new SkillLoader(catalog).scan(SkillScope.PROJECT, skillsRoot).loadAll();
        assertThat(catalog.size()).isEqualTo(2);
        assertThat(catalog.view("dcf-model")).isPresent();
        assertThat(catalog.view("lbo-model")).isPresent();
    }

    @Test
    void missingDirectoryDoesNotFail(@TempDir Path tempDir) {
        SkillCatalog catalog = new SkillCatalog();
        int loaded = new SkillLoader(catalog)
                .scan(SkillScope.PROJECT, tempDir.resolve("doesnt-exist"))
                .loadAll();
        assertThat(loaded).isZero();
        assertThat(catalog.size()).isZero();
    }

    @Test
    void multipleScopesRespectPrecedence(@TempDir Path tempDir) throws Exception {
        // Same skill name in two scopes — PROJECT should win on view()
        Path personal = tempDir.resolve("personal");
        Path project = tempDir.resolve("project");
        Files.createDirectories(personal);
        Files.createDirectories(project);
        Files.writeString(personal.resolve("SKILL.md"), """
                ---
                name: shared-skill
                description: personal version
                ---
                body
                """);
        Files.writeString(project.resolve("SKILL.md"), """
                ---
                name: shared-skill
                description: project version
                ---
                body
                """);

        SkillCatalog catalog = new SkillCatalog();
        new SkillLoader(catalog)
                .scan(SkillScope.PERSONAL, personal)
                .scan(SkillScope.PROJECT, project)
                .loadAll();

        assertThat(catalog.size()).isEqualTo(2);
        var view = catalog.view("shared-skill");
        assertThat(view).isPresent();
        assertThat(view.get().frontmatter().scope()).isEqualTo(SkillScope.PROJECT);
        assertThat(view.get().definition().getDescription()).isEqualTo("project version");
    }

    @Test
    void skillsWithoutNameAreSkipped(@TempDir Path tempDir) throws Exception {
        Path skillDir = tempDir.resolve("skills");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                description: no name here
                ---
                body
                """);
        SkillCatalog catalog = new SkillCatalog();
        int loaded = new SkillLoader(catalog).scan(SkillScope.PROJECT, skillDir).loadAll();
        assertThat(loaded).isZero();
        assertThat(catalog.size()).isZero();
    }

    @Test
    void refreshReRegistersChangedSkills(@TempDir Path tempDir) throws Exception {
        Path skillDir = tempDir.resolve("skills");
        Files.createDirectories(skillDir);
        Path skillFile = skillDir.resolve("SKILL.md");
        Files.writeString(skillFile, """
                ---
                name: morning-note
                description: v1
                ---
                body v1
                """);

        SkillCatalog catalog = new SkillCatalog();
        SkillLoader loader = new SkillLoader(catalog).scan(SkillScope.PROJECT, skillDir);
        loader.loadAll();
        assertThat(catalog.view("morning-note").get().definition().getDescription()).isEqualTo("v1");

        // Update the file
        Files.writeString(skillFile, """
                ---
                name: morning-note
                description: v2
                ---
                body v2
                """);
        loader.refresh();
        assertThat(catalog.view("morning-note").get().definition().getDescription()).isEqualTo("v2");
    }
}
