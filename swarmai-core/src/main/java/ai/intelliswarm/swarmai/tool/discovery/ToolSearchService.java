package ai.intelliswarm.swarmai.tool.discovery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Keyword-relevance search over a {@link ToolCatalog}. Deliberately simple —
 * exact-substring scoring with weighted matches against name, keywords, and
 * description. No external dependencies; no embedding model. Good enough for
 * tool counts up to a few hundred, which is the practical range for harness
 * tool-set scaling before architectural rework is needed anyway.
 *
 * <h2>Scoring (per query term)</h2>
 * <ul>
 *   <li>name match (substring): +3.0</li>
 *   <li>keyword match (whole-token, case-insensitive): +2.0</li>
 *   <li>description match (substring, case-insensitive): +1.0</li>
 * </ul>
 *
 * <p>Plus a small bonus when <em>every</em> query term hits somewhere in the
 * entry: +1.0. That bonus rewards "tight" matches over partial ones.
 *
 * <p>Entries with score 0 are dropped. Results are sorted by score descending,
 * stable on ties (preserving registration order).
 */
public final class ToolSearchService {

    /** Default cap on results returned by {@link #search(String)}. */
    public static final int DEFAULT_MAX_RESULTS = 5;

    /** Tokenizer pattern: split on any non-alphanumeric character. */
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-zA-Z0-9]+");

    /** Per-match-type weights. Public so callers can document their behaviour. */
    public static final double NAME_MATCH_WEIGHT = 3.0;
    public static final double KEYWORD_MATCH_WEIGHT = 2.0;
    public static final double DESCRIPTION_MATCH_WEIGHT = 1.0;
    public static final double ALL_TERMS_HIT_BONUS = 1.0;

    private final ToolCatalog catalog;

    public ToolSearchService(ToolCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    /** Search with the default cap. Empty query returns empty list. */
    public List<ToolMatch> search(String query) {
        return search(query, DEFAULT_MAX_RESULTS);
    }

    /**
     * Search the catalog. {@code maxResults <= 0} returns an empty list;
     * {@code maxResults > catalog size} returns up to catalog size.
     */
    public List<ToolMatch> search(String query, int maxResults) {
        if (query == null || query.isBlank() || maxResults <= 0) {
            return List.of();
        }
        List<String> terms = tokenize(query);
        if (terms.isEmpty()) return List.of();

        List<ToolMatch> matches = new ArrayList<>();
        for (CatalogEntry entry : catalog.all()) {
            double s = score(entry, terms);
            if (s > 0) matches.add(new ToolMatch(entry, s));
        }

        matches.sort(Comparator.comparingDouble(ToolMatch::score).reversed());
        if (matches.size() > maxResults) {
            return List.copyOf(matches.subList(0, maxResults));
        }
        return List.copyOf(matches);
    }

    /** The catalog this service searches. */
    public ToolCatalog catalog() {
        return catalog;
    }

    /**
     * Score one entry against pre-tokenized query terms.
     * Public to allow callers (and tests) to inspect scoring.
     */
    public static double score(CatalogEntry entry, List<String> queryTerms) {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(queryTerms, "queryTerms");
        if (queryTerms.isEmpty()) return 0.0;

        String name = entry.name().toLowerCase(Locale.ROOT);
        String description = entry.description().toLowerCase(Locale.ROOT);
        List<String> keywordTokens = new ArrayList<>();
        for (String kw : entry.keywords()) {
            keywordTokens.add(kw.toLowerCase(Locale.ROOT));
        }

        double total = 0.0;
        int hitTermCount = 0;
        for (String term : queryTerms) {
            boolean hit = false;
            if (name.contains(term)) {
                total += NAME_MATCH_WEIGHT;
                hit = true;
            }
            for (String kw : keywordTokens) {
                if (kw.equals(term) || kw.contains(term) || term.contains(kw)) {
                    total += KEYWORD_MATCH_WEIGHT;
                    hit = true;
                    break;
                }
            }
            if (description.contains(term)) {
                total += DESCRIPTION_MATCH_WEIGHT;
                hit = true;
            }
            if (hit) hitTermCount++;
        }
        if (hitTermCount == queryTerms.size() && hitTermCount > 1) {
            total += ALL_TERMS_HIT_BONUS;
        }
        return total;
    }

    /** Convenience: tokenize then score. */
    public static double score(CatalogEntry entry, String query) {
        return score(entry, tokenize(query));
    }

    /**
     * Minimum token length kept by {@link #tokenize}. Tokens below this length
     * carry too little signal to score reliably — e.g. {@code "What's the time?"}
     * tokenizes to {@code [what, s, the, time]} and {@code "s"} would otherwise
     * substring-match many irrelevant entries' names/descriptions, polluting
     * relevance. Stop-words like {@code "the", "and", "is"} also fall below
     * this floor or are dropped via {@link #STOP_WORDS}.
     */
    public static final int MIN_TOKEN_LENGTH = 3;

    /**
     * English stop-words filtered from query tokenization. Short, high-frequency
     * words that almost never carry intent. Kept small and conservative — false
     * negatives (a useful word filtered out) are rarer than false positives.
     */
    private static final java.util.Set<String> STOP_WORDS = java.util.Set.of(
            "the", "and", "for", "with", "from", "into", "what", "this", "that",
            "are", "was", "were", "has", "have", "had", "you", "your", "our",
            "any", "all", "but", "not", "now", "out");

    /** Lower-case, split, drop empties + tokens shorter than {@link #MIN_TOKEN_LENGTH} + stop-words. Public for test introspection. */
    public static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();
        String[] parts = TOKEN_SPLIT.split(text.toLowerCase(Locale.ROOT));
        List<String> out = new ArrayList<>(parts.length);
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (p.length() < MIN_TOKEN_LENGTH) continue;
            if (STOP_WORDS.contains(p)) continue;
            out.add(p);
        }
        return out;
    }
}
