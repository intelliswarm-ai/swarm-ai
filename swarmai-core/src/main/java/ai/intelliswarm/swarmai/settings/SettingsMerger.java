package ai.intelliswarm.swarmai.settings;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Layers per-{@link SettingsLayer} configuration trees and produces a
 * {@link MergedSettings} where each effective value carries provenance
 * (which layer it came from).
 *
 * <p>Resolution: PROJECT &gt; USER &gt; MACHINE. Nested maps are flattened
 * to dotted keys, so a project-layer override of {@code llm.model.name}
 * doesn't accidentally erase the user-layer's {@code llm.model.temperature}.
 *
 * <p>The merger is YAML-loader-agnostic: callers parse YAML / JSON / .env
 * files into {@code Map&lt;String,Object&gt;} (using whatever library they
 * already have) and pass the maps in via {@link #with}. Keeps swarmai-core
 * free of a YAML dependency.
 */
@PublicApi(since = "1.0.24")
public final class SettingsMerger {

    private final Map<SettingsLayer, Map<String, Object>> perLayer = new LinkedHashMap<>();

    public SettingsMerger with(SettingsLayer layer, Map<String, Object> tree) {
        Objects.requireNonNull(layer, "layer must not be null");
        Objects.requireNonNull(tree, "tree must not be null");
        perLayer.put(layer, tree);
        return this;
    }

    /** Compute the effective merged view. */
    public MergedSettings merge() {
        Map<String, Object> values = new HashMap<>();
        Map<String, SettingsLayer> provenance = new HashMap<>();
        // Iterate from lowest priority to highest, so PROJECT overwrites USER overwrites MACHINE.
        SettingsLayer[] order = { SettingsLayer.MACHINE, SettingsLayer.USER, SettingsLayer.PROJECT };
        for (SettingsLayer layer : order) {
            Map<String, Object> tree = perLayer.get(layer);
            if (tree == null) continue;
            for (Map.Entry<String, Object> e : flatten(tree, "").entrySet()) {
                values.put(e.getKey(), e.getValue());
                provenance.put(e.getKey(), layer);
            }
        }
        return new MergedSettings(values, provenance);
    }

    /** Recursive depth-first flatten. {@code prefix} accumulates the dotted path. */
    private static Map<String, Object> flatten(Map<String, Object> in, String prefix) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : in.entrySet()) {
            String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            Object v = e.getValue();
            if (v instanceof Map<?, ?> nested) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) nested;
                out.putAll(flatten(typed, key));
            } else {
                out.put(key, v);
            }
        }
        return out;
    }
}
