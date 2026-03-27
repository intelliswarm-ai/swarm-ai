package ai.intelliswarm.swarmai.tool.common;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * JSON Transform Tool — parse, query, extract, and transform JSON data.
 *
 * Operations:
 * - parse: Pretty-print and validate JSON
 * - extract: Extract fields by dot-notation path (e.g., "data.users[0].name")
 * - keys: List top-level or nested keys
 * - flatten: Flatten nested JSON to dot-notation key-value pairs
 * - to_csv: Convert JSON array to CSV format
 * - count: Count array elements or object keys
 */
@Component
public class JSONTransformTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(JSONTransformTool.class);

    private static final Set<String> VALID_OPERATIONS = Set.of(
        "parse", "extract", "keys", "flatten", "to_csv", "count"
    );

    private final ObjectMapper objectMapper;

    public JSONTransformTool() {
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public String getFunctionName() {
        return "json_transform";
    }

    @Override
    public String getDescription() {
        return "Parse, query, and transform JSON data. Operations: " +
               "'parse' (validate & pretty-print), " +
               "'extract' (get value by path like 'data.users[0].name'), " +
               "'keys' (list keys), " +
               "'flatten' (nested to dot-notation), " +
               "'to_csv' (array of objects to CSV), " +
               "'count' (count elements).";
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String json = (String) parameters.get("json");
        String operation = ((String) parameters.getOrDefault("operation", "parse")).toLowerCase();
        String path = (String) parameters.getOrDefault("path", null);

        logger.info("JSONTransformTool: operation={}, path={}, json={} chars",
            operation, path, json != null ? json.length() : 0);

        try {
            // 1. Validate inputs
            String inputError = validateInputs(json, operation);
            if (inputError != null) {
                return "Error: " + inputError;
            }

            // 2. Parse JSON
            JsonNode root = objectMapper.readTree(json);

            // 3. Execute operation
            return switch (operation) {
                case "parse" -> executeParse(root);
                case "extract" -> executeExtract(root, path);
                case "keys" -> executeKeys(root, path);
                case "flatten" -> executeFlatten(root);
                case "to_csv" -> executeToCsv(root, path);
                case "count" -> executeCount(root, path);
                default -> "Error: Unknown operation: " + operation;
            };

        } catch (JsonProcessingException e) {
            return "Error: Invalid JSON: " + e.getOriginalMessage();
        } catch (Exception e) {
            logger.error("Error in JSON transform: {}", e.getMessage(), e);
            return "Error: " + e.getMessage();
        }
    }

    private String validateInputs(String json, String operation) {
        if (json == null || json.trim().isEmpty()) {
            return "JSON input is required";
        }
        if (!VALID_OPERATIONS.contains(operation)) {
            return "Invalid operation: '" + operation + "'. Valid: " + VALID_OPERATIONS;
        }
        return null;
    }

    // ==================== Operations ====================

    private String executeParse(JsonNode root) throws JsonProcessingException {
        StringBuilder response = new StringBuilder();
        response.append("**JSON Structure:**\n");
        response.append("- Type: ").append(root.isArray() ? "Array" : "Object").append("\n");

        if (root.isArray()) {
            response.append("- Elements: ").append(root.size()).append("\n");
        } else if (root.isObject()) {
            response.append("- Keys: ").append(root.size()).append("\n");
        }

        response.append("\n**Formatted JSON:**\n```json\n");
        response.append(objectMapper.writeValueAsString(root));
        response.append("\n```\n");

        return response.toString();
    }

    private String executeExtract(JsonNode root, String path) throws JsonProcessingException {
        if (path == null || path.trim().isEmpty()) {
            return "Error: 'path' parameter is required for extract operation (e.g., 'data.users[0].name')";
        }

        JsonNode result = navigatePath(root, path);
        if (result == null || result.isMissingNode()) {
            return "Error: Path not found: " + path;
        }

        StringBuilder response = new StringBuilder();
        response.append("**Path:** ").append(path).append("\n");
        response.append("**Type:** ").append(getNodeType(result)).append("\n\n");

        if (result.isValueNode()) {
            response.append("**Value:** ").append(result.asText()).append("\n");
        } else {
            response.append("**Value:**\n```json\n");
            response.append(objectMapper.writeValueAsString(result));
            response.append("\n```\n");
        }

        return response.toString();
    }

    private String executeKeys(JsonNode root, String path) {
        JsonNode target = root;
        if (path != null && !path.trim().isEmpty()) {
            target = navigatePath(root, path);
            if (target == null || target.isMissingNode()) {
                return "Error: Path not found: " + path;
            }
        }

        if (!target.isObject()) {
            return "Error: Keys operation requires a JSON object, got " + getNodeType(target);
        }

        StringBuilder response = new StringBuilder();
        if (path != null && !path.isEmpty()) {
            response.append("**Keys at path '").append(path).append("':**\n\n");
        } else {
            response.append("**Top-level keys:**\n\n");
        }

        Iterator<String> fieldNames = target.fieldNames();
        int count = 0;
        while (fieldNames.hasNext()) {
            String field = fieldNames.next();
            JsonNode value = target.get(field);
            response.append("- `").append(field).append("` (").append(getNodeType(value)).append(")\n");
            count++;
        }

        response.append("\n**Total:** ").append(count).append(" keys\n");
        return response.toString();
    }

    private String executeFlatten(JsonNode root) {
        Map<String, String> flattened = new LinkedHashMap<>();
        flattenNode("", root, flattened);

        StringBuilder response = new StringBuilder();
        response.append("**Flattened JSON** (").append(flattened.size()).append(" entries):\n\n");
        response.append("| Path | Value |\n");
        response.append("|------|-------|\n");

        for (Map.Entry<String, String> entry : flattened.entrySet()) {
            String value = entry.getValue();
            if (value.length() > 80) {
                value = value.substring(0, 77) + "...";
            }
            response.append("| `").append(entry.getKey()).append("` | ").append(value).append(" |\n");
        }

        return response.toString();
    }

    private String executeToCsv(JsonNode root, String path) {
        JsonNode target = root;
        if (path != null && !path.trim().isEmpty()) {
            target = navigatePath(root, path);
            if (target == null || target.isMissingNode()) {
                return "Error: Path not found: " + path;
            }
        }

        if (!target.isArray() || target.isEmpty()) {
            return "Error: to_csv requires a non-empty JSON array of objects";
        }

        // Collect all unique keys from all objects
        LinkedHashSet<String> allKeys = new LinkedHashSet<>();
        for (JsonNode element : target) {
            if (element.isObject()) {
                element.fieldNames().forEachRemaining(allKeys::add);
            }
        }

        if (allKeys.isEmpty()) {
            return "Error: Array elements must be objects with keys for CSV conversion";
        }

        StringBuilder csv = new StringBuilder();

        // Header
        csv.append(String.join(",", allKeys)).append("\n");

        // Rows
        for (JsonNode element : target) {
            List<String> values = new ArrayList<>();
            for (String key : allKeys) {
                JsonNode val = element.get(key);
                if (val == null || val.isNull()) {
                    values.add("");
                } else {
                    String text = val.asText();
                    // Quote values that contain commas or quotes
                    if (text.contains(",") || text.contains("\"") || text.contains("\n")) {
                        text = "\"" + text.replace("\"", "\"\"") + "\"";
                    }
                    values.add(text);
                }
            }
            csv.append(String.join(",", values)).append("\n");
        }

        StringBuilder response = new StringBuilder();
        response.append("**CSV Output** (").append(target.size()).append(" rows, ")
                .append(allKeys.size()).append(" columns):\n\n```csv\n");
        response.append(csv);
        response.append("```\n");

        return response.toString();
    }

    private String executeCount(JsonNode root, String path) {
        JsonNode target = root;
        if (path != null && !path.trim().isEmpty()) {
            target = navigatePath(root, path);
            if (target == null || target.isMissingNode()) {
                return "Error: Path not found: " + path;
            }
        }

        StringBuilder response = new StringBuilder();
        if (path != null && !path.isEmpty()) {
            response.append("**Count at path '").append(path).append("':** ");
        } else {
            response.append("**Count:** ");
        }

        if (target.isArray()) {
            response.append(target.size()).append(" elements (array)\n");
        } else if (target.isObject()) {
            response.append(target.size()).append(" keys (object)\n");
        } else {
            response.append("1 (scalar value: ").append(getNodeType(target)).append(")\n");
        }

        return response.toString();
    }

    // ==================== Helper Methods ====================

    /**
     * Navigate a dot-notation path with array index support.
     * Examples: "data.users", "data.users[0]", "data.users[0].name", "items[2].tags[0]"
     */
    private JsonNode navigatePath(JsonNode root, String path) {
        JsonNode current = root;
        String[] segments = path.split("\\.");

        for (String segment : segments) {
            if (current == null || current.isMissingNode()) return null;

            // Check for array index: "field[0]"
            int bracketStart = segment.indexOf('[');
            if (bracketStart != -1) {
                String fieldName = segment.substring(0, bracketStart);
                String indexStr = segment.substring(bracketStart + 1, segment.indexOf(']'));

                if (!fieldName.isEmpty()) {
                    current = current.get(fieldName);
                    if (current == null) return null;
                }

                try {
                    int index = Integer.parseInt(indexStr);
                    if (!current.isArray() || index >= current.size()) return null;
                    current = current.get(index);
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                current = current.get(segment);
            }
        }

        return current;
    }

    private void flattenNode(String prefix, JsonNode node, Map<String, String> result) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = prefix.isEmpty() ? field.getKey() : prefix + "." + field.getKey();
                flattenNode(key, field.getValue(), result);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                flattenNode(prefix + "[" + i + "]", node.get(i), result);
            }
        } else {
            result.put(prefix, node.asText());
        }
    }

    private String getNodeType(JsonNode node) {
        if (node.isObject()) return "object";
        if (node.isArray()) return "array";
        if (node.isTextual()) return "string";
        if (node.isNumber()) return "number";
        if (node.isBoolean()) return "boolean";
        if (node.isNull()) return "null";
        return "unknown";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> json = new HashMap<>();
        json.put("type", "string");
        json.put("description", "The JSON string to parse and transform");
        properties.put("json", json);

        Map<String, Object> operation = new HashMap<>();
        operation.put("type", "string");
        operation.put("description", "Operation: parse, extract, keys, flatten, to_csv, count (default: parse)");
        operation.put("default", "parse");
        operation.put("enum", List.of("parse", "extract", "keys", "flatten", "to_csv", "count"));
        properties.put("operation", operation);

        Map<String, Object> path = new HashMap<>();
        path.put("type", "string");
        path.put("description", "Dot-notation path for extract/keys/count (e.g., 'data.users[0].name')");
        properties.put("path", path);

        schema.put("properties", properties);
        schema.put("required", new String[]{"json"});

        return schema;
    }

    @Override
    public boolean isAsync() {
        return false;
    }

    // ==================== OpenClaw Metadata ====================

    @Override
    public String getTriggerWhen() {
        return "User needs to parse, query, flatten, or transform JSON data.";
    }

    @Override
    public String getAvoidWhen() {
        return "Data is CSV, XML, or plain text.";
    }

    @Override
    public String getCategory() {
        return "data-io";
    }

    @Override
    public List<String> getTags() {
        return List.of("json", "transform", "query", "flatten");
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return Map.of(
            "type", "markdown",
            "description", "Formatted JSON output, extracted values, flattened key-value tables, or CSV conversion depending on operation"
        );
    }

    @Override
    public int getMaxResponseLength() {
        return 12000;
    }

    // Request record for Spring AI function binding
    public record Request(String json, String operation, String path) {}
}
