package ai.intelliswarm.swarmai.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Vector store-backed implementation of the Knowledge interface.
 * Uses Spring AI's VectorStore for semantic search (RAG pipeline).
 *
 * Supports loading documents from:
 *   - Plain text files
 *   - Strings
 *   - Any Spring AI Document
 *
 * For PDF/Word support, use Spring AI's TikaDocumentReader
 * or PagePdfDocumentReader before calling addDocument().
 *
 * Configuration:
 *   swarmai.knowledge.provider: vector
 *   # Plus vector store config (pgvector, chroma, etc.)
 *
 * Usage:
 *   VectorKnowledge knowledge = new VectorKnowledge(vectorStore);
 *   knowledge.addSource("doc1", "Company annual report content...", null);
 *   knowledge.addDocument(Path.of("data/report.txt"));
 *
 *   Agent agent = Agent.builder()
 *       .knowledge(knowledge)  // agent can now query the documents
 *       .build();
 */
public class VectorKnowledge implements Knowledge {

    private static final Logger logger = LoggerFactory.getLogger(VectorKnowledge.class);
    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_CHUNK_SIZE = 1000; // chars per chunk

    private final VectorStore vectorStore;
    private final Set<String> sourceIds = Collections.synchronizedSet(new HashSet<>());

    public VectorKnowledge(VectorStore vectorStore) {
        this.vectorStore = Objects.requireNonNull(vectorStore, "VectorStore cannot be null");
        logger.info("VectorKnowledge initialized with {}", vectorStore.getClass().getSimpleName());
    }

    @Override
    public String query(String query) {
        if (query == null || query.isBlank()) return "";

        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(DEFAULT_TOP_K).build());

        if (results == null || results.isEmpty()) return "";

        return results.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n"));
    }

    @Override
    public List<String> search(String query, int limit) {
        if (query == null || query.isBlank()) return Collections.emptyList();

        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(limit).build());

        if (results == null) return Collections.emptyList();

        return results.stream()
                .map(Document::getText)
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

        // Split content into chunks for better retrieval
        List<Document> chunks = chunkContent(sourceId, content, metadata);
        vectorStore.add(chunks);
        sourceIds.add(sourceId);

        logger.info("Added source '{}' to vector knowledge ({} chunks from {} chars)",
                sourceId, chunks.size(), content.length());
    }

    /**
     * Loads a text file and adds it as a knowledge source.
     * The file name is used as the source ID.
     */
    public void addDocument(Path filePath) throws IOException {
        String content = Files.readString(filePath);
        String sourceId = filePath.getFileName().toString();
        addSource(sourceId, content, Map.of("file", filePath.toString()));
    }

    @Override
    public void removeSource(String sourceId) {
        // Vector stores don't always support deletion by metadata
        // Mark as removed in our tracking
        sourceIds.remove(sourceId);
        logger.info("Source '{}' marked as removed (vector entries may persist)", sourceId);
    }

    @Override
    public List<String> getSources() {
        return new ArrayList<>(sourceIds);
    }

    @Override
    public boolean hasSource(String sourceId) {
        return sourceId != null && sourceIds.contains(sourceId);
    }

    /**
     * Splits content into chunks for better vector retrieval.
     */
    private List<Document> chunkContent(String sourceId, String content, Map<String, Object> metadata) {
        List<Document> chunks = new ArrayList<>();
        Map<String, Object> docMetadata = new HashMap<>();
        docMetadata.put("sourceId", sourceId);
        if (metadata != null) docMetadata.putAll(metadata);

        // Simple chunking by paragraphs or fixed size
        String[] paragraphs = content.split("\n\n+");
        StringBuilder currentChunk = new StringBuilder();
        int chunkIndex = 0;

        for (String paragraph : paragraphs) {
            if (currentChunk.length() + paragraph.length() > MAX_CHUNK_SIZE && currentChunk.length() > 0) {
                Map<String, Object> chunkMeta = new HashMap<>(docMetadata);
                chunkMeta.put("chunkIndex", chunkIndex++);
                chunks.add(new Document(currentChunk.toString(), chunkMeta));
                currentChunk = new StringBuilder();
            }
            if (currentChunk.length() > 0) currentChunk.append("\n\n");
            currentChunk.append(paragraph);
        }

        // Add remaining content
        if (currentChunk.length() > 0) {
            Map<String, Object> chunkMeta = new HashMap<>(docMetadata);
            chunkMeta.put("chunkIndex", chunkIndex);
            chunks.add(new Document(currentChunk.toString(), chunkMeta));
        }

        return chunks;
    }
}
