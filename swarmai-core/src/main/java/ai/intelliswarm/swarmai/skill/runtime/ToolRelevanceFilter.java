package ai.intelliswarm.swarmai.skill.runtime;

import ai.intelliswarm.swarmai.tool.base.BaseTool;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Selects the most relevant {@link BaseTool}s for a given task by scoring
 * each tool's description, triggerWhen, tags, and category against the
 * task's text.
 *
 * <h2>Why this matters as the toolkit grows</h2>
 * Once the framework dynamically registers 50+ tools (one Kali toolbox
 * + a few Codex-generated capabilities + the platform's own utilities),
 * passing all of them to the LLM in every turn bloats the system prompt
 * and degrades reasoning quality. Claude Code dodges this by having a
 * fixed, curated tool list. SwarmAI can grow indefinitely, so it needs
 * a filter step.
 *
 * <h2>Scoring</h2>
 * For each candidate tool we compute a score = sum of
 * {@code IDF(token) * matched(token)} over the union of tokens in the
 * task description and the tool's blurb (description + triggerWhen +
 * avoidWhen + category + tags joined). A simplified TF-IDF where:
 * <ul>
 *   <li>"Document frequency" is computed across the candidate tool set
 *       — common terms like "tool", "data", "the" naturally get small
 *       weights without an explicit stopword list.</li>
 *   <li>Token overlap on a tool's blurb increments the score by that
 *       token's IDF weight.</li>
 * </ul>
 *
 * <p>This is intentionally simple. Vector embeddings would score better
 * but require an embedding model and a vector index — not justified
 * unless the toolkit grows past several hundred tools. For 50-200 tools
 * the IDF-weighted overlap is fast (single pass per tool), deterministic
 * (no LLM in the hot path), and good enough.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * ToolRelevanceFilter filter = new ToolRelevanceFilter();
 * List<BaseTool> top = filter.topK(task.getDescription(), allTools, 12);
 * agent.registerTools(top);
 * }</pre>
 */
public final class ToolRelevanceFilter {

    /**
     * Tokenise: lowercase, split on non-alphanumeric. Cheap and
     * sufficient for short tool blurbs and task descriptions.
     */
    static Set<String> tokenize(String s) {
        if (s == null || s.isBlank()) return Set.of();
        Set<String> tokens = new HashSet<>();
        for (String raw : s.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (raw.length() >= 2 && raw.length() <= 30) tokens.add(raw);
        }
        return tokens;
    }

    /**
     * Score every tool against the task and return the top-k by relevance.
     * Ties broken by the tool's natural order in {@code candidates}.
     *
     * @param taskDescription   the task / goal text the agent is reasoning about
     * @param candidates        the full available toolkit
     * @param k                 maximum tools to return; if {@code k >= candidates.size()},
     *                          returns all (still scored, ranked)
     */
    public List<BaseTool> topK(String taskDescription, List<BaseTool> candidates, int k) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        if (k <= 0) return List.of();

        Set<String> taskTokens = tokenize(taskDescription);
        if (taskTokens.isEmpty()) {
            // Empty task → return the first k tools as-is. Better than 0.
            return candidates.subList(0, Math.min(k, candidates.size()));
        }

        // Compute IDF-like weight for each task-relevant token across the
        // candidate corpus. Rare tokens contribute more.
        Map<String, Double> idf = computeIdf(taskTokens, candidates);

        List<Scored> scored = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            BaseTool tool = candidates.get(i);
            Set<String> toolTokens = tokenize(blurbOf(tool));
            double score = 0;
            for (String t : taskTokens) {
                if (toolTokens.contains(t)) {
                    score += idf.getOrDefault(t, 0.0);
                }
            }
            scored.add(new Scored(i, tool, score));
        }

        scored.sort(Comparator
                .comparingDouble((Scored s) -> -s.score)   // highest score first
                .thenComparingInt(s -> s.originalIndex));   // stable for ties

        int limit = Math.min(k, scored.size());
        List<BaseTool> top = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            // Drop tools whose score is exactly zero unless we'd return nothing
            // — a zero-score tool isn't truly relevant.
            if (scored.get(i).score == 0 && !top.isEmpty()) break;
            top.add(scored.get(i).tool);
        }
        return top;
    }

    /**
     * Score-and-return-everything variant. Useful when the caller wants
     * to display the relevance ranking rather than truncate.
     */
    public List<Scored> rank(String taskDescription, List<BaseTool> candidates) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        Set<String> taskTokens = tokenize(taskDescription);
        Map<String, Double> idf = taskTokens.isEmpty() ? Map.of() : computeIdf(taskTokens, candidates);
        List<Scored> scored = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            BaseTool tool = candidates.get(i);
            Set<String> toolTokens = tokenize(blurbOf(tool));
            double score = 0;
            for (String t : taskTokens) {
                if (toolTokens.contains(t)) score += idf.getOrDefault(t, 0.0);
            }
            scored.add(new Scored(i, tool, score));
        }
        scored.sort(Comparator
                .comparingDouble((Scored s) -> -s.score)
                .thenComparingInt(s -> s.originalIndex));
        return scored;
    }

    /** Concatenate the tool's text fields into a single blurb for tokenization. */
    private static String blurbOf(BaseTool tool) {
        StringBuilder sb = new StringBuilder();
        sb.append(tool.getFunctionName()).append(' ');
        sb.append(tool.getDescription()).append(' ');
        if (tool.getTriggerWhen() != null) sb.append(tool.getTriggerWhen()).append(' ');
        if (tool.getAvoidWhen() != null) sb.append(tool.getAvoidWhen()).append(' ');
        if (tool.getCategory() != null) sb.append(tool.getCategory()).append(' ');
        if (tool.getTags() != null) {
            for (String tag : tool.getTags()) sb.append(tag).append(' ');
        }
        return sb.toString();
    }

    private static Map<String, Double> computeIdf(Set<String> taskTokens, List<BaseTool> candidates) {
        // Document frequency for each task token over the candidate set.
        Map<String, Integer> df = new HashMap<>();
        for (BaseTool tool : candidates) {
            Set<String> toolTokens = tokenize(blurbOf(tool));
            for (String t : taskTokens) {
                if (toolTokens.contains(t)) df.merge(t, 1, Integer::sum);
            }
        }
        int n = candidates.size();
        Map<String, Double> idf = new HashMap<>();
        for (String t : taskTokens) {
            int dfT = df.getOrDefault(t, 0);
            // Smoothed IDF: log((n+1)/(df+1)) + 1 — never zero, never negative.
            double w = Math.log((double) (n + 1) / (dfT + 1)) + 1.0;
            idf.put(t, w);
        }
        return idf;
    }

    /**
     * Scoring result — a (tool, score) pair plus the original index for
     * stable sort order.
     */
    public record Scored(int originalIndex, BaseTool tool, double score) {
        public String summary() {
            return String.format("%.2f  %s", score, tool.getFunctionName());
        }
    }
}
