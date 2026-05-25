package ai.intelliswarm.swarmai.rag.tool;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG ingest tool — extract text from a local file (PDF / TXT / MD / HTML),
 * split into chunks, embed, and write into the configured Spring AI VectorStore.
 *
 * <p>Pair this with {@code PdfDownloadTool} (downloads remote PDFs to disk)
 * and the existing {@code SemanticSearchTool} (reads back from the VectorStore).
 *
 * <p>Parameters:
 * <ul>
 *     <li>{@code path} — local file path (required)</li>
 *     <li>{@code source} — citation label stored in chunk metadata
 *                          (e.g. "arxiv:2401.12345"). Optional; defaults to file name.</li>
 *     <li>{@code chunk_size} — target tokens per chunk (default 800)</li>
 *     <li>{@code chunk_overlap} — token overlap between chunks (default 100)</li>
 *     <li>{@code metadata} — additional key/value metadata persisted with every chunk</li>
 * </ul>
 */
public class RagIngestTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(RagIngestTool.class);

    private static final long MAX_FILE_BYTES = 100L * 1024L * 1024L; // 100 MB
    private static final int DEFAULT_CHUNK_SIZE = 800;
    private static final int DEFAULT_CHUNK_OVERLAP = 100;

    private final VectorStore vectorStore;

    @Autowired(required = false)
    public RagIngestTool(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override public String getFunctionName() { return "rag_ingest"; }

    @Override
    public String getDescription() {
        return "Ingest a local document (PDF/TXT/MD/HTML) into the RAG vector store. "
             + "Extracts text, splits into chunks, embeds, and indexes. Returns the count of chunks added and the source label. "
             + "Use after downloading a paper with pdf_download.";
    }

    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.WORKSPACE_WRITE; }
    @Override public String getCategory() { return "rag"; }
    @Override public List<String> getTags() { return List.of("rag", "ingest", "embed", "vector"); }

    @Override
    public Object execute(Map<String, Object> parameters) {
        if (vectorStore == null) {
            return "Error: no VectorStore bean configured. Define a LuceneVectorStore (or other VectorStore) bean.";
        }
        String pathStr = asString(parameters.get("path"));
        if (pathStr == null || pathStr.isBlank()) {
            return "Error: 'path' is required";
        }
        if (pathStr.contains("..")) {
            return "Error: path traversal '..' is not allowed";
        }
        String source = asString(parameters.getOrDefault("source", pathStr));
        int chunkSize = asInt(parameters.get("chunk_size"), DEFAULT_CHUNK_SIZE);
        int chunkOverlap = asInt(parameters.get("chunk_overlap"), DEFAULT_CHUNK_OVERLAP);

        @SuppressWarnings("unchecked")
        Map<String, Object> extraMeta = parameters.get("metadata") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();

        try {
            Path path = Paths.get(pathStr).toAbsolutePath().normalize();
            if (!Files.exists(path)) return "Error: file not found: " + pathStr;
            if (Files.size(path) > MAX_FILE_BYTES) {
                return "Error: file too large (max " + MAX_FILE_BYTES + " bytes)";
            }

            String text = extractText(path);
            if (text == null || text.isBlank()) {
                return "Error: extracted text is empty for " + pathStr;
            }

            Map<String, Object> baseMeta = new HashMap<>();
            baseMeta.put("source", source);
            baseMeta.put("path", path.toString());
            baseMeta.putAll(extraMeta);

            Document seed = new Document(text, baseMeta);
            TokenTextSplitter splitter = new TokenTextSplitter(chunkSize, chunkOverlap,
                    /*keywordCount*/ 0, /*maxNumChunks*/ 10_000, /*keepSeparator*/ true);
            List<Document> chunks = splitter.apply(List.of(seed));

            // Tag every chunk with its index for citation back-references.
            int i = 0;
            for (Document c : chunks) {
                c.getMetadata().put("chunk_index", i++);
                c.getMetadata().putIfAbsent("source", source);
            }

            vectorStore.add(chunks);
            logger.info("RagIngest: source='{}' chunks={} (size={}/overlap={})",
                    source, chunks.size(), chunkSize, chunkOverlap);

            return buildResponse(source, path, chunks.size(), chunkSize, chunkOverlap);

        } catch (Exception e) {
            logger.error("RagIngest failed", e);
            return "Error: ingest failed — " + e.getMessage();
        }
    }

    private String extractText(Path path) throws IOException {
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".pdf")) {
            try (PDDocument doc = Loader.loadPDF(path.toFile())) {
                return new PDFTextStripper().getText(doc);
            }
        }
        // Fallback: treat as plain text. HTML stripping is left to the caller — keeping this tool small.
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private String buildResponse(String source, Path path, int chunks, int chunkSize, int chunkOverlap) {
        StringBuilder sb = new StringBuilder();
        sb.append("**RAG ingest complete**\n");
        sb.append("- source: ").append(source).append('\n');
        sb.append("- path: ").append(path).append('\n');
        sb.append("- chunks indexed: ").append(chunks).append('\n');
        sb.append("- chunk size / overlap: ").append(chunkSize).append(" / ").append(chunkOverlap).append(" tokens\n");
        return sb.toString();
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();
        props.put("path", Map.of("type", "string", "description", "Local file path (PDF/TXT/MD/HTML)"));
        props.put("source", Map.of("type", "string", "description", "Citation label stored on every chunk (e.g. arxiv:2401.12345)"));
        props.put("chunk_size", Map.of("type", "integer", "description", "Target tokens per chunk (default 800)"));
        props.put("chunk_overlap", Map.of("type", "integer", "description", "Token overlap between chunks (default 100)"));
        props.put("metadata", Map.of("type", "object", "description", "Extra key/value metadata stored on every chunk"));
        schema.put("properties", props);
        schema.put("required", new String[]{"path"});
        return schema;
    }

    @Override public boolean isAsync() { return false; }
    @Override public String getTriggerWhen() { return "User wants to add a paper/document to the RAG store so it can be searched semantically."; }
    @Override public String getAvoidWhen() { return "User just wants to read a file without indexing it — use file_read or pdf_read."; }

    private static String asString(Object v) { return v == null ? null : v.toString(); }
    private static int asInt(Object v, int dflt) {
        if (v instanceof Number n) return n.intValue();
        try { return v == null ? dflt : Integer.parseInt(v.toString()); } catch (Exception ignored) { return dflt; }
    }
}
