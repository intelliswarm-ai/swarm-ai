package ai.intelliswarm.swarmai.skill.disclosure;

import ai.intelliswarm.swarmai.skill.SkillDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SkillCatalogTest {

    @Test
    void emptyCatalogReturnsEmptyList() {
        SkillCatalog catalog = new SkillCatalog();
        assertThat(catalog.list(SkillCatalog.SkillListingOptions.defaults())).isEmpty();
        assertThat(catalog.view("anything")).isEmpty();
        assertThat(catalog.size()).isZero();
    }

    @Test
    void registerAndListReturnsTier1Metadata() {
        SkillCatalog catalog = new SkillCatalog();
        catalog.register(buildEntry("dcf-model", "Builds a discounted cash-flow valuation", SkillScope.PROJECT));

        List<SkillMetadata> listing = catalog.list(SkillCatalog.SkillListingOptions.defaults());
        assertThat(listing).hasSize(1);
        assertThat(listing.get(0).name()).isEqualTo("dcf-model");
        assertThat(listing.get(0).description()).contains("discounted cash-flow");
    }

    @Test
    void viewReturnsFullEntry() {
        SkillCatalog catalog = new SkillCatalog();
        SkillCatalogEntry entry = buildEntry("lbo-model", "Builds an LBO model", SkillScope.PROJECT);
        catalog.register(entry);

        var fetched = catalog.view("lbo-model");
        assertThat(fetched).isPresent();
        assertThat(fetched.get().definition().getName()).isEqualTo("lbo-model");
    }

    @Test
    void scopePrecedenceProjectBeatsEnterprise() {
        SkillCatalog catalog = new SkillCatalog();
        catalog.register(buildEntry("comps", "Enterprise variant", SkillScope.ENTERPRISE));
        catalog.register(buildEntry("comps", "Project variant", SkillScope.PROJECT));

        var listing = catalog.list(SkillCatalog.SkillListingOptions.defaults());
        assertThat(listing).hasSize(1);
        assertThat(listing.get(0).description()).contains("Project variant");

        var view = catalog.view("comps");
        assertThat(view).isPresent();
        assertThat(view.get().frontmatter().scope()).isEqualTo(SkillScope.PROJECT);
    }

    @Test
    void personalBeatsPluginBeatsEnterprise() {
        SkillCatalog catalog = new SkillCatalog();
        catalog.register(buildEntry("x", "ent", SkillScope.ENTERPRISE));
        catalog.register(buildEntry("x", "plug", SkillScope.PLUGIN));
        catalog.register(buildEntry("x", "personal", SkillScope.PERSONAL));

        assertThat(catalog.view("x").get().frontmatter().scope()).isEqualTo(SkillScope.PERSONAL);
    }

    @Test
    void unreadyAndUserOnlySkillsHiddenFromLlmListingByDefault() {
        SkillCatalog catalog = new SkillCatalog();
        catalog.register(buildEntry("ready", "ok", SkillScope.PROJECT));
        catalog.register(SkillCatalogEntry.from(
                defAt("missing-key", "needs API key"),
                SkillFrontmatter.builder(SkillScope.PROJECT)
                        .readiness(SkillReadiness.SETUP_NEEDED)
                        .setupHint("set ALPHA_VANTAGE_API_KEY")
                        .build()));
        catalog.register(SkillCatalogEntry.from(
                defAt("user-only", "human-only operation"),
                SkillFrontmatter.builder(SkillScope.PROJECT)
                        .disableModelInvocation(true)
                        .build()));

        var llmListing = catalog.list(SkillCatalog.SkillListingOptions.defaults());
        assertThat(llmListing).hasSize(1);
        assertThat(llmListing.get(0).name()).isEqualTo("ready");

        var unrestricted = catalog.list(SkillCatalog.SkillListingOptions.unrestricted());
        assertThat(unrestricted).hasSize(3);
    }

    @Test
    void listingRespectsScopeFilter() {
        SkillCatalog catalog = new SkillCatalog();
        catalog.register(buildEntry("a", "ent", SkillScope.ENTERPRISE));
        catalog.register(buildEntry("b", "proj", SkillScope.PROJECT));
        catalog.register(buildEntry("c", "personal", SkillScope.PERSONAL));

        var projectOnly = catalog.list(SkillCatalog.SkillListingOptions.builder()
                .scopes(SkillScope.PROJECT)
                .build());
        assertThat(projectOnly).extracting(SkillMetadata::name).containsExactly("b");
    }

    @Test
    void tokenBudgetTrimsListing() {
        SkillCatalog catalog = new SkillCatalog();
        for (int i = 0; i < 20; i++) {
            catalog.register(buildEntry("skill-" + i,
                    "Some description that takes a moderate number of characters to print out",
                    SkillScope.PROJECT));
        }
        // Tight budget — only a few entries fit
        var trimmed = catalog.list(SkillCatalog.SkillListingOptions.builder()
                .listingBudgetTokens(50)
                .build());
        assertThat(trimmed.size()).isLessThan(20);
        // Generous budget — all entries fit
        var full = catalog.list(SkillCatalog.SkillListingOptions.builder()
                .listingBudgetTokens(10_000)
                .build());
        assertThat(full).hasSize(20);
    }

    @Test
    void unregisterRemovesEntry() {
        SkillCatalog catalog = new SkillCatalog();
        catalog.register(buildEntry("temp", "tmp", SkillScope.PROJECT));
        assertThat(catalog.size()).isEqualTo(1);
        assertThat(catalog.unregister(SkillScope.PROJECT, "temp")).isTrue();
        assertThat(catalog.size()).isZero();
        assertThat(catalog.unregister(SkillScope.PROJECT, "temp")).isFalse();
    }

    @Test
    void renderListingProducesPromptFragment() {
        SkillCatalog catalog = new SkillCatalog();
        catalog.register(SkillCatalogEntry.from(
                defAt("dcf", "DCF analysis"),
                SkillFrontmatter.builder(SkillScope.PROJECT)
                        .whenToUse("user asks for intrinsic valuation")
                        .build()));
        String rendered = catalog.renderListing(SkillCatalog.SkillListingOptions.defaults());
        assertThat(rendered).contains("## Available Skills");
        assertThat(rendered).contains("- dcf:").contains("DCF analysis")
                .contains("use when: user asks for intrinsic valuation");
    }

    @Test
    void argumentHintAppearsInListing() {
        SkillMetadata m = new SkillMetadata(
                "morning-note", "Generates a sector morning note",
                "before market open", "{sector, date}",
                SkillScope.PROJECT, SkillReadiness.AVAILABLE, null, false);
        assertThat(m.toListingLine()).contains("args: {sector, date}");
    }

    @Test
    void viewWithScopeFetchesSpecificCopy() {
        SkillCatalog catalog = new SkillCatalog();
        catalog.register(buildEntry("x", "ent", SkillScope.ENTERPRISE));
        catalog.register(buildEntry("x", "personal", SkillScope.PERSONAL));

        var entVariant = catalog.view(SkillScope.ENTERPRISE, "x");
        assertThat(entVariant).isPresent();
        assertThat(entVariant.get().definition().getDescription()).isEqualTo("ent");
    }

    @Test
    void readinessFlagsControlVisibility() {
        SkillCatalog catalog = new SkillCatalog();
        catalog.register(SkillCatalogEntry.from(
                defAt("unsupported", "won't run here"),
                SkillFrontmatter.builder(SkillScope.PROJECT)
                        .readiness(SkillReadiness.UNSUPPORTED).build()));

        var defaults = catalog.list(SkillCatalog.SkillListingOptions.defaults());
        assertThat(defaults).isEmpty();

        // Need to disable BOTH filters to see UNSUPPORTED: onlyLlmVisible() also gates on readiness
        var disabledFilter = catalog.list(SkillCatalog.SkillListingOptions.builder()
                .onlyReadyToInvoke(false)
                .onlyLlmVisible(false)
                .build());
        assertThat(disabledFilter).hasSize(1);
    }

    // ---------- helpers ----------

    private static SkillDefinition defAt(String name, String description) {
        return SkillDefinition.builder()
                .name(name)
                .description(description)
                .build();
    }

    private static SkillCatalogEntry buildEntry(String name, String description, SkillScope scope) {
        return SkillCatalogEntry.from(
                defAt(name, description),
                SkillFrontmatter.defaultsFor(scope));
    }
}
