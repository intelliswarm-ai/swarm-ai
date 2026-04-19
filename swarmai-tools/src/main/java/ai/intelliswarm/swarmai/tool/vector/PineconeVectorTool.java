package ai.intelliswarm.swarmai.tool.vector;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import ai.intelliswarm.swarmai.tool.base.ToolRequirements;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pinecone Vector Tool — query / upsert / delete / stats against a Pinecone index.
 *
 * <p>This tool deliberately does <em>not</em> handle embedding. Agents pass raw
 * vectors (pre-embedded with their preferred model) or IDs. That keeps the
 * embedding-model choice orthogonal to the vector-store choice.
 *
 * <p>Required config:
 * <ul>
 *   <li>{@code PINECONE_API_KEY}     — your Pinecone API key</li>
 *   <li>{@code PINECONE_INDEX_HOST}  — full index host URL,
 *       e.g. {@code https://my-index-abcdef.svc.us-east1-gcp.pinecone.io}.
 *       Get it from the Pinecone console → index → "CONNECT".</li>
 * </ul>
 *
 * <p>The index host can be passed per call via {@code index_host}. Permission
 * level is {@code WORKSPACE_WRITE} because upsert/delete mutate the index.
 */
@Component
public class PineconeVectorTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(PineconeVectorTool.class);
    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 100;
    private static final int MAX_UPSERT_BATCH = 100; // Pinecone hard limit per request
    private static final int MAX_DELETE_BATCH = 1000;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${swarmai.tools.pinecone.api-key:}")
    private String apiKey = "";

    @Value("${swarmai.tools.pinecone.index-host:}")
    private String indexHost = "";

    public PineconeVectorTool() {
        this(new RestTemplate(), new ObjectMapper());
    }

    PineconeVectorTool(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Override public String getFunctionName() { return "pinecone"; }

    @Override
    public String getDescription() {
        return "Interact with a Pinecone vector index: 'query' for nearest-neighbor search, 'upsert' " +
               "to write vectors, 'delete' to remove by IDs, or 'stats' to inspect the index. " +
               "Requires PINECONE_API_KEY and PINECONE_INDEX_HOST. Vectors must be pre-embedded " +
               "— this tool does not compute embeddings.";
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String key = pickKey(parameters);
        String host = pickHost(parameters);
        if (key == null) {
            return "Error: PINECONE_API_KEY not configured. Set the env var or " +
                   "swarmai.tools.pinecone.api-key property.";
        }
        if (host == null) {
            return "Error: PINECONE_INDEX_HOST not configured. Paste the full index host URL from " +
                   "the Pinecone console (e.g. 'https://myindex-abc123.svc.us-east1-gcp.pinecone.io').";
        }
        String operation = asString(parameters.getOrDefault("operation", "query")).toLowerCase();
        logger.info("PineconeVectorTool: op={} host={}", operation, host);

        try {
            return switch (operation) {
                case "query"   -> query(key, host, parameters);
                case "upsert"  -> upsert(key, host, parameters);
                case "delete"  -> delete(key, host, parameters);
                case "stats"   -> stats(key, host);
                default -> "Error: unknown operation '" + operation +
                           "'. Use 'query', 'upsert', 'delete', or 'stats'.";
            };
        } catch (HttpClientErrorException.Unauthorized e) {
            return "Error: Pinecone rejected the API key (401). Verify PINECONE_API_KEY.";
        } catch (HttpClientErrorException.NotFound e) {
            return "Error: Pinecone returned 404 — the index host may be wrong or the namespace doesn't exist.";
        } catch (HttpClientErrorException.BadRequest e) {
            return "Error: Pinecone 400 — " + truncate(e.getResponseBodyAsString(), 500);
        } catch (RestClientException e) {
            logger.warn("PineconeVectorTool network error: {}", e.getMessage());
            return "Error: Pinecone request failed — " + e.getMessage();
        } catch (Exception e) {
            logger.error("PineconeVectorTool unexpected error", e);
            return "Error: " + e.getMessage();
        }
    }

    // ---------- operations ----------

    private String query(String key, String host, Map<String, Object> parameters) throws Exception {
        List<Double> vector = extractVector(parameters.get("vector"));
        String id = asString(parameters.get("id"));
        if (vector.isEmpty() && (id == null || id.isBlank())) {
            return "Error: provide either 'vector' (list of numbers) or 'id' (existing vector id).";
        }
        int topK = parseInt(parameters, "top_k", DEFAULT_TOP_K, 1, MAX_TOP_K);
        String namespace = asString(parameters.getOrDefault("namespace", ""));
        boolean includeMetadata = parseBool(parameters, "include_metadata", true);
        boolean includeValues   = parseBool(parameters, "include_values", false);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("topK", topK);
        body.put("includeMetadata", includeMetadata);
        body.put("includeValues", includeValues);
        if (!namespace.isBlank()) body.put("namespace", namespace);
        if (!vector.isEmpty()) {
            ArrayNode v = body.putArray("vector");
            for (Double d : vector) v.add(d);
        } else {
            body.put("id", id);
        }
        Object filter = parameters.get("filter");
        if (filter instanceof String fs && !fs.isBlank()) {
            body.set("filter", objectMapper.readTree(fs));
        } else if (filter instanceof Map) {
            body.set("filter", objectMapper.valueToTree(filter));
        }

        ResponseEntity<String> response = post(key, host + "/query", body.toString());
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            return "Error: Pinecone query returned HTTP " + response.getStatusCode().value();
        }
        return formatQuery(response.getBody(), topK);
    }

    private String upsert(String key, String host, Map<String, Object> parameters) throws Exception {
        Object raw = parameters.get("vectors");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return "Error: 'vectors' must be a non-empty list of {id, values, metadata?} objects.";
        }
        if (list.size() > MAX_UPSERT_BATCH) {
            return "Error: upsert batch too large (" + list.size() + "). Max " + MAX_UPSERT_BATCH + " per call.";
        }
        String namespace = asString(parameters.getOrDefault("namespace", ""));

        ObjectNode body = objectMapper.createObjectNode();
        if (!namespace.isBlank()) body.put("namespace", namespace);
        ArrayNode vecsOut = body.putArray("vectors");

        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                return "Error: each vector entry must be a map with 'id' and 'values'.";
            }
            String id = asString(m.get("id"));
            List<Double> values = extractVector(m.get("values"));
            Object metadata = m.get("metadata");
            if (id == null || id.isBlank() || values.isEmpty()) {
                return "Error: every vector needs an 'id' (string) and 'values' (list of numbers).";
            }
            ObjectNode node = vecsOut.addObject();
            node.put("id", id);
            ArrayNode vs = node.putArray("values");
            for (Double d : values) vs.add(d);
            if (metadata instanceof Map) {
                node.set("metadata", objectMapper.valueToTree(metadata));
            }
        }

        ResponseEntity<String> response = post(key, host + "/vectors/upsert", body.toString());
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            return "Error: Pinecone upsert returned HTTP " + response.getStatusCode().value();
        }
        JsonNode n = objectMapper.readTree(response.getBody());
        int upsertedCount = n.path("upsertedCount").asInt(list.size());
        return "Upserted " + upsertedCount + " vector(s)" +
               (namespace.isBlank() ? "" : " into namespace '" + namespace + "'") + ".";
    }

    private String delete(String key, String host, Map<String, Object> parameters) throws Exception {
        Object idsRaw = parameters.get("ids");
        boolean deleteAll = parseBool(parameters, "delete_all", false);
        String namespace = asString(parameters.getOrDefault("namespace", ""));

        ObjectNode body = objectMapper.createObjectNode();
        if (deleteAll) {
            body.put("deleteAll", true);
        } else if (idsRaw instanceof List<?> ids && !ids.isEmpty()) {
            if (ids.size() > MAX_DELETE_BATCH) {
                return "Error: delete batch too large (" + ids.size() + "). Max " + MAX_DELETE_BATCH + ".";
            }
            ArrayNode arr = body.putArray("ids");
            for (Object id : ids) arr.add(String.valueOf(id));
        } else {
            return "Error: provide either 'ids' (list of strings) or 'delete_all=true'.";
        }
        if (!namespace.isBlank()) body.put("namespace", namespace);

        ResponseEntity<String> response = post(key, host + "/vectors/delete", body.toString());
        if (!response.getStatusCode().is2xxSuccessful()) {
            return "Error: Pinecone delete returned HTTP " + response.getStatusCode().value();
        }
        if (deleteAll) return "Deleted all vectors" + (namespace.isBlank() ? "" : " in namespace '" + namespace + "'") + ".";
        int n = ((List<?>) idsRaw).size();
        return "Requested deletion of " + n + " vector(s)" +
               (namespace.isBlank() ? "" : " in namespace '" + namespace + "'") + ".";
    }

    private String stats(String key, String host) throws Exception {
        // stats is POST with empty body on v1.
        ResponseEntity<String> response = post(key, host + "/describe_index_stats", "{}");
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            return "Error: Pinecone stats returned HTTP " + response.getStatusCode().value();
        }
        JsonNode n = objectMapper.readTree(response.getBody());
        int dim = n.path("dimension").asInt();
        long total = n.path("totalVectorCount").asLong();
        double fullness = n.path("indexFullness").asDouble(0.0);

        StringBuilder out = new StringBuilder();
        out.append("**Pinecone index stats**\n");
        out.append("Dimension:       ").append(dim).append('\n');
        out.append("Total vectors:   ").append(total).append('\n');
        out.append(String.format("Index fullness:  %.1f%%%n", fullness * 100));

        JsonNode namespaces = n.path("namespaces");
        if (namespaces.isObject()) {
            out.append("\nNamespaces:\n");
            namespaces.fieldNames().forEachRemaining(name ->
                out.append("  • ").append(name.isEmpty() ? "(default)" : name)
                   .append(": ").append(namespaces.path(name).path("vectorCount").asLong()).append(" vectors\n"));
        }
        return out.toString().trim();
    }

    // ---------- formatting ----------

    private String formatQuery(String json, int topK) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        JsonNode matches = root.path("matches");
        if (!matches.isArray() || matches.isEmpty()) {
            return "Pinecone query returned no matches.";
        }
        StringBuilder out = new StringBuilder();
        out.append("Pinecone returned ").append(matches.size()).append(" match(es) (top_k=")
           .append(topK).append("):\n\n");
        int i = 1;
        for (JsonNode m : matches) {
            out.append(i++).append(". id=`").append(m.path("id").asText()).append("`  score=")
               .append(String.format("%.4f", m.path("score").asDouble())).append('\n');
            JsonNode meta = m.path("metadata");
            if (meta.isObject() && !meta.isEmpty()) {
                meta.fieldNames().forEachRemaining(name ->
                    out.append("   ").append(name).append(": ")
                       .append(truncate(meta.path(name).asText(), 200)).append('\n'));
            }
        }
        return out.toString().trim();
    }

    // ---------- HTTP helpers ----------

    private ResponseEntity<String> post(String key, String url, String body) {
        return restTemplate.exchange(URI.create(url), HttpMethod.POST,
            new HttpEntity<>(body, headers(key)), String.class);
    }

    private HttpHeaders headers(String key) {
        HttpHeaders h = new HttpHeaders();
        h.set("Api-Key", key);
        h.set("X-Pinecone-API-Version", "2024-07");
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setAccept(List.of(MediaType.APPLICATION_JSON));
        return h;
    }

    private String pickKey(Map<String, Object> parameters) {
        String p = asString(parameters.get("api_key"));
        if (p != null && !p.isBlank()) return p;
        if (apiKey != null && !apiKey.isBlank()) return apiKey;
        String env = System.getenv("PINECONE_API_KEY");
        return env != null && !env.isBlank() ? env : null;
    }

    private String pickHost(Map<String, Object> parameters) {
        String p = asString(parameters.get("index_host"));
        String chosen = firstNonBlank(p, indexHost, System.getenv("PINECONE_INDEX_HOST"));
        if (chosen == null) return null;
        if (!chosen.startsWith("http")) chosen = "https://" + chosen;
        if (chosen.endsWith("/")) chosen = chosen.substring(0, chosen.length() - 1);
        return chosen;
    }

    // ---------- misc helpers ----------

    @SuppressWarnings("unchecked")
    private static List<Double> extractVector(Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof List<?> list) {
            List<Double> out = new ArrayList<>(list.size());
            for (Object v : list) {
                if (v instanceof Number n) out.add(n.doubleValue());
                else try { out.add(Double.parseDouble(String.valueOf(v))); } catch (NumberFormatException e) { return List.of(); }
            }
            return out;
        }
        if (raw instanceof double[] arr) {
            List<Double> out = new ArrayList<>(arr.length);
            for (double d : arr) out.add(d);
            return out;
        }
        return List.of();
    }

    private static int parseInt(Map<String, Object> parameters, String key, int def, int min, int max) {
        Object raw = parameters.get(key);
        if (raw == null) return def;
        try {
            int n = raw instanceof Number ? ((Number) raw).intValue() : Integer.parseInt(raw.toString().trim());
            return Math.max(min, Math.min(max, n));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean parseBool(Map<String, Object> parameters, String key, boolean def) {
        Object raw = parameters.get(key);
        if (raw instanceof Boolean b) return b;
        if (raw == null) return def;
        return Boolean.parseBoolean(raw.toString());
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private static String asString(Object v) { return v == null ? null : v.toString(); }
    private static String truncate(String s, int n) { return s == null || s.length() <= n ? s : s.substring(0, n) + "…"; }

    // ---------- metadata ----------

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();

        Map<String, Object> operation = new HashMap<>();
        operation.put("type", "string");
        operation.put("enum", List.of("query", "upsert", "delete", "stats"));
        operation.put("description", "Which Pinecone operation to perform. Default: 'query'.");
        props.put("operation", operation);

        Map<String, Object> vec = new HashMap<>();
        vec.put("type", "array");
        vec.put("items", Map.of("type", "number"));
        vec.put("description", "Query vector (numeric list). Mutually exclusive with 'id'.");
        props.put("vector", vec);

        addStringProp(props, "id", "Vector id to query by (alternative to 'vector').");
        addStringProp(props, "namespace", "Pinecone namespace. Empty = default namespace.");

        Map<String, Object> topK = new HashMap<>();
        topK.put("type", "integer");
        topK.put("description", "Number of neighbors for 'query' (1–" + MAX_TOP_K + "). Default " + DEFAULT_TOP_K + ".");
        props.put("top_k", topK);

        Map<String, Object> filter = new HashMap<>();
        filter.put("type", "string");
        filter.put("description", "Raw Pinecone metadata filter JSON (for 'query').");
        props.put("filter", filter);

        Map<String, Object> vecs = new HashMap<>();
        vecs.put("type", "array");
        vecs.put("items", Map.of("type", "object"));
        vecs.put("description", "For 'upsert': list of {id, values, metadata?}. Max " + MAX_UPSERT_BATCH + ".");
        props.put("vectors", vecs);

        Map<String, Object> ids = new HashMap<>();
        ids.put("type", "array");
        ids.put("items", Map.of("type", "string"));
        ids.put("description", "For 'delete': list of vector ids to remove. Max " + MAX_DELETE_BATCH + ".");
        props.put("ids", ids);

        Map<String, Object> deleteAll = new HashMap<>();
        deleteAll.put("type", "boolean");
        deleteAll.put("description", "For 'delete': remove every vector (scope is 'namespace' if set).");
        props.put("delete_all", deleteAll);

        Map<String, Object> includeMeta = new HashMap<>();
        includeMeta.put("type", "boolean");
        includeMeta.put("description", "'query': return metadata in matches. Default true.");
        props.put("include_metadata", includeMeta);

        Map<String, Object> includeValues = new HashMap<>();
        includeValues.put("type", "boolean");
        includeValues.put("description", "'query': return the raw vector values in matches. Default false.");
        props.put("include_values", includeValues);

        addStringProp(props, "index_host", "Optional per-call override of PINECONE_INDEX_HOST.");

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
    @Override public String getCategory() { return "vector"; }
    @Override public List<String> getTags() { return List.of("pinecone", "vector", "rag", "embeddings"); }
    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.WORKSPACE_WRITE; }

    @Override
    public String getTriggerWhen() {
        return "User wants to perform semantic / vector search over a Pinecone index, write new " +
               "embeddings into it, delete stale entries, or inspect index stats (RAG pipelines).";
    }

    @Override
    public String getAvoidWhen() {
        return "User's vectors live in a different store (Qdrant, Weaviate, pgvector) — use the store-specific tool.";
    }

    @Override
    public ToolRequirements getRequirements() {
        return ToolRequirements.builder().env("PINECONE_API_KEY", "PINECONE_INDEX_HOST").build();
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return Map.of("type", "markdown",
            "description", "Ranked match list (id, score, metadata), upsert/delete confirmation, or stats summary.");
    }

    @Override
    public String smokeTest() {
        String k = pickKey(Map.of());
        String h = pickHost(Map.of());
        if (k == null) return "PINECONE_API_KEY not configured";
        if (h == null) return "PINECONE_INDEX_HOST not configured";
        try {
            ResponseEntity<String> r = post(k, h + "/describe_index_stats", "{}");
            return r.getStatusCode().is2xxSuccessful() ? null
                : "Pinecone unreachable: HTTP " + r.getStatusCode().value();
        } catch (Exception e) {
            return "Pinecone unreachable: " + e.getMessage();
        }
    }

    public record Request(String operation, List<Double> vector, String id, String namespace,
                          Integer top_k, String filter, List<Map<String, Object>> vectors,
                          List<String> ids, Boolean delete_all, Boolean include_metadata,
                          Boolean include_values, String index_host) {}
}
