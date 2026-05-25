package ai.intelliswarm.swarmai.rag.tool;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Download a binary file (typically a PDF) from an HTTP/HTTPS URL to a local directory.
 *
 * <p>Distinct from {@code WebFetchTool} (which strips HTML to text and is unsuitable for
 * binary content). The downloaded file's absolute path is returned so an agent can
 * pass it straight to {@code rag_ingest}.
 *
 * <p>Parameters:
 * <ul>
 *     <li>{@code url} — http(s) URL to download (required)</li>
 *     <li>{@code dir} — destination directory; created if missing (default {@code papers/})</li>
 *     <li>{@code filename} — override filename; default is derived from the URL</li>
 * </ul>
 */
public class PdfDownloadTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(PdfDownloadTool.class);
    private static final long MAX_BYTES = 100L * 1024L * 1024L; // 100 MB
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient httpClient;
    private final Path defaultDir;

    public PdfDownloadTool() {
        this(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build(),
            Paths.get("papers"));
    }

    public PdfDownloadTool(HttpClient httpClient, Path defaultDir) {
        this.httpClient = httpClient;
        this.defaultDir = defaultDir;
    }

    @Override public String getFunctionName() { return "pdf_download"; }

    @Override
    public String getDescription() {
        return "Download a PDF (or other binary) from an HTTP(S) URL. Saves under the "
             + "application's papers directory and returns the absolute path so it can be "
             + "passed directly to rag_ingest. Pass ONLY the `url` parameter.";
    }

    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.WORKSPACE_WRITE; }
    @Override public String getCategory() { return "rag"; }
    @Override public List<String> getTags() { return List.of("pdf", "download", "binary", "http"); }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String url = asString(parameters.get("url"));
        if (url == null || url.isBlank()) return "Error: 'url' is required";
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "Error: url must start with http:// or https://";
        }

        // Ignore the dir parameter — LLM agents tend to hallucinate paths like /app, /tmp,
        // /workspace etc. that aren't writable in the host environment. Always use the
        // application-configured papers directory (defaults to ./papers).
        String filenameOverride = asString(parameters.get("filename"));

        try {
            Path dir = defaultDir.toAbsolutePath().normalize();
            Files.createDirectories(dir);

            String filename = filenameOverride != null && !filenameOverride.isBlank()
                    ? filenameOverride
                    : deriveFilename(url);
            Path target = dir.resolve(filename);

            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("User-Agent", "SwarmAI-Rag/1.0 (+https://intelliswarm.ai)")
                    .header("Accept", "application/pdf,application/octet-stream;q=0.9,*/*;q=0.5")
                    .GET()
                    .build();

            HttpResponse<Path> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofFile(
                    target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE));

            if (resp.statusCode() / 100 != 2) {
                Files.deleteIfExists(target);
                return "Error: HTTP " + resp.statusCode() + " for " + url;
            }
            long size = Files.size(target);
            if (size > MAX_BYTES) {
                Files.deleteIfExists(target);
                return "Error: download exceeded max size (" + MAX_BYTES + " bytes)";
            }
            if (size == 0) {
                Files.deleteIfExists(target);
                return "Error: downloaded file is empty";
            }

            String contentType = resp.headers().firstValue("content-type").orElse("");
            logger.info("PdfDownload: {} -> {} ({} bytes, {})", url, target, size, contentType);

            return buildResponse(url, target, size, contentType);
        } catch (Exception e) {
            logger.error("PdfDownload failed for {}", url, e);
            return "Error: download failed — " + e.getMessage();
        }
    }

    private static String deriveFilename(String url) {
        String tail = url;
        int q = tail.indexOf('?');
        if (q >= 0) tail = tail.substring(0, q);
        int slash = tail.lastIndexOf('/');
        if (slash >= 0 && slash < tail.length() - 1) tail = tail.substring(slash + 1);
        if (tail.isBlank()) tail = "download";
        // arXiv "/pdf/2401.12345" has no extension — append one.
        if (!tail.toLowerCase().endsWith(".pdf")
                && !tail.contains(".")) {
            tail = tail + ".pdf";
        }
        // Strip unsafe chars
        return tail.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String buildResponse(String url, Path target, long size, String contentType) {
        StringBuilder sb = new StringBuilder();
        sb.append("**Download complete**\n");
        sb.append("- url: ").append(url).append('\n');
        sb.append("- saved_to: ").append(target).append('\n');
        sb.append("- size: ").append(size).append(" bytes\n");
        sb.append("- content_type: ").append(contentType.isBlank() ? "(unknown)" : contentType).append('\n');
        return sb.toString();
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();
        props.put("url", Map.of("type", "string", "description", "http(s) URL of the PDF or binary"));
        props.put("filename", Map.of("type", "string", "description", "Optional override filename (defaults to URL tail)"));
        schema.put("properties", props);
        schema.put("required", new String[]{"url"});
        return schema;
    }

    @Override public boolean isAsync() { return false; }
    @Override public boolean isDynamic() { return true; }
    @Override public String getTriggerWhen() { return "User has a URL to a PDF (e.g. an arXiv PDF link) and needs the file saved locally before indexing."; }
    @Override public String getAvoidWhen() { return "User wants the *text* of a web page (use web_fetch) or a search engine query (use web_search)."; }

    private static String asString(Object v) { return v == null ? null : v.toString(); }
}
