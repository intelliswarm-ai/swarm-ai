package ai.intelliswarm.swarmai.rag.store;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Spring AI {@link VectorStore} backed by Apache Lucene's KnnFloatVectorField.
 *
 * <p>One Lucene index per store; documents are persisted to disk under {@code indexPath} and
 * survive JVM restarts. Vector similarity uses cosine. Metadata is serialized via
 * Lucene {@link StoredField}s, one per key.
 *
 * <p>This is intentionally minimal — it is not a full RAG pipeline. For HyDE / reranking
 * / dedup, layer enhancers on top of the returned {@link Document}s, or swap in
 * Chroma/PGVector if you need a managed service.
 */
public class LuceneVectorStore implements VectorStore, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(LuceneVectorStore.class);

    private static final String F_ID = "id";
    private static final String F_CONTENT = "content";
    private static final String F_VECTOR = "vector";
    private static final String F_META_PREFIX = "meta_";
    private static final int DEFAULT_TOP_K = 5;

    private final Directory directory;
    private final EmbeddingModel embeddingModel;
    private final int vectorDimensions;

    public LuceneVectorStore(Path indexPath, EmbeddingModel embeddingModel) {
        if (indexPath == null) throw new IllegalArgumentException("indexPath is required");
        if (embeddingModel == null) throw new IllegalArgumentException("embeddingModel is required");
        try {
            this.directory = FSDirectory.open(indexPath);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot open Lucene index at " + indexPath, e);
        }
        this.embeddingModel = embeddingModel;
        this.vectorDimensions = embeddingModel.dimensions();
        logger.info("LuceneVectorStore ready at {} (dim={})", indexPath, vectorDimensions);
    }

    @Override
    public void add(List<Document> documents) {
        if (documents == null || documents.isEmpty()) return;
        IndexWriterConfig cfg = new IndexWriterConfig(new StandardAnalyzer());
        cfg.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        try (IndexWriter writer = new IndexWriter(directory, cfg)) {
            for (Document doc : documents) {
                String id = doc.getId() != null ? doc.getId() : UUID.randomUUID().toString();
                float[] vector = embed(doc.getText());
                org.apache.lucene.document.Document lDoc = new org.apache.lucene.document.Document();
                lDoc.add(new StringField(F_ID, id, Field.Store.YES));
                lDoc.add(new TextField(F_CONTENT, doc.getText() == null ? "" : doc.getText(), Field.Store.YES));
                lDoc.add(new KnnFloatVectorField(F_VECTOR, vector, VectorSimilarityFunction.COSINE));
                if (doc.getMetadata() != null) {
                    for (Map.Entry<String, Object> e : doc.getMetadata().entrySet()) {
                        if (e.getValue() != null) {
                            lDoc.add(new StoredField(F_META_PREFIX + e.getKey(), e.getValue().toString()));
                        }
                    }
                }
                // Upsert by id
                writer.updateDocument(new Term(F_ID, id), lDoc);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Lucene add() failed", e);
        }
    }

    @Override
    public void delete(List<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        IndexWriterConfig cfg = new IndexWriterConfig(new StandardAnalyzer());
        try (IndexWriter writer = new IndexWriter(directory, cfg)) {
            for (String id : ids) writer.deleteDocuments(new Term(F_ID, id));
        } catch (IOException e) {
            throw new IllegalStateException("Lucene delete() failed", e);
        }
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
        // Filter pushdown is not implemented — metadata filtering happens in-memory after search.
        throw new UnsupportedOperationException("Filter-based delete is not implemented in LuceneVectorStore");
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        String query = request.getQuery();
        int topK = request.getTopK() > 0 ? request.getTopK() : DEFAULT_TOP_K;
        double minScore = request.getSimilarityThreshold();

        float[] queryVector = embed(query);
        List<Document> results = new ArrayList<>();

        try (DirectoryReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            KnnFloatVectorQuery knn = new KnnFloatVectorQuery(F_VECTOR, queryVector, topK);
            TopDocs hits = searcher.search(knn, topK);
            for (ScoreDoc hit : hits.scoreDocs) {
                org.apache.lucene.document.Document lDoc = searcher.storedFields().document(hit.doc);
                String id = lDoc.get(F_ID);
                String content = lDoc.get(F_CONTENT);
                Map<String, Object> meta = new HashMap<>();
                lDoc.getFields().forEach(f -> {
                    if (f.name().startsWith(F_META_PREFIX)) {
                        meta.put(f.name().substring(F_META_PREFIX.length()), f.stringValue());
                    }
                });
                // Lucene returns a raw distance score for KNN; clamp/normalize loosely.
                double score = Math.max(0.0, Math.min(1.0, hit.score));
                if (score < minScore) continue;
                meta.put("distance", 1.0 - score);
                Document d = new Document(id, content == null ? "" : content, meta);
                results.add(d);
            }
        } catch (IOException e) {
            // First search before any add() — index has no segments yet
            if (e.getMessage() != null && e.getMessage().contains("no segments")) {
                return List.of();
            }
            throw new IllegalStateException("Lucene similaritySearch() failed", e);
        }
        return results;
    }

    public int documentCount() {
        try (DirectoryReader reader = DirectoryReader.open(directory)) {
            return reader.numDocs();
        } catch (IOException e) {
            return 0;
        }
    }

    private float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return new float[vectorDimensions];
        }
        return embeddingModel.embed(text);
    }

    @Override
    public void close() throws IOException {
        directory.close();
    }
}
