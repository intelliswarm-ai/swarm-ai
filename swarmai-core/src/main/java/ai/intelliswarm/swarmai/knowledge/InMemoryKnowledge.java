package ai.intelliswarm.swarmai.knowledge;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory implementation of the Knowledge interface.
 * Provides simple keyword-based search for knowledge sources.
 * Suitable for testing and small-scale deployments.
 */
public class InMemoryKnowledge implements Knowledge {

    private final Map<String, KnowledgeSource> sources = new ConcurrentHashMap<>();

    @Override
    public String query(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }

        String lowerQuery = query.toLowerCase();

        // Find the most relevant source based on keyword matching
        return sources.values().stream()
                .filter(source -> source.content().toLowerCase().contains(lowerQuery))
                .max(Comparator.comparingInt(source -> countMatches(source.content().toLowerCase(), lowerQuery)))
                .map(KnowledgeSource::content)
                .orElse("");
    }

    @Override
    public List<String> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        String lowerQuery = query.toLowerCase();

        return sources.values().stream()
                .filter(source -> source.content().toLowerCase().contains(lowerQuery))
                .sorted(Comparator.comparingInt(
                        (KnowledgeSource source) -> countMatches(source.content().toLowerCase(), lowerQuery)).reversed())
                .limit(limit)
                .map(KnowledgeSource::content)
                .collect(Collectors.toList());
    }

    @Override
    public void addSource(String sourceId, String content, Map<String, Object> metadata) {
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("Source ID cannot be null or blank");
        }
        if (content == null) {
            throw new IllegalArgumentException("Content cannot be null");
        }

        sources.put(sourceId, new KnowledgeSource(
                sourceId,
                content,
                metadata != null ? new HashMap<>(metadata) : new HashMap<>(),
                LocalDateTime.now()
        ));
    }

    @Override
    public void removeSource(String sourceId) {
        if (sourceId != null) {
            sources.remove(sourceId);
        }
    }

    @Override
    public List<String> getSources() {
        return new ArrayList<>(sources.keySet());
    }

    @Override
    public boolean hasSource(String sourceId) {
        return sourceId != null && sources.containsKey(sourceId);
    }

    /**
     * Gets the content of a specific source.
     */
    public String getSourceContent(String sourceId) {
        KnowledgeSource source = sources.get(sourceId);
        return source != null ? source.content() : null;
    }

    /**
     * Gets the metadata of a specific source.
     */
    public Map<String, Object> getSourceMetadata(String sourceId) {
        KnowledgeSource source = sources.get(sourceId);
        return source != null ? new HashMap<>(source.metadata()) : null;
    }

    /**
     * Gets the total number of sources.
     */
    public int size() {
        return sources.size();
    }

    /**
     * Checks if the knowledge base is empty.
     */
    public boolean isEmpty() {
        return sources.isEmpty();
    }

    /**
     * Clears all sources.
     */
    public void clear() {
        sources.clear();
    }

    /**
     * Counts occurrences of a substring in a string.
     */
    private int countMatches(String text, String substring) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(substring, index)) != -1) {
            count++;
            index += substring.length();
        }
        return count;
    }

    /**
     * Internal record to store knowledge sources with metadata.
     */
    private record KnowledgeSource(
            String sourceId,
            String content,
            Map<String, Object> metadata,
            LocalDateTime addedAt
    ) {
    }
}
