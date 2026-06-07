package ai.intelliswarm.swarmai.tool.code.web;

import ai.intelliswarm.swarmai.api.PublicApi;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Fetch a URL and return its text body, with HTML stripped to plaintext.
 *
 * <p>Distinct from {@code WebScrapeTool} (which is HTML-structured scraping)
 * and {@code WebSearchTool} (which is search-engine queries). This tool is the
 * minimal "give me the text of THIS url" primitive — useful for reading
 * documentation, GitHub READMEs, blog posts referenced in code comments,
 * or release-notes pages.
 */
@PublicApi(since = "1.0.26")
@Component
public class WebFetchTool implements BaseTool {

    private static final Pattern SCRIPT_STYLE = Pattern.compile(
            "<(script|style)\\b[^>]*>.*?</\\1>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE = Pattern.compile("\\n{3,}");

    private final HttpClient httpClient;

    public WebFetchTool() {
        this(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    /** Test-injectable constructor. */
    public WebFetchTool(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override public String getFunctionName() { return "web_fetch"; }

    @Override
    public String getDescription() {
        return "Fetch a URL and return its text content (HTML stripped). Use to read documentation pages, "
             + "GitHub READMEs, blog posts, or any HTTP resource the user references.";
    }

    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.DANGEROUS; }
    @Override public String getCategory() { return "web"; }
    @Override public List<String> getTags() { return List.of("web", "http", "fetch", "docs"); }
    @Override public boolean isAsync() { return false; }
    @Override public int getMaxResponseLength() { return 20_000; }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String url = (String) parameters.get("url");
        if (url == null || url.isBlank()) return "Error: 'url' is required";
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "Error: url must start with http:// or https://";
        }
        boolean raw = Boolean.TRUE.equals(parameters.get("raw"));

        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "SwarmAI/1.0 (+https://github.com/intelliswarm-ai/swarm-ai)")
                    .header("Accept", "text/html,application/xhtml+xml,text/plain,application/json;q=0.9,*/*;q=0.5")
                    .GET()
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            String body = resp.body();
            String contentType = resp.headers().firstValue("content-type").orElse("");

            String text = raw || isPlain(contentType) ? body : htmlToText(body);

            StringBuilder sb = new StringBuilder();
            sb.append("URL: ").append(url).append('\n');
            sb.append("Status: ").append(resp.statusCode()).append('\n');
            sb.append("Content-Type: ").append(contentType).append('\n');
            sb.append("Length: ").append(text.length()).append(" chars\n");
            sb.append("---\n").append(text);
            return sb.toString();
        } catch (Exception e) {
            return "Error fetching " + url + ": " + e.getMessage();
        }
    }

    static boolean isPlain(String ct) {
        String low = ct.toLowerCase();
        return low.contains("text/plain") || low.contains("application/json")
                || low.contains("text/markdown") || low.contains("application/xml")
                || low.contains("text/x-") || low.contains("application/yaml");
    }

    static String htmlToText(String html) {
        String s = SCRIPT_STYLE.matcher(html).replaceAll("");
        s = TAG.matcher(s).replaceAll("");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&")
             .replace("&lt;", "<").replace("&gt;", ">")
             .replace("&quot;", "\"").replace("&#39;", "'");
        s = WHITESPACE.matcher(s).replaceAll("\n\n");
        return s.trim();
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("url", Map.of("type", "string", "description", "Full http(s) URL."));
        props.put("raw", Map.of(
                "type", "boolean",
                "description", "Return raw response body without HTML stripping (default false).",
                "default", false));
        schema.put("properties", props);
        schema.put("required", new String[]{"url"});
        return schema;
    }
}
