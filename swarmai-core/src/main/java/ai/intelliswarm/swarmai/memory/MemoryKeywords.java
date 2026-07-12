package ai.intelliswarm.swarmai.memory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Shared keyword tokenization and relevance scoring for Memory implementations.
 *
 * <p>Memory queries are typically full task descriptions ("Analyze the Q3 revenue
 * figures and summarize trends"), while stored memories are "Task: ...\nResult: ..."
 * summaries. Whole-string substring matching almost never hits, so retrieval is
 * done with OR keyword semantics: tokenize the query, drop stopwords, and rank
 * stored content by how many distinct keywords it contains (ties broken by recency
 * at the call site).
 */
final class MemoryKeywords {

    /** Common English words that carry no retrieval signal in task descriptions. */
    private static final Set<String> STOPWORDS = Set.of(
            "the", "and", "for", "are", "was", "were", "with", "that", "this",
            "then", "than", "from", "into", "your", "their", "them", "they",
            "will", "would", "should", "could", "have", "has", "had", "been",
            "each", "all", "any", "can", "may", "must", "not", "but", "you",
            "our", "out", "use", "using", "used", "task", "result", "please",
            "make", "sure", "when", "what", "which", "where", "how", "why",
            "about", "also", "its", "it's", "these", "those", "such", "per");

    /** Cap so pathological queries don't produce huge SQL or scoring loops. */
    private static final int MAX_KEYWORDS = 12;

    private static final int MIN_KEYWORD_LENGTH = 3;

    private MemoryKeywords() {
    }

    /**
     * Tokenizes a query into distinct lowercase keywords, dropping stopwords and
     * tokens shorter than {@value MIN_KEYWORD_LENGTH} chars. Order of first
     * appearance is preserved; at most {@value MAX_KEYWORDS} keywords are returned.
     * Returns an empty list when the query yields no usable keywords (callers
     * should then fall back to raw substring matching).
     */
    static List<String> tokenize(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Set<String> keywords = new LinkedHashSet<>();
        for (String token : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_.-]+")) {
            String cleaned = token.replaceAll("^[.\\-]+|[.\\-]+$", "");
            if (cleaned.length() >= MIN_KEYWORD_LENGTH && !STOPWORDS.contains(cleaned)) {
                keywords.add(cleaned);
                if (keywords.size() >= MAX_KEYWORDS) {
                    break;
                }
            }
        }
        return new ArrayList<>(keywords);
    }

    /**
     * Counts how many of the given keywords appear in the content (case-insensitive).
     * Content should be lowercased once by the caller when scoring many keywords
     * against the same entry is not needed; this method lowercases internally for
     * convenience.
     */
    static int score(String content, List<String> keywords) {
        if (content == null || content.isEmpty() || keywords.isEmpty()) {
            return 0;
        }
        String lower = content.toLowerCase(Locale.ROOT);
        int matches = 0;
        for (String keyword : keywords) {
            if (lower.contains(keyword)) {
                matches++;
            }
        }
        return matches;
    }
}
