package ai.intelliswarm.swarmai.rag.tool;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Citation-aware retrieval over the configured Spring AI VectorStore.
 *
 * <p>Differs from {@code semantic_search} in that each result is prefixed with its
 * {@code source} metadata (the citation label set by {@code rag_ingest}), making it
 * easy for an evaluator agent to attribute each piece of evidence to the paper it came from.
 *
 * <p>Parameters:
 * <ul>
 *     <li>{@code query} — natural language search (required)</li>
 *     <li>{@code top_k} — max results (default 5, hard cap 20)</li>
 *     <li>{@code threshold} — min similarity 0..1 (default 0)</li>
 *     <li>{@code source_prefix} — restrict results whose {@code source} metadata starts with this prefix
 *                                 (e.g. "arxiv:" to search only arXiv ingestions)</li>
 * </ul>
 */
public class RagSearchTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(RagSearchTool.class);

    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 20;

    private final VectorStore vectorStore;

    @Autowired(required = false)
    public RagSearchTool(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override public String getFunctionName() { return "rag_search"; }

    @Override
    public String getDescription() {
        return "Semantic search over the RAG vector store, returning text chunks with their citation source. "
             + "Use when you have a hypothesis or claim and need evidence from previously-ingested papers.";
    }

    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.READ_ONLY; }
    @Override public String getCategory() { return "rag"; }
    @Override public List<String> getTags() { return List.of("rag", "search", "semantic", "retrieve"); }

    @Override
    public Object execute(Map<String, Object> parameters) {
        if (vectorStore == null) {
            return "Error: no VectorStore bean configured.";
        }
        String query = asString(parameters.get("query"));
        if (query == null || query.isBlank()) return "Error: 'query' is required";
        int topK = clamp(asInt(parameters.get("top_k"), DEFAULT_TOP_K), 1, MAX_TOP_K);
        double threshold = asDouble(parameters.get("threshold"), 0.0);
        String sourcePrefix = asString(parameters.get("source_prefix"));

        try {
            SearchRequest req = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(threshold)
                    .build();
            List<Document> hits = vectorStore.similaritySearch(req);

            if (sourcePrefix != null && !sourcePrefix.isBlank()) {
                hits = hits.stream()
                    .filter(d -> {
                        Object s = d.getMetadata() == null ? null : d.getMetadata().get("source");
                        return s != null && s.toString().startsWith(sourcePrefix);
                    })
                    .toList();
            }

            logger.info("RagSearch: query='{}' top_k={} hits={}", query, topK, hits.size());
            return format(query, hits, topK);
        } catch (Exception e) {
            logger.error("RagSearch failed", e);
            return "Error: search failed — " + e.getMessage();
        }
    }

    private String format(String query, List<Document> hits, int topK) {
        StringBuilder sb = new StringBuilder();
        sb.append("**RAG search results**\n");
        sb.append("- query: ").append(query).append('\n');
        sb.append("- returned: ").append(hits.size()).append(" of top ").append(topK).append("\n\n");
        if (hits.isEmpty()) {
            sb.append("No matching chunks. Ingest more papers via rag_ingest before retrying.\n");
            return sb.toString();
        }
        for (int i = 0; i < hits.size(); i++) {
            Document d = hits.get(i);
            Map<String, Object> meta = d.getMetadata() == null ? Map.of() : d.getMetadata();
            sb.append("### Result ").append(i + 1).append('\n');
            sb.append("- source: ").append(meta.getOrDefault("source", "(unknown)")).append('\n');
            if (meta.containsKey("chunk_index")) sb.append("- chunk_index: ").append(meta.get("chunk_index")).append('\n');
            if (meta.containsKey("distance")) {
                double dist = ((Number) meta.get("distance")).doubleValue();
                sb.append("- similarity: ").append(String.format("%.4f", 1.0 - dist)).append('\n');
            }
            String content = d.getText();
            if (content != null) {
                if (content.length() > 1800) content = content.substring(0, 1800) + "\n[… truncated …]";
                sb.append('\n').append(content).append("\n\n---\n\n");
            }
        }
        return sb.toString();
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();
        props.put("query", Map.of("type", "string", "description", "Natural-language query (claim, hypothesis, question)"));
        props.put("top_k", Map.of("type", "integer", "description", "Number of chunks to return (max 20)"));
        props.put("threshold", Map.of("type", "number", "description", "Minimum similarity 0..1"));
        props.put("source_prefix", Map.of("type", "string", "description", "Restrict to chunks whose source metadata starts with this prefix"));
        schema.put("properties", props);
        schema.put("required", new String[]{"query"});
        return schema;
    }

    @Override public boolean isAsync() { return false; }
    @Override public boolean isDynamic() { return true; }
    @Override public int getMaxResponseLength() { return 15_000; }
    @Override public String getTriggerWhen() { return "User wants evidence from previously-ingested papers to support or refute a claim."; }
    @Override public String getAvoidWhen() { return "User is asking for keyword/grep-style matching (use grep_search) or for the raw web (use web_search)."; }

    private static String asString(Object v) { return v == null ? null : v.toString(); }
    private static int asInt(Object v, int dflt) {
        if (v instanceof Number n) return n.intValue();
        try { return v == null ? dflt : Integer.parseInt(v.toString()); } catch (Exception e) { return dflt; }
    }
    private static double asDouble(Object v, double dflt) {
        if (v instanceof Number n) return n.doubleValue();
        try { return v == null ? dflt : Double.parseDouble(v.toString()); } catch (Exception e) { return dflt; }
    }
    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
}
