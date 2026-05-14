package ai.intelliswarm.swarmai.tool.finance;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Shared base for finance tools — collects the boilerplate every finance
 * computation does:
 *
 * <ul>
 *   <li>Typed input coercion ({@link #asDouble}, {@link #asInt},
 *       {@link #asList}, {@link #required}) so each tool's {@code execute}
 *       isn't a wall of {@code instanceof} ladders</li>
 *   <li>Standard error envelope ({@link #ok}, {@link #error}) so callers
 *       can parse responses without per-tool special casing</li>
 *   <li>Rounding to 4 decimal places — the convention every finance number
 *       in this codebase already follows</li>
 * </ul>
 *
 * <p>Concrete tools override {@link #getFunctionName()}, {@link #getDescription()},
 * {@link #getParameterSchema()}, and {@link #executeFinance(java.util.Map)}.
 * The base class wraps {@code executeFinance} so a thrown
 * {@link IllegalArgumentException} becomes a structured error response rather
 * than a 500 to the LLM.
 */
@PublicApi(since = "1.0.24")
public abstract class AbstractFinanceTool implements FinanceTool {

    /** Implement the math here. May throw {@link IllegalArgumentException}. */
    protected abstract Object executeFinance(Map<String, Object> parameters);

    @Override
    public final Object execute(Map<String, Object> parameters) {
        try {
            return executeFinance(parameters);
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    @Override
    public boolean isAsync() {
        return false;
    }

    // ---------- input coercion ----------

    protected static double asDouble(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String s) {
            try { return Double.parseDouble(s); }
            catch (NumberFormatException e) {
                throw new IllegalArgumentException("Expected number, got " + s);
            }
        }
        throw new IllegalArgumentException("Expected number, got " + (value == null ? "null" : value.getClass().getSimpleName()));
    }

    protected static double asDoubleOr(Object value, double fallback) {
        if (value == null) return fallback;
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    protected static int asInt(Object value) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try { return Integer.parseInt(s); }
            catch (NumberFormatException e) {
                throw new IllegalArgumentException("Expected integer, got " + s);
            }
        }
        throw new IllegalArgumentException("Expected integer, got " + (value == null ? "null" : value));
    }

    protected static int asIntOr(Object value, int fallback) {
        if (value == null) return fallback;
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    protected static String asStringOr(Object value, String fallback) {
        return value instanceof String s ? s : fallback;
    }

    @SuppressWarnings("unchecked")
    protected static List<Object> asList(Object value) {
        if (value instanceof List<?> l) return (List<Object>) l;
        throw new IllegalArgumentException("Expected list, got " + (value == null ? "null" : value.getClass().getSimpleName()));
    }

    @SuppressWarnings("unchecked")
    protected static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> m) return (Map<String, Object>) m;
        throw new IllegalArgumentException("Expected map, got " + (value == null ? "null" : value.getClass().getSimpleName()));
    }

    protected static Object required(Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (v == null) {
            throw new IllegalArgumentException("Required parameter missing: " + key);
        }
        return v;
    }

    protected static Optional<Object> optional(Map<String, Object> params, String key) {
        return Optional.ofNullable(params.get(key));
    }

    // ---------- output helpers ----------

    /** Round to 4 decimal places — convention for finance outputs in this codebase. */
    protected static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    protected static Map<String, Object> ok(Map<String, Object> body) {
        Objects.requireNonNull(body, "body must not be null");
        java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>(body.size() + 1);
        out.put("ok", true);
        out.putAll(body);
        return out;
    }

    protected static Map<String, Object> error(String message) {
        return Map.of("ok", false, "error", message);
    }

    protected static Map<String, Object> error(String message, Map<String, Object> details) {
        java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", message);
        out.putAll(details);
        return out;
    }
}
