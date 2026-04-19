package ai.intelliswarm.swarmai.tool.common;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

/**
 * HTTP Request Tool — general-purpose REST API client for agents.
 *
 * Supports: GET, POST, PUT, DELETE, PATCH with custom headers, body, and query params.
 * Features: JSON response formatting, authentication helpers, SSRF prevention, timeout.
 */
@Component
public class HttpRequestTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(HttpRequestTool.class);

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final Set<String> ALLOWED_METHODS = Set.of("GET", "POST", "PUT", "DELETE");

    private static final List<String> BLOCKED_HOST_PREFIXES = List.of(
        "127.", "10.", "192.168.", "172.16.", "172.17.", "172.18.", "172.19.",
        "172.20.", "172.21.", "172.22.", "172.23.", "172.24.", "172.25.",
        "172.26.", "172.27.", "172.28.", "172.29.", "172.30.", "172.31.",
        "0.", "169.254."
    );

    private static final Set<String> BLOCKED_HOSTS = Set.of(
        "localhost", "metadata.google.internal", "169.254.169.254"
    );

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public HttpRequestTool() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public String getFunctionName() {
        return "http_request";
    }

    @Override
    public String getDescription() {
        return "Make HTTP requests to REST APIs. Supports GET, POST, PUT, DELETE with " +
               "custom headers, request body, and authentication. Returns status code, headers, and response body.";
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String url = (String) parameters.get("url");
        String method = ((String) parameters.getOrDefault("method", "GET")).toUpperCase();
        String body = (String) parameters.getOrDefault("body", null);
        Object rawHeaders = parameters.get("headers");
        Map<String, Object> parsedHeaders = rawHeaders == null
            ? java.util.Map.of()
            : ai.intelliswarm.swarmai.tool.common.config.SpringAiToolBindingSupport.parseJsonMap(rawHeaders);
        Map<String, String> headers = new HashMap<>();
        parsedHeaders.forEach((k, v) -> headers.put(k, v == null ? null : v.toString()));
        String authToken = (String) parameters.getOrDefault("auth_token", null);

        logger.info("HttpRequestTool: {} {} (body: {}, headers: {}, auth: {})",
            method, url, body != null ? body.length() + " chars" : "none",
            headers != null ? headers.size() + " headers" : "none",
            authToken != null ? "Bearer ***" : "none");

        try {
            // 1. Validate inputs
            String inputError = validateInputs(url, method);
            if (inputError != null) {
                return "Error: " + inputError;
            }

            // 2. Security check (SSRF prevention)
            String securityError = checkUrlSecurity(url);
            if (securityError != null) {
                return "Error: " + securityError;
            }

            // 3. Build request
            HttpHeaders httpHeaders = buildHeaders(headers, authToken, body);
            HttpEntity<String> requestEntity = new HttpEntity<>(body, httpHeaders);
            HttpMethod httpMethod = HttpMethod.valueOf(method);

            // 4. Execute request
            ResponseEntity<String> response = restTemplate.exchange(url, httpMethod, requestEntity, String.class);

            // 5. Build response
            return buildResponse(url, method, response);

        } catch (HttpClientErrorException | HttpServerErrorException e) {
            return buildErrorResponse(url, method, e.getStatusCode().value(), e.getStatusText(), e.getResponseBodyAsString());
        } catch (Exception e) {
            logger.error("Error executing HTTP request: {} {}", method, url, e);
            return "Error: HTTP request failed: " + e.getMessage();
        }
    }

    // Domains the LLM commonly hallucinates — these never resolve to real services
    private static final Set<String> FAKE_DOMAINS = Set.of(
        "example.com", "example.org", "example.net",
        "api.example.com", "www.example.com",
        "placeholder.com", "test.com", "fake.com",
        "api.placeholder.com", "data.example.com"
    );

    // Patterns that indicate a hallucinated domain: api.{topic}.com
    private static final java.util.regex.Pattern HALLUCINATED_API_PATTERN =
        java.util.regex.Pattern.compile("^api\\.[a-z]+(?:data|market|cloud|stock|pricing|analytics|search|info)\\w*\\.com$", java.util.regex.Pattern.CASE_INSENSITIVE);

    private String validateInputs(String url, String method) {
        if (url == null || url.trim().isEmpty()) {
            return "URL is required";
        }

        if (!ALLOWED_METHODS.contains(method)) {
            return "Invalid HTTP method: '" + method + "'. Allowed: GET, POST, PUT, DELETE";
        }

        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                return "Only http and https URLs are supported";
            }
            String host = uri.getHost();
            if (host == null || host.isEmpty()) {
                return "URL must have a valid host";
            }

            // Reject hallucinated/placeholder URLs
            String hostLower = host.toLowerCase();
            if (FAKE_DOMAINS.contains(hostLower)) {
                return "Rejected: '" + host + "' is a placeholder domain. Use a REAL URL. " +
                    "Try: Wikipedia API (en.wikipedia.org/api/rest_v1/page/summary/TOPIC), " +
                    "GitHub API (api.github.com/search/repositories?q=TOPIC), " +
                    "or HN Algolia (hn.algolia.com/api/v1/search?query=TOPIC)";
            }

            // Reject hallucinated api.{something}.com patterns
            if (HALLUCINATED_API_PATTERN.matcher(hostLower).matches()) {
                return "Rejected: '" + host + "' appears to be a hallucinated API domain (not a real service). " +
                    "Use REAL, known-good API endpoints instead of inventing domain names.";
            }

        } catch (URISyntaxException e) {
            return "Invalid URL format: " + e.getMessage();
        }

        return null;
    }

    private String checkUrlSecurity(String url) {
        try {
            URI uri = new URI(url.trim());
            String host = uri.getHost().toLowerCase();

            if (BLOCKED_HOSTS.contains(host)) {
                return "Access denied: internal/private URLs are not allowed";
            }

            for (String prefix : BLOCKED_HOST_PREFIXES) {
                if (host.startsWith(prefix)) {
                    return "Access denied: private IP addresses are not allowed";
                }
            }

            try {
                InetAddress address = InetAddress.getByName(host);
                if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()) {
                    return "Access denied: URL resolves to a private/local address";
                }
            } catch (Exception e) {
                logger.debug("DNS resolution failed for {}: {}", host, e.getMessage());
            }

        } catch (URISyntaxException e) {
            return "Invalid URL: " + e.getMessage();
        }
        return null;
    }

    private HttpHeaders buildHeaders(Map<String, String> customHeaders, String authToken, String body) {
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.set("User-Agent", "SwarmAI/1.0");
        httpHeaders.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN, MediaType.ALL));

        // Set content type if body is present
        if (body != null && !body.isEmpty()) {
            // Auto-detect JSON body
            if (body.trim().startsWith("{") || body.trim().startsWith("[")) {
                httpHeaders.setContentType(MediaType.APPLICATION_JSON);
            } else {
                httpHeaders.setContentType(MediaType.TEXT_PLAIN);
            }
        }

        // Apply custom headers (can override defaults)
        if (customHeaders != null) {
            customHeaders.forEach(httpHeaders::set);
        }

        // Apply Bearer token auth
        if (authToken != null && !authToken.isEmpty()) {
            httpHeaders.setBearerAuth(authToken);
        }

        return httpHeaders;
    }

    private String buildResponse(String url, String method, ResponseEntity<String> response) {
        StringBuilder result = new StringBuilder();

        result.append("**HTTP Response**\n");
        result.append("**Request:** ").append(method).append(" ").append(url).append("\n");
        result.append("**Status:** ").append(response.getStatusCode().value())
              .append(" ").append(((HttpStatusCode) response.getStatusCode()).value()).append("\n");

        // Content type
        MediaType contentType = response.getHeaders().getContentType();
        if (contentType != null) {
            result.append("**Content-Type:** ").append(contentType).append("\n");
        }

        result.append("\n---\n\n");

        // Body
        String responseBody = response.getBody();
        if (responseBody == null || responseBody.isEmpty()) {
            result.append("(empty response body)\n");
        } else {
            // Try to pretty-print JSON
            result.append(formatResponseBody(responseBody, contentType));
        }

        return truncateResponse(result.toString());
    }

    private String buildErrorResponse(String url, String method, int statusCode, String statusText, String body) {
        StringBuilder result = new StringBuilder();

        result.append("**HTTP Error Response**\n");
        result.append("**Request:** ").append(method).append(" ").append(url).append("\n");
        result.append("**Status:** ").append(statusCode).append(" ").append(statusText).append("\n");
        result.append("\n---\n\n");

        if (body != null && !body.isEmpty()) {
            result.append(formatResponseBody(body, null));
        } else {
            result.append("(no response body)\n");
        }

        return truncateResponse(result.toString());
    }

    private String formatResponseBody(String body, MediaType contentType) {
        // Try JSON pretty-printing
        boolean isJson = (contentType != null && contentType.includes(MediaType.APPLICATION_JSON))
            || body.trim().startsWith("{") || body.trim().startsWith("[");

        if (isJson) {
            try {
                Object json = objectMapper.readValue(body, Object.class);
                return objectMapper.writeValueAsString(json);
            } catch (Exception e) {
                // Not valid JSON, return as-is
            }
        }

        return body;
    }

    private String truncateResponse(String response) {
        int max = getMaxResponseLength();
        if (response.length() > max) {
            return response.substring(0, max) + "\n\n[... response truncated at " + max + " chars ...]";
        }
        return response;
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> url = new HashMap<>();
        url.put("type", "string");
        url.put("description", "The URL to send the request to (must be http or https)");
        properties.put("url", url);

        Map<String, Object> method = new HashMap<>();
        method.put("type", "string");
        method.put("description", "HTTP method: GET, POST, PUT, DELETE (default: GET)");
        method.put("default", "GET");
        method.put("enum", List.of("GET", "POST", "PUT", "DELETE"));
        properties.put("method", method);

        Map<String, Object> body = new HashMap<>();
        body.put("type", "string");
        body.put("description", "Request body (for POST, PUT, PATCH). JSON auto-detected.");
        properties.put("body", body);

        Map<String, Object> headers = new HashMap<>();
        headers.put("type", "string");
        headers.put("description", "Custom HTTP headers as a JSON object string, e.g. '{\"X-Trace\":\"abc\"}'.");
        properties.put("headers", headers);

        Map<String, Object> authToken = new HashMap<>();
        authToken.put("type", "string");
        authToken.put("description", "Bearer token for Authorization header");
        properties.put("auth_token", authToken);

        schema.put("properties", properties);
        schema.put("required", new String[]{"url"});

        return schema;
    }

    @Override
    public boolean isAsync() {
        return false;
    }

    @Override
    public String getTriggerWhen() {
        return "User needs to call a REST API, make HTTP requests, or interact with web services.";
    }

    @Override
    public String getAvoidWhen() {
        return "User needs web page content (use web_scrape) or search results (use web_search).";
    }

    @Override
    public String getCategory() {
        return "web";
    }

    @Override
    public List<String> getTags() {
        return List.of("http", "api", "rest", "request");
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return Map.of(
            "type", "markdown",
            "description", "HTTP response with status code, headers, and formatted response body (JSON pretty-printed when applicable)"
        );
    }

    @Override
    public int getMaxResponseLength() {
        return 12000;
    }

    // Request record for Spring AI function binding
    // `headers` is a JSON object string so Spring AI's auto-generated function schema stays
    // OpenAI-compatible. Parsing happens inside execute() via SpringAiToolBindingSupport.
    public record Request(String url, String method, String body, String headers, String authToken) {}
}
