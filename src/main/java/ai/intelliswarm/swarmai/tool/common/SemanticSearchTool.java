package ai.intelliswarm.swarmai.tool.common;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Semantic Search Tool — natural language search over a vector store using embeddings.
 *
 * Uses Spring AI's VectorStore interface (supports Chroma, PGVector, etc.).
 * Requires a VectorStore bean to be configured.
 */
@Component
public class SemanticSearchTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(SemanticSearchTool.class);

    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 20;
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.0;

    private final VectorStore vectorStore;

    @Autowired(required = false)
    public SemanticSearchTool(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public SemanticSearchTool() {
        this.vectorStore = null;
    }

    @Override
    public String getFunctionName() {
        return "semantic_search";
    }

    @Override
    public String getDescription() {
        return "Search over documents using natural language queries and semantic similarity. " +
               "Returns the most relevant document chunks with similarity scores. " +
               "Requires a vector store to be configured with indexed documents.";
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String query = (String) parameters.get("query");
        Integer topK = parameters.get("top_k") != null
            ? Math.min(((Number) parameters.get("top_k")).intValue(), MAX_TOP_K)
            : DEFAULT_TOP_K;
        Double threshold = parameters.get("threshold") != null
            ? ((Number) parameters.get("threshold")).doubleValue()
            : DEFAULT_SIMILARITY_THRESHOLD;

        logger.info("SemanticSearchTool: query='{}', top_k={}, threshold={}", query, topK, threshold);

        try {
            // 1. Check availability
            if (vectorStore == null) {
                return "Error: No vector store configured. Configure a VectorStore bean " +
                       "(e.g., Chroma, PGVector) with indexed documents.";
            }

            // 2. Validate
            if (query == null || query.trim().isEmpty()) {
                return "Error: Search query is required";
            }

            // 3. Search
            SearchRequest searchRequest = SearchRequest.builder()
                .query(query.trim())
                .topK(topK)
                .similarityThreshold(threshold)
                .build();

            List<Document> results = vectorStore.similaritySearch(searchRequest);

            // 4. Format response
            return buildResponse(query, results, topK, threshold);

        } catch (Exception e) {
            logger.error("Error performing semantic search", e);
            return "Error: Semantic search failed: " + e.getMessage();
        }
    }

    private String buildResponse(String query, List<Document> results, int topK, double threshold) {
        StringBuilder sb = new StringBuilder();

        sb.append("**Semantic Search Results**\n");
        sb.append("**Query:** ").append(query).append("\n");
        sb.append("**Results:** ").append(results.size());
        if (results.size() >= topK) sb.append(" (top ").append(topK).append(")");
        sb.append("\n\n");

        if (results.isEmpty()) {
            sb.append("No relevant documents found for the given query.\n");
            return sb.toString();
        }

        for (int i = 0; i < results.size(); i++) {
            Document doc = results.get(i);

            sb.append("### Result ").append(i + 1).append("\n");

            // Metadata
            Map<String, Object> metadata = doc.getMetadata();
            if (metadata != null && !metadata.isEmpty()) {
                if (metadata.containsKey("source")) {
                    sb.append("**Source:** ").append(metadata.get("source")).append("\n");
                }
                if (metadata.containsKey("distance")) {
                    sb.append("**Similarity:** ").append(
                        String.format("%.4f", 1.0 - ((Number) metadata.get("distance")).doubleValue())
                    ).append("\n");
                }
            }

            // Content
            String content = doc.getText();
            if (content != null && !content.isEmpty()) {
                if (content.length() > 2000) {
                    content = content.substring(0, 2000) + "\n[... truncated ...]";
                }
                sb.append("\n").append(content).append("\n\n");
            }

            sb.append("---\n\n");
        }

        return sb.toString();
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> query = new HashMap<>();
        query.put("type", "string");
        query.put("description", "Natural language search query");
        properties.put("query", query);

        Map<String, Object> topK = new HashMap<>();
        topK.put("type", "integer");
        topK.put("description", "Number of results to return (default: 5, max: 20)");
        topK.put("default", DEFAULT_TOP_K);
        properties.put("top_k", topK);

        Map<String, Object> threshold = new HashMap<>();
        threshold.put("type", "number");
        threshold.put("description", "Minimum similarity threshold 0.0-1.0 (default: 0.0)");
        threshold.put("default", DEFAULT_SIMILARITY_THRESHOLD);
        properties.put("threshold", threshold);

        schema.put("properties", properties);
        schema.put("required", new String[]{"query"});

        return schema;
    }

    @Override
    public boolean isAsync() {
        return false;
    }

    @Override
    public String getTriggerWhen() {
        return "User needs semantic/vector search to find conceptually similar content.";
    }

    @Override
    public String getAvoidWhen() {
        return "User needs exact keyword matching (use web_search) or file content (use file_read).";
    }

    @Override
    public String getCategory() {
        return "analysis";
    }

    @Override
    public List<String> getTags() {
        return List.of("semantic", "search", "vector", "embeddings");
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return Map.of(
            "type", "markdown",
            "description", "Semantic search results with similarity scores, source metadata, and document content chunks"
        );
    }

    @Override
    public int getMaxResponseLength() {
        return 15000;
    }

    public record Request(String query, Integer topK, Double threshold) {}
}
