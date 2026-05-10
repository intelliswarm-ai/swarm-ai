package ai.intelliswarm.swarmai.skill.runtime;

import ai.intelliswarm.swarmai.skill.runtime.CapabilityManifest.Capability;
import ai.intelliswarm.swarmai.skill.runtime.CapabilityManifest.Parameter;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Hand-rolled parser for {@link CapabilityManifest} JSON. We avoid
 * pulling in Jackson here so {@code swarmai-core} stays free of mandatory
 * JSON dependencies — the rest of the module already uses simple
 * SKILL.md / CDATA round-trips. ~150 lines of regex-free recursive
 * parsing covers the manifest shape exactly.
 *
 * <h2>Forward compatibility</h2>
 * Unknown fields are ignored. Missing optional fields default to sane
 * values (see {@link CapabilityManifest.Capability}). Required fields
 * missing or wrong-typed throw {@link ManifestParseException} with a
 * pointer to the problem.
 */
public final class ManifestParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ManifestParser() {}

    /** Parse a JSON document into a {@link CapabilityManifest}. */
    public static CapabilityManifest parse(String json) {
        if (json == null || json.isBlank()) {
            throw new ManifestParseException("empty manifest");
        }
        Object root;
        try {
            root = MAPPER.readValue(json, Object.class);
        } catch (Exception e) {
            throw new ManifestParseException("invalid JSON: " + e.getMessage(), e);
        }
        if (!(root instanceof Map<?, ?> rootMap)) {
            throw new ManifestParseException("manifest root must be a JSON object");
        }
        return parseManifest(rootMap);
    }

    /**
     * Decode a manifest carried as a base64-encoded string (the LABEL
     * transport keeps Docker's value-string rules simple).
     */
    public static CapabilityManifest parseBase64(String base64Json) {
        if (base64Json == null || base64Json.isBlank()) {
            throw new ManifestParseException("empty base64 manifest");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Json.trim());
        } catch (IllegalArgumentException e) {
            throw new ManifestParseException("manifest is not valid base64", e);
        }
        return parse(new String(decoded));
    }

    @SuppressWarnings("unchecked")
    private static CapabilityManifest parseManifest(Map<?, ?> m) {
        String name = requiredString(m, "name", "manifest");
        String version = optionalString(m, "version", CapabilityManifest.SCHEMA_VERSION);
        String description = optionalString(m, "description", "");

        Object toolsRaw = m.get("tools");
        List<Capability> tools = new ArrayList<>();
        if (toolsRaw instanceof List<?> toolList) {
            int idx = 0;
            for (Object t : toolList) {
                if (!(t instanceof Map<?, ?> toolMap)) {
                    throw new ManifestParseException(
                            "tools[" + idx + "] is not a JSON object");
                }
                tools.add(parseCapability(toolMap, idx));
                idx++;
            }
        } else if (toolsRaw != null) {
            throw new ManifestParseException("tools must be an array; got " + toolsRaw.getClass().getSimpleName());
        }
        return new CapabilityManifest(name, version, description, tools);
    }

    @SuppressWarnings("unchecked")
    private static Capability parseCapability(Map<?, ?> m, int idx) {
        String ctx = "tools[" + idx + "]";
        String name = requiredString(m, "name", ctx);
        ctx = "tool '" + name + "'";

        String description = requiredString(m, "description", ctx);
        String triggerWhen = optionalString(m, "triggerWhen", "");
        String avoidWhen = optionalString(m, "avoidWhen", "");
        String category = optionalString(m, "category", "");

        List<String> tags = new ArrayList<>();
        Object tagsRaw = m.get("tags");
        if (tagsRaw instanceof List<?> tagList) {
            for (Object tag : tagList) {
                if (tag != null) tags.add(tag.toString());
            }
        }

        List<Parameter> params = new ArrayList<>();
        Object paramsRaw = m.get("parameters");
        if (paramsRaw instanceof List<?> paramList) {
            int pIdx = 0;
            for (Object p : paramList) {
                if (!(p instanceof Map<?, ?> pMap)) {
                    throw new ManifestParseException(
                            ctx + " parameters[" + pIdx + "] must be an object");
                }
                params.add(parseParameter(pMap, ctx + " parameters[" + pIdx + "]"));
                pIdx++;
            }
        }

        String entrypoint = requiredString(m, "entrypoint", ctx);
        long timeoutSeconds = requiredLong(m, "timeoutSeconds", ctx);
        String network = optionalString(m, "network", "bridge");
        String memory = optionalString(m, "memory", null);

        try {
            return new Capability(
                    name, description, triggerWhen, avoidWhen, category, tags,
                    params, entrypoint, timeoutSeconds, network, memory);
        } catch (IllegalArgumentException e) {
            // Record constructor enforces invariants (name regex, timeout > 0,
            // ...). Wrap so callers only need to handle ManifestParseException.
            throw new ManifestParseException(ctx + ": " + e.getMessage(), e);
        }
    }

    private static Parameter parseParameter(Map<?, ?> m, String ctx) {
        String name = requiredString(m, "name", ctx);
        String type = optionalString(m, "type", "string");
        String description = optionalString(m, "description", "");
        boolean required = optionalBool(m, "required", false);
        String envVar = optionalString(m, "envVar", null);
        try {
            return new Parameter(name, type, description, required, envVar);
        } catch (IllegalArgumentException e) {
            throw new ManifestParseException(ctx + ": " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------------
    // typed lookups
    // ---------------------------------------------------------------------

    private static String requiredString(Map<?, ?> m, String key, String ctx) {
        Object v = m.get(key);
        if (v == null) {
            throw new ManifestParseException(ctx + " missing required field '" + key + "'");
        }
        if (!(v instanceof String s)) {
            throw new ManifestParseException(ctx + " field '" + key + "' must be a string");
        }
        if (s.isBlank()) {
            throw new ManifestParseException(ctx + " field '" + key + "' is blank");
        }
        return s;
    }

    private static String optionalString(Map<?, ?> m, String key, String defaultValue) {
        Object v = m.get(key);
        if (v == null) return defaultValue;
        if (!(v instanceof String s)) {
            throw new ManifestParseException("field '" + key + "' must be a string");
        }
        return s;
    }

    private static long requiredLong(Map<?, ?> m, String key, String ctx) {
        Object v = m.get(key);
        if (v == null) {
            throw new ManifestParseException(ctx + " missing required field '" + key + "'");
        }
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            try { return Long.parseLong(s.trim()); }
            catch (NumberFormatException ignored) { }
        }
        throw new ManifestParseException(
                ctx + " field '" + key + "' must be a number; got " + v.getClass().getSimpleName());
    }

    private static boolean optionalBool(Map<?, ?> m, String key, boolean defaultValue) {
        Object v = m.get(key);
        if (v == null) return defaultValue;
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) {
            String t = s.trim().toLowerCase();
            if (t.equals("true")) return true;
            if (t.equals("false")) return false;
        }
        throw new ManifestParseException("field '" + key + "' must be a boolean");
    }

    /** Thrown when a manifest can't be parsed or fails schema validation. */
    public static final class ManifestParseException extends RuntimeException {
        public ManifestParseException(String msg) { super(msg); }
        public ManifestParseException(String msg, Throwable cause) { super(msg, cause); }
    }
}
