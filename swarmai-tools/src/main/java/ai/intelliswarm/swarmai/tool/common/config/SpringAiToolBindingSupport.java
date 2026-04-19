package ai.intelliswarm.swarmai.tool.common.config;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Helpers shared by the per-category {@code *ToolsConfiguration} classes that register
 * each tool as a Spring AI {@code Function<Request, String>} bean.
 *
 * <p>Keeps the category configs tiny — each one just declares one {@code @Bean} per tool
 * and calls {@link #bind(BaseTool)} with an instance.
 */
public final class SpringAiToolBindingSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SpringAiToolBindingSupport() {}

    /** Convert a typed {@code Request} record into the untyped parameter map the tool's
     *  {@code execute} expects. Drops nulls so tool-level defaults still apply. */
    public static Map<String, Object> toParams(Object request) {
        Map<String, Object> all = MAPPER.convertValue(request, new TypeReference<>() {});
        Map<String, Object> filtered = new HashMap<>(all.size());
        all.forEach((k, v) -> { if (v != null) filtered.put(k, v); });
        return filtered;
    }

    /** Standard binder: {@code Request → Map → tool.execute → String}. */
    public static <R> Function<R, String> bind(BaseTool tool) {
        return request -> {
            Object out = tool.execute(toParams(request));
            return out == null ? "" : out.toString();
        };
    }

    /**
     * Parse a value that may be a Map, a JSON-encoded string, or null into a Map.
     * Used by tools whose {@code Request} record declares a field as {@code String}
     * (to keep the OpenAI function-schema valid — {@code Map<String, ?>} serialises
     * as {@code "type": "object"} with no {@code properties}, which OpenAI rejects).
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseJsonMap(Object raw) {
        if (raw == null) return new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
            return out;
        }
        if (raw instanceof String s) {
            if (s.isBlank()) return new LinkedHashMap<>();
            try {
                return MAPPER.readValue(s, new TypeReference<LinkedHashMap<String, Object>>() {});
            } catch (Exception e) {
                throw new IllegalArgumentException("expected a JSON object string but could not parse: " + e.getMessage());
            }
        }
        throw new IllegalArgumentException("expected a map or JSON object string but got: " + raw.getClass().getSimpleName());
    }

    /** Like {@link #parseJsonMap}, but for list-valued fields (JSON array string or List). */
    @SuppressWarnings("unchecked")
    public static List<Object> parseJsonList(Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof List<?> l) return (List<Object>) l;
        if (raw instanceof String s) {
            if (s.isBlank()) return List.of();
            try {
                return MAPPER.readValue(s, new TypeReference<List<Object>>() {});
            } catch (Exception e) {
                throw new IllegalArgumentException("expected a JSON array string but could not parse: " + e.getMessage());
            }
        }
        throw new IllegalArgumentException("expected a list or JSON array string but got: " + raw.getClass().getSimpleName());
    }
}
