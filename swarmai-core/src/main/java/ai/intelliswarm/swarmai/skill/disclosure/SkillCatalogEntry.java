package ai.intelliswarm.swarmai.skill.disclosure;

import ai.intelliswarm.swarmai.api.PublicApi;
import ai.intelliswarm.swarmai.skill.SkillDefinition;

import java.util.Objects;

/**
 * One catalog row. Pairs the lightweight {@link SkillMetadata} (Tier-1)
 * with the heavy {@link SkillDefinition} (Tier-2 body) and the
 * {@link SkillFrontmatter} (runtime invocation policy).
 *
 * <p>The catalog stores entries; the LLM only sees Tier-1 via
 * {@link SkillCatalog#list}. Tier-2 is fetched on demand.
 */
@PublicApi(since = "1.0.24")
public record SkillCatalogEntry(
        SkillMetadata metadata,
        SkillFrontmatter frontmatter,
        SkillDefinition definition) {

    public SkillCatalogEntry {
        Objects.requireNonNull(metadata, "metadata must not be null");
        Objects.requireNonNull(frontmatter, "frontmatter must not be null");
        Objects.requireNonNull(definition, "definition must not be null");
    }

    /**
     * Convenience builder that derives the {@link SkillMetadata} from a
     * {@link SkillDefinition} + {@link SkillFrontmatter}, avoiding the need
     * for callers to wire up three records by hand.
     */
    public static SkillCatalogEntry from(SkillDefinition definition, SkillFrontmatter frontmatter) {
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(frontmatter, "frontmatter must not be null");

        String whenToUse = frontmatter.whenToUse() != null
                ? frontmatter.whenToUse()
                : definition.getTriggerWhen();

        SkillMetadata metadata = new SkillMetadata(
                definition.getName(),
                truncate(definition.getDescription(), 200),
                whenToUse,
                frontmatter.argumentHint(),
                frontmatter.scope(),
                frontmatter.readiness(),
                frontmatter.setupHint(),
                frontmatter.disableModelInvocation());

        return new SkillCatalogEntry(metadata, frontmatter, definition);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max - 1).trim() + "…";
    }
}
