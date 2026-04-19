package ai.intelliswarm.swarmai.tool.integrations;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OpenAPI Toolkit — a universal adapter that turns any OpenAPI 3.x spec into callable tools.
 *
 * <p>One tool, two operations:
 * <ol>
 *   <li>{@code list_operations} — parses the spec and enumerates every operation
 *       (method + path + summary + parameters). Lets an agent discover what it can call.</li>
 *   <li>{@code invoke} — executes a single {@code operationId} with path / query / header /
 *       body parameters. Path params are substituted into the URL template, query params
 *       are URL-encoded, and an optional bearer token is attached.</li>
 * </ol>
 *
 * <p>Specs can be loaded from a URL or an inline string. Results are cached per-spec-location
 * to avoid re-parsing on every call.
 *
 * <p>Permission level is {@code DANGEROUS} because this tool can call arbitrary external
 * endpoints (including POST/DELETE) on behalf of the agent. Gate accordingly.
 */
@Component
public class OpenApiToolkit implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(OpenApiToolkit.class);
    private static final int MAX_BODY_PREVIEW = 4000;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final Map<String, OpenAPI> specCache = new ConcurrentHashMap<>();

    public OpenApiToolkit() {
        this(new RestTemplate(), new ObjectMapper());
    }

    OpenApiToolkit(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Override public String getFunctionName() { return "openapi_call"; }

    @Override
    public String getDescription() {
        return "Universal OpenAPI 3.x client. Use operation='list_operations' to discover every operationId " +
               "in a spec (method/path/summary/params), then operation='invoke' with the operationId and " +
               "its parameters. Spec loaded from 'spec_url' (preferred) or inline 'spec'. Optional " +
               "'bearer_token' attaches Authorization: Bearer. Permission: DANGEROUS — tool can call " +
               "any endpoint described by the spec.";
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String operation = asString(parameters.getOrDefault("operation", "list_operations")).toLowerCase();
        try {
            return switch (operation) {
                case "list_operations" -> listOperations(parameters);
                case "invoke"          -> invoke(parameters);
                default -> "Error: unknown operation '" + operation +
                           "'. Use 'list_operations' or 'invoke'.";
            };
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        } catch (RestClientException e) {
            logger.warn("OpenApiToolkit network error: {}", e.getMessage());
            return "Error: request failed — " + e.getMessage();
        } catch (Exception e) {
            logger.error("OpenApiToolkit unexpected error", e);
            return "Error: " + e.getClass().getSimpleName() + " — " + e.getMessage();
        }
    }

    // ---------- list_operations ----------

    private String listOperations(Map<String, Object> parameters) {
        OpenAPI spec = loadSpec(parameters);
        StringBuilder out = new StringBuilder();
        String title = spec.getInfo() != null && spec.getInfo().getTitle() != null
            ? spec.getInfo().getTitle() : "(untitled API)";
        String version = spec.getInfo() != null && spec.getInfo().getVersion() != null
            ? spec.getInfo().getVersion() : "?";
        String baseServer = resolveServer(spec);

        out.append("**").append(title).append("** v").append(version).append('\n');
        if (baseServer != null) out.append("Base URL: ").append(baseServer).append("\n\n");

        Paths paths = spec.getPaths();
        if (paths == null || paths.isEmpty()) {
            return out.append("(no operations declared)").toString();
        }

        int count = 0;
        for (Map.Entry<String, PathItem> pe : paths.entrySet()) {
            String path = pe.getKey();
            for (Map.Entry<PathItem.HttpMethod, Operation> me : pe.getValue().readOperationsMap().entrySet()) {
                Operation op = me.getValue();
                String opId = op.getOperationId();
                if (opId == null || opId.isBlank()) {
                    // Synthesize a stable ID from method+path so agents can always reference it.
                    opId = me.getKey().name().toLowerCase() + "_" + path.replaceAll("[{}/]", "_").replaceAll("_+", "_");
                }
                out.append("• **").append(opId).append("** — `")
                   .append(me.getKey().name()).append(' ').append(path).append("`\n");
                if (op.getSummary() != null && !op.getSummary().isBlank()) {
                    out.append("  ").append(op.getSummary()).append('\n');
                }
                if (op.getParameters() != null && !op.getParameters().isEmpty()) {
                    out.append("  params: ");
                    boolean first = true;
                    for (Parameter p : op.getParameters()) {
                        if (!first) out.append(", ");
                        first = false;
                        out.append(p.getName()).append(" (").append(p.getIn());
                        if (Boolean.TRUE.equals(p.getRequired())) out.append("*");
                        out.append(")");
                    }
                    out.append('\n');
                }
                if (op.getRequestBody() != null) out.append("  body: required\n");
                count++;
            }
        }
        out.append("\n(").append(count).append(" operation(s))");
        return out.toString();
    }

    // ---------- invoke ----------

    @SuppressWarnings("unchecked")
    private String invoke(Map<String, Object> parameters) throws Exception {
        OpenAPI spec = loadSpec(parameters);
        String opId = asString(parameters.get("operation_id"));
        if (opId == null || opId.isBlank()) {
            return "Error: 'operation_id' is required. Call list_operations first to discover IDs.";
        }

        OpResolved found = findOperation(spec, opId);
        if (found == null) return "Error: operationId '" + opId + "' not found in spec.";

        Map<String, Object> pathParams   = toStringMap(parameters.get("path_params"));
        Map<String, Object> queryParams  = toStringMap(parameters.get("query_params"));
        Map<String, Object> headerParams = toStringMap(parameters.get("headers"));
        Object body = parameters.get("body");
        String bearerToken = asString(parameters.get("bearer_token"));

        // Validate required params before burning a network call.
        if (found.operation.getParameters() != null) {
            for (Parameter p : found.operation.getParameters()) {
                if (!Boolean.TRUE.equals(p.getRequired())) continue;
                Map<String, Object> source = switch (p.getIn()) {
                    case "path"   -> pathParams;
                    case "query"  -> queryParams;
                    case "header" -> headerParams;
                    default -> Map.of();
                };
                if (!source.containsKey(p.getName())) {
                    return "Error: missing required " + p.getIn() + " parameter '" + p.getName() + "'.";
                }
            }
        }

        // Build URL
        String base = resolveServer(spec);
        if (base == null) return "Error: spec declares no servers[] — cannot resolve base URL.";
        String filledPath = substitutePathParams(found.path, pathParams);
        if (filledPath == null) return "Error: unsubstituted path parameter in '" + found.path + "'.";
        String url = base + filledPath + buildQueryString(queryParams);

        // Build headers
        HttpHeaders headers = new HttpHeaders();
        headerParams.forEach((k, v) -> headers.set(k, String.valueOf(v)));
        if (bearerToken != null && !bearerToken.isBlank()) headers.setBearerAuth(bearerToken);
        if (body != null && !headers.containsKey(HttpHeaders.CONTENT_TYPE)) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.ALL));

        HttpMethod method = HttpMethod.valueOf(found.method.name());
        HttpEntity<String> entity = body == null
            ? new HttpEntity<>(headers)
            : new HttpEntity<>(body instanceof String s ? s : objectMapper.writeValueAsString(body), headers);

        logger.info("OpenApiToolkit invoke: {} {}  (opId={})", method, url, opId);

        ResponseEntity<String> response;
        try {
            response = restTemplate.exchange(URI.create(url), method, entity, String.class);
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            return "HTTP " + e.getStatusCode().value() + " from " + method + " " + filledPath +
                   "\n\n" + truncate(e.getResponseBodyAsString(), MAX_BODY_PREVIEW);
        }

        StringBuilder out = new StringBuilder();
        out.append("HTTP ").append(response.getStatusCode().value()).append(" — ")
           .append(method).append(' ').append(filledPath).append('\n');
        String respBody = response.getBody();
        if (respBody == null || respBody.isBlank()) {
            out.append("(empty body)");
            return out.toString();
        }
        // Pretty-print if JSON.
        try {
            JsonNode tree = objectMapper.readTree(respBody);
            out.append("\n").append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree));
        } catch (Exception e) {
            out.append("\n").append(truncate(respBody, MAX_BODY_PREVIEW));
        }
        return truncate(out.toString(), getMaxResponseLength());
    }

    // ---------- helpers ----------

    private record OpResolved(String path, PathItem.HttpMethod method, Operation operation) {}

    private OpResolved findOperation(OpenAPI spec, String opId) {
        if (spec.getPaths() == null) return null;
        for (Map.Entry<String, PathItem> pe : spec.getPaths().entrySet()) {
            for (Map.Entry<PathItem.HttpMethod, Operation> me : pe.getValue().readOperationsMap().entrySet()) {
                Operation op = me.getValue();
                String id = op.getOperationId();
                if (opId.equals(id)) return new OpResolved(pe.getKey(), me.getKey(), op);
            }
        }
        return null;
    }

    private OpenAPI loadSpec(Map<String, Object> parameters) {
        String specUrl = asString(parameters.get("spec_url"));
        String inline = asString(parameters.get("spec"));
        if (specUrl == null && inline == null) {
            throw new IllegalArgumentException("provide 'spec_url' or inline 'spec' (YAML/JSON)");
        }
        String cacheKey = specUrl != null ? "url:" + specUrl : "inline:" + Integer.toHexString(inline.hashCode());
        return specCache.computeIfAbsent(cacheKey, k -> parseSpec(specUrl, inline));
    }

    private OpenAPI parseSpec(String specUrl, String inline) {
        SwaggerParseResult result;
        if (specUrl != null && !specUrl.isBlank()) {
            result = new OpenAPIV3Parser().readLocation(specUrl, null, null);
        } else {
            result = new OpenAPIV3Parser().readContents(inline, null, null);
        }
        if (result.getOpenAPI() == null) {
            String msgs = result.getMessages() == null ? "" : String.join("; ", result.getMessages());
            throw new IllegalArgumentException("spec parse failed: " + msgs);
        }
        return result.getOpenAPI();
    }

    private String resolveServer(OpenAPI spec) {
        List<Server> servers = spec.getServers();
        if (servers == null || servers.isEmpty()) return null;
        String url = servers.get(0).getUrl();
        if (url == null) return null;
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    private String substitutePathParams(String template, Map<String, Object> params) {
        String filled = template;
        for (Map.Entry<String, Object> e : params.entrySet()) {
            String placeholder = "{" + e.getKey() + "}";
            if (!filled.contains(placeholder)) continue;
            String encoded = URLEncoder.encode(String.valueOf(e.getValue()), StandardCharsets.UTF_8);
            filled = filled.replace(placeholder, encoded);
        }
        // Any unsubstituted {…} means the caller forgot a required path param.
        if (filled.matches(".*\\{[^/]+\\}.*")) return null;
        return filled;
    }

    private String buildQueryString(Map<String, Object> params) {
        if (params == null || params.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("?");
        boolean first = true;
        for (Map.Entry<String, Object> e : params.entrySet()) {
            if (!first) sb.append('&');
            first = false;
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(String.valueOf(e.getValue()), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toStringMap(Object raw) {
        if (raw == null) return new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
            return out;
        }
        throw new IllegalArgumentException("expected a map but got: " + raw.getClass().getSimpleName());
    }

    private static String asString(Object v) { return v == null ? null : v.toString(); }

    private static String truncate(String s, int n) {
        if (s == null || s.length() <= n) return s == null ? "" : s;
        return s.substring(0, n) + "\n… (truncated)";
    }

    // ---------- metadata ----------

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();

        Map<String, Object> operation = new HashMap<>();
        operation.put("type", "string");
        operation.put("enum", List.of("list_operations", "invoke"));
        operation.put("description", "'list_operations' to discover, 'invoke' to call. Default: 'list_operations'.");
        props.put("operation", operation);

        addStringProp(props, "spec_url", "URL to an OpenAPI 3.x spec (JSON or YAML).");
        addStringProp(props, "spec", "Inline OpenAPI 3.x spec (JSON or YAML). Use instead of spec_url.");
        addStringProp(props, "operation_id", "For 'invoke': operationId from the spec.");

        Map<String, Object> pathP = new HashMap<>();
        pathP.put("type", "object");
        pathP.put("description", "Path parameter map (name → value).");
        props.put("path_params", pathP);

        Map<String, Object> queryP = new HashMap<>();
        queryP.put("type", "object");
        queryP.put("description", "Query parameter map (name → value).");
        props.put("query_params", queryP);

        Map<String, Object> hP = new HashMap<>();
        hP.put("type", "object");
        hP.put("description", "Extra HTTP headers (name → value).");
        props.put("headers", hP);

        Map<String, Object> body = new HashMap<>();
        body.put("description", "Request body: JSON string or an object (auto-serialised to JSON).");
        props.put("body", body);

        addStringProp(props, "bearer_token", "Optional bearer token attached as Authorization header.");

        schema.put("properties", props);
        schema.put("required", new String[]{});
        return schema;
    }

    private static void addStringProp(Map<String, Object> props, String name, String desc) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", "string");
        m.put("description", desc);
        props.put(name, m);
    }

    @Override public boolean isAsync() { return false; }
    @Override public boolean isCacheable() { return false; }
    @Override public String getCategory() { return "integrations"; }
    @Override public List<String> getTags() { return List.of("openapi", "swagger", "api", "universal"); }
    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.DANGEROUS; }

    @Override
    public String getTriggerWhen() {
        return "User references a REST API described by an OpenAPI/Swagger spec, or wants to call a specific " +
               "endpoint that isn't covered by a dedicated tool (Stripe, GitHub, Slack, internal APIs, etc.).";
    }

    @Override
    public String getAvoidWhen() {
        return "A dedicated tool already covers the operation — prefer that for better shape, error messages, " +
               "and rate-limit handling.";
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return Map.of("type", "markdown",
            "description", "For 'list_operations': API title + bulleted operations. For 'invoke': HTTP status + pretty-printed JSON response body.");
    }

    @Override
    public String smokeTest() { return null; /* nothing to probe without a spec */ }

    public record Request(String operation, String spec_url, String spec, String operation_id,
                          Map<String, Object> path_params, Map<String, Object> query_params,
                          Map<String, Object> headers, Object body, String bearer_token) {}
}
