package ai.intelliswarm.swarmai.knowledge.rag;

import ai.intelliswarm.swarmai.api.PublicApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Vector + BM25 retrieval fused via Reciprocal Rank Fusion (RRF).
 *
 * <p>Why hybrid: pure vector search misses exact identifiers (algorithm
 * names, statute numbers, proper nouns) that the embedding model misranks.
 * BM25 catches them. Empirical: disabling BM25 in the 2026-04-26 IntelliDoc
 * eval dropped chunk-hit from 14% to 8% on a 225-question battery.
 *
 * <p>Why RRF over linear combination: RRF doesn't need score calibration
 * between the two retrievers (cosine [-1,1] vs BM25 [0,unbounded]). The K=60
 * constant is the standard choice from the original RRF paper.
 */
@PublicApi(since = "1.0.9")
public final class HybridRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);
    private static final int RRF_K = 60;
    private static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{2,}");
    private static final Set<String> STOP = Set.of(
            "the","a","an","and","or","but","is","are","was","were","be","been","being",
            "this","that","these","those","of","in","to","for","on","at","with","by",
            "from","as","if","then","than","which","what","how","why","when","where",
            "who","do","does","did","have","has","had","not","no","so");

    private final VectorStore vectorStore;
    private final List<ChunkRecord> chunkIndex = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Integer> docFrequency = new HashMap<>();
    private final boolean useBm25;

    public HybridRetriever(VectorStore vectorStore, boolean useBm25) {
        this.vectorStore = Objects.requireNonNull(vectorStore, "vectorStore");
        this.useBm25 = useBm25;
    }

    /**
     * Register a chunk so BM25 can score it. Vector search uses the
     * {@link VectorStore} directly; this index is the keyword-side complement.
     */
    public void index(String chunkId, String sourceId, String text, int chunkIndex) {
        Set<String> tokens = tokenize(text);
        for (String t : tokens) docFrequency.merge(t, 1, Integer::sum);
        this.chunkIndex.add(new ChunkRecord(chunkId, sourceId, text, tokens, chunkIndex));
    }

    /** Drop a chunk from the BM25 index. Vector store deletion is the caller's job. */
    public void unindex(String chunkId) {
        chunkIndex.removeIf(c -> c.chunkId.equals(chunkId));
    }

    /** Top-K chunks fused from vector + BM25. */
    public List<ChunkRecord> retrieve(String query, int topK) {
        if (query == null || query.isBlank() || topK <= 0) return List.of();
        Map<String, Double> fused = new LinkedHashMap<>();
        Map<String, ChunkRecord> seen = new HashMap<>();

        // Vector retrieval
        try {
            int rank = 0;
            for (Document d : vectorStore.similaritySearch(SearchRequest.builder().query(query).topK(topK).build())) {
                rank++;
                String id = d.getId();
                fused.merge(id, 1.0 / (RRF_K + rank), Double::sum);
                ChunkRecord cached = findById(id);
                if (cached != null) seen.putIfAbsent(id, cached);
                else seen.putIfAbsent(id, new ChunkRecord(id,
                        (String) d.getMetadata().getOrDefault("sourceId", "unknown"),
                        (String) d.getMetadata().getOrDefault("originalText", d.getText()),
                        Set.of(),
                        ((Number) d.getMetadata().getOrDefault("chunk", 0)).intValue()));
            }
        } catch (Exception e) {
            log.warn("vector retrieval failed: {}", e.getMessage());
        }

        // BM25 keyword retrieval
        if (useBm25 && !chunkIndex.isEmpty()) {
            Map<String, Double> qIdf = idfWeights(query);
            List<Map.Entry<ChunkRecord, Double>> scored = new ArrayList<>();
            for (ChunkRecord c : chunkIndex) {
                double s = bm25Score(qIdf, c.tokens);
                if (s > 0) scored.add(Map.entry(c, s));
            }
            scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            int rank = 0;
            for (var e : scored.subList(0, Math.min(topK, scored.size()))) {
                rank++;
                String id = e.getKey().chunkId;
                fused.merge(id, 1.0 / (RRF_K + rank), Double::sum);
                seen.putIfAbsent(id, e.getKey());
            }
        }

        return fused.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(topK)
                .map(e -> seen.get(e.getKey()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private ChunkRecord findById(String chunkId) {
        for (ChunkRecord c : chunkIndex) if (c.chunkId.equals(chunkId)) return c;
        return null;
    }

    private static Set<String> tokenize(String text) {
        if (text == null) return Set.of();
        Set<String> out = new HashSet<>();
        var m = WORD.matcher(text);
        while (m.find()) {
            String t = m.group().toLowerCase(Locale.ROOT);
            if (!STOP.contains(t)) out.add(stem(t));
        }
        return out;
    }

    private static String stem(String t) {
        if (t.length() <= 3) return t;
        if (t.endsWith("ization") && t.length() > 9) return t.substring(0, t.length() - 7);
        if (t.endsWith("ization") && t.length() > 9) return t.substring(0, t.length() - 7);
        if (t.endsWith("tion") && t.length() > 6) return t.substring(0, t.length() - 4);
        if (t.endsWith("ing")  && t.length() > 5) return t.substring(0, t.length() - 3);
        if (t.endsWith("ed")   && t.length() > 4) return t.substring(0, t.length() - 2);
        if (t.endsWith("ly")   && t.length() > 4) return t.substring(0, t.length() - 2);
        if (t.endsWith("er")   && t.length() > 4) return t.substring(0, t.length() - 2);
        if (t.endsWith("s")    && t.length() > 3 && !t.endsWith("ss")) return t.substring(0, t.length() - 1);
        return t;
    }

    private Map<String, Double> idfWeights(String query) {
        int N = Math.max(1, chunkIndex.size());
        Map<String, Double> weights = new HashMap<>();
        for (String t : tokenize(query)) {
            int df = docFrequency.getOrDefault(t, 0);
            weights.put(t, Math.log((N - df + 0.5) / (df + 0.5) + 1));
        }
        return weights;
    }

    private static double bm25Score(Map<String, Double> qIdf, Set<String> chunkTokens) {
        double s = 0;
        for (var e : qIdf.entrySet()) if (chunkTokens.contains(e.getKey())) s += e.getValue();
        return s;
    }

    /** Indexed chunk metadata used for BM25 + RRF fusion. */
    public record ChunkRecord(String chunkId, String sourceId, String text, Set<String> tokens, int chunkIndex) {}
}
