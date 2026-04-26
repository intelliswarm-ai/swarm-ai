package ai.intelliswarm.swarmai.knowledge.rag;

import ai.intelliswarm.swarmai.api.PublicApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * High-level RAG pipeline. Bake-in of the lessons from the 2026-04-26
 * IntelliDoc iteration battery — see
 * {@code intellidoc/docs/internal/EVAL_2026_04_26_FULL_ITERATIONS.md}.
 *
 * <p><b>Quick start:</b>
 * <pre>{@code
 * RagPipeline rag = RagPipeline.builder()
 *         .vectorStore(vectorStore)
 *         .chatClient(chatClient)
 *         .build();
 *
 * rag.ingestText("japan.md", Files.readString(Path.of("japan.md")));
 *
 * RagAnswer a = rag.query("What is the capital of Japan?");
 * System.out.println(a.answer());           // "The capital of Japan is Tokyo [source: japan.md #0]."
 * for (Citation c : a.citations()) {
 *     System.out.println(c.sourceId() + " #" + c.chunkIndex() + ": " + c.passage());
 * }
 * }</pre>
 *
 * <p>Defaults are tuned (chunk 800/100, top-K 5, hybrid retrieval, simple
 * prompt). Override via {@link Builder#config(RagConfig)}.
 *
 * <p>This class is thread-safe for query() but ingest*() should be called from
 * a single thread (or externally synchronised).
 */
@PublicApi(since = "1.0.9")
public final class RagPipeline {

    private static final Logger log = LoggerFactory.getLogger(RagPipeline.class);
    private static final Pattern CITATION_TAG = Pattern.compile("\\[source:\\s*([^#\\]]+?)\\s*#\\s*(\\d+)\\s*\\]");

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final RagConfig config;
    private final HybridRetriever retriever;
    private final RecursiveCharSplitter splitter;
    private final Map<String, List<Document>> bySource = new HashMap<>();

    private RagPipeline(Builder b) {
        this.vectorStore = Objects.requireNonNull(b.vectorStore, "vectorStore");
        this.chatClient = Objects.requireNonNull(b.chatClient, "chatClient");
        this.config = b.config != null ? b.config : RagConfig.defaults();
        this.retriever = new HybridRetriever(vectorStore, config.hybridRetrieval());
        this.splitter = new RecursiveCharSplitter(config.chunkSize(), config.chunkOverlap());
    }

    // ---------- ingestion ----------

    /** Ingest raw text. The {@code sourceId} is what citations refer to. */
    public void ingestText(String sourceId, String content) {
        ingestText(sourceId, content, Map.of());
    }

    public void ingestText(String sourceId, String content, Map<String, Object> metadata) {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(content, "content");
        if (content.isBlank()) return;
        if (bySource.containsKey(sourceId)) {
            removeSource(sourceId);
        }

        List<String> chunks = splitter.split(content);
        List<Document> docs = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            String chunkText = sanitizeUtf16(chunks.get(i));
            String chunkId = sourceId + "#" + i;
            String embeddedText = config.contextualPrefix()
                    ? "[" + sourceId + "] " + chunkText
                    : chunkText;
            Map<String, Object> meta = new HashMap<>(metadata);
            meta.put("sourceId", sourceId);
            meta.put("chunk", i);
            meta.put("originalText", chunkText);
            Document d = new Document(chunkId, embeddedText, meta);
            docs.add(d);
            retriever.index(chunkId, sourceId, chunkText, i);
        }
        try {
            vectorStore.add(docs);
        } catch (Exception e) {
            log.warn("vector store add failed for {}: {}", sourceId, e.getMessage());
        }
        bySource.put(sourceId, docs);
        log.info("ingested {} ({} chunks)", sourceId, docs.size());
    }

    /** Ingest a single text-readable file. Source ID = file name. */
    public void ingestFile(Path file) throws IOException {
        ingestText(file.getFileName().toString(), Files.readString(file));
    }

    /** Walk a folder and ingest every text-readable file. */
    public int ingestFolder(Path folder) throws IOException {
        int n = 0;
        try (var stream = Files.walk(folder)) {
            for (Path p : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.endsWith(".txt") && !name.endsWith(".md") && !name.endsWith(".json") && !name.endsWith(".csv")) continue;
                try {
                    ingestFile(p);
                    n++;
                } catch (Exception e) {
                    log.warn("skip {}: {}", p, e.getMessage());
                }
            }
        }
        log.info("ingestFolder({}) — {} files", folder, n);
        return n;
    }

    /** Drop a previously-ingested source. */
    public void removeSource(String sourceId) {
        List<Document> docs = bySource.remove(sourceId);
        if (docs != null) {
            for (Document d : docs) retriever.unindex(d.getId());
            try { vectorStore.delete(docs.stream().map(Document::getId).toList()); }
            catch (Exception e) { log.warn("vector store delete failed: {}", e.getMessage()); }
        }
    }

    // ---------- query ----------

    public RagAnswer query(String question) {
        Objects.requireNonNull(question, "question");
        long t0 = System.currentTimeMillis();

        var hits = retriever.retrieve(question, config.topK());
        String passages = formatPassages(hits);
        if (passages.length() > config.maxPassageChars()) {
            passages = passages.substring(0, config.maxPassageChars());
        }

        String answer;
        try {
            answer = chatClient.prompt()
                    .system(RagPrompts.SYSTEM)
                    .user(RagPrompts.userPrompt(passages, question))
                    .call()
                    .content();
            if (answer == null) answer = "";
            answer = answer.trim();
        } catch (Exception e) {
            log.warn("synthesis failed: {}", e.getMessage());
            answer = "synthesis failed: " + e.getMessage();
        }

        boolean refused = answer.contains(RagPrompts.REFUSAL) || answer.toLowerCase().contains("do not answer");
        List<Citation> citations = parseCitations(answer, hits);
        long elapsed = System.currentTimeMillis() - t0;
        return new RagAnswer(question, answer, citations, refused, elapsed);
    }

    private String formatPassages(List<HybridRetriever.ChunkRecord> hits) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (var h : hits) {
            i++;
            sb.append("--- Passage ").append(i)
              .append(" [source: ").append(h.sourceId()).append(" #").append(h.chunkIndex()).append("] ---\n")
              .append(h.text()).append("\n\n");
        }
        return sb.toString();
    }

    private List<Citation> parseCitations(String answer, List<HybridRetriever.ChunkRecord> hits) {
        if (answer == null || answer.isBlank()) return List.of();
        List<Citation> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Matcher m = CITATION_TAG.matcher(answer);
        while (m.find()) {
            String src = m.group(1).trim();
            int idx = Integer.parseInt(m.group(2));
            String key = src + "#" + idx;
            if (!seen.add(key)) continue;
            String passage = hits.stream()
                    .filter(h -> h.sourceId().equals(src) && h.chunkIndex() == idx)
                    .findFirst()
                    .map(h -> h.text().length() > 240 ? h.text().substring(0, 240) + "…" : h.text())
                    .orElse("");
            out.add(new Citation(src, idx, passage));
        }
        return out;
    }

    // ---------- helpers ----------

    /**
     * Strip lone UTF-16 surrogate pairs. PDFBox occasionally extracts math
     * symbols (𝒙, ෍) as unmatched surrogates which cause Chroma to reject
     * the entire batch with "invalid high surrogate in string". Replace with
     * U+FFFD so the rest of the chunk still indexes.
     */
    static String sanitizeUtf16(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                    sb.append(c).append(s.charAt(i + 1));
                    i++;
                } else sb.append('�');
            } else if (Character.isLowSurrogate(c)) {
                sb.append('�');
            } else sb.append(c);
        }
        return sb.toString();
    }

    // ---------- builder ----------

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private VectorStore vectorStore;
        private ChatClient chatClient;
        private RagConfig config;

        public Builder vectorStore(VectorStore v) { this.vectorStore = v; return this; }
        public Builder chatClient(ChatClient c) { this.chatClient = c; return this; }
        public Builder config(RagConfig c) { this.config = c; return this; }
        public RagPipeline build() { return new RagPipeline(this); }
    }
}
