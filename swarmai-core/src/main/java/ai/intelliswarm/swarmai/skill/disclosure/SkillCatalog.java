package ai.intelliswarm.swarmai.skill.disclosure;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tier-1 / Tier-2 skill disclosure facade. Progressive-disclosure pattern: the
 * catalog exposes lightweight {@link SkillMetadata} descriptors for the model's
 * selection pass, then yields full bodies only when {@link #view(String)} is
 * called for a specific skill.
 *
 * <p>Two surfaces:
 * <ul>
 *   <li>{@link #list(SkillListingOptions)} — Tier-1: returns ordered
 *       {@link SkillMetadata} entries respecting scope precedence and the
 *       caller's filters. Token-cheap.</li>
 *   <li>{@link #view(String)} — Tier-2: returns the full
 *       {@link SkillCatalogEntry} including the SKILL.md body, references,
 *       integration tests, hooks, etc. Called only when a skill is selected.</li>
 * </ul>
 *
 * <p>Scope precedence on name collisions: {@code PROJECT} &gt; {@code PERSONAL}
 * &gt; {@code PLUGIN} &gt; {@code ENTERPRISE}. The more-specific scope wins.
 * Use {@link #listAll()} when you need the raw multi-scope view.
 *
 * <p>Thread-safe via {@link ConcurrentHashMap}.
 */
@PublicApi(since = "1.0.24")
public final class SkillCatalog {

    /** Default listing budget: enough for ~100 skills × ~50 tokens each. */
    public static final int DEFAULT_LISTING_BUDGET_TOKENS = 5_000;

    /** Approximate character-to-token ratio for budget enforcement. */
    private static final int CHARS_PER_TOKEN = 4;

    /** Entries keyed by (scope, name). */
    private final Map<ScopedKey, SkillCatalogEntry> entries = new ConcurrentHashMap<>();

    /** Register a skill. If a skill with the same (scope, name) already exists it is replaced. */
    public void register(SkillCatalogEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        entries.put(new ScopedKey(entry.frontmatter().scope(), entry.metadata().name()), entry);
    }

    /** Remove a skill by scope + name. */
    public boolean unregister(SkillScope scope, String name) {
        return entries.remove(new ScopedKey(scope, name)) != null;
    }

    public int size() {
        return entries.size();
    }

    /** Every entry across all scopes — no precedence applied. */
    public List<SkillCatalogEntry> listAll() {
        return List.copyOf(entries.values());
    }

    /** Tier-1 listing for the LLM, respecting scope precedence and options. */
    public List<SkillMetadata> list(SkillListingOptions options) {
        Objects.requireNonNull(options, "options must not be null");
        // Group by name; pick the highest-precedence entry per name.
        Map<String, SkillCatalogEntry> winners = new java.util.LinkedHashMap<>();
        for (SkillCatalogEntry entry : entries.values()) {
            SkillCatalogEntry current = winners.get(entry.metadata().name());
            if (current == null || precedence(entry.frontmatter().scope())
                    > precedence(current.frontmatter().scope())) {
                winners.put(entry.metadata().name(), entry);
            }
        }

        // Filter by options
        List<SkillMetadata> filtered = new ArrayList<>();
        for (SkillCatalogEntry e : winners.values()) {
            if (!options.scopes().isEmpty() && !options.scopes().contains(e.frontmatter().scope())) {
                continue;
            }
            if (options.onlyLlmVisible() && !e.metadata().visibleToLlm()) {
                continue;
            }
            if (options.onlyReadyToInvoke() && e.metadata().readiness() != SkillReadiness.AVAILABLE) {
                continue;
            }
            filtered.add(e.metadata());
        }

        // Sort by name (stable, predictable for the model)
        filtered.sort(Comparator.comparing(SkillMetadata::name));

        // Apply token budget if set — trim entries that would push us past budget
        int budgetChars = options.listingBudgetTokens() * CHARS_PER_TOKEN;
        if (budgetChars > 0) {
            List<SkillMetadata> trimmed = new ArrayList<>();
            int used = 0;
            for (SkillMetadata m : filtered) {
                int cost = m.toListingLine().length() + 1;
                if (used + cost > budgetChars) break;
                trimmed.add(m);
                used += cost;
            }
            return List.copyOf(trimmed);
        }
        return List.copyOf(filtered);
    }

    /** Tier-2 fetch — returns the full entry for {@code name}, honouring scope precedence. */
    public Optional<SkillCatalogEntry> view(String name) {
        SkillCatalogEntry best = null;
        for (SkillCatalogEntry entry : entries.values()) {
            if (!entry.metadata().name().equals(name)) continue;
            if (best == null || precedence(entry.frontmatter().scope())
                    > precedence(best.frontmatter().scope())) {
                best = entry;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Tier-2 fetch with explicit scope, useful when the caller knows which copy it wants. */
    public Optional<SkillCatalogEntry> view(SkillScope scope, String name) {
        return Optional.ofNullable(entries.get(new ScopedKey(scope, name)));
    }

    /**
     * Render a complete Tier-1 listing as a single prompt fragment, ready to
     * be appended to the agent's system prompt. Each line is the result of
     * {@link SkillMetadata#toListingLine()}.
     */
    public String renderListing(SkillListingOptions options) {
        List<SkillMetadata> metas = list(options);
        if (metas.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("## Available Skills\n");
        for (SkillMetadata m : metas) {
            sb.append(m.toListingLine()).append('\n');
        }
        return sb.toString();
    }

    /** Higher number → higher precedence. */
    private static int precedence(SkillScope scope) {
        return switch (scope) {
            case PROJECT -> 4;
            case PERSONAL -> 3;
            case PLUGIN -> 2;
            case ENTERPRISE -> 1;
        };
    }

    private record ScopedKey(SkillScope scope, String name) {}

    /**
     * Listing-time filters and budget. Use {@link Builder} for readability.
     *
     * @param scopes               restrict to these scopes only ({@code empty} = no restriction)
     * @param onlyLlmVisible       hide user-only / unready skills
     * @param onlyReadyToInvoke    hide SETUP_NEEDED / UNSUPPORTED skills
     * @param listingBudgetTokens  approximate cap on total listing tokens; {@code 0} = no cap
     */
    @PublicApi(since = "1.0.24")
    public record SkillListingOptions(
            Set<SkillScope> scopes,
            boolean onlyLlmVisible,
            boolean onlyReadyToInvoke,
            int listingBudgetTokens) {

        public SkillListingOptions {
            scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
        }

        /** Default options: all scopes, LLM-visible only, ready only, default budget. */
        public static SkillListingOptions defaults() {
            return new SkillListingOptions(Set.of(), true, true, DEFAULT_LISTING_BUDGET_TOKENS);
        }

        /** Unfiltered, no budget — useful for diagnostics. */
        public static SkillListingOptions unrestricted() {
            return new SkillListingOptions(Set.of(), false, false, 0);
        }

        public static Builder builder() { return new Builder(); }

        public static final class Builder {
            private Set<SkillScope> scopes = Set.of();
            private boolean onlyLlmVisible = true;
            private boolean onlyReadyToInvoke = true;
            private int listingBudgetTokens = DEFAULT_LISTING_BUDGET_TOKENS;

            public Builder scopes(SkillScope... s) { this.scopes = Set.of(s); return this; }
            public Builder scopes(Set<SkillScope> s) { this.scopes = s; return this; }
            public Builder onlyLlmVisible(boolean v) { this.onlyLlmVisible = v; return this; }
            public Builder onlyReadyToInvoke(boolean v) { this.onlyReadyToInvoke = v; return this; }
            public Builder listingBudgetTokens(int v) { this.listingBudgetTokens = v; return this; }

            public SkillListingOptions build() {
                return new SkillListingOptions(scopes, onlyLlmVisible, onlyReadyToInvoke, listingBudgetTokens);
            }
        }
    }
}
