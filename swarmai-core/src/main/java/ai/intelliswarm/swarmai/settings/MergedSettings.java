package ai.intelliswarm.swarmai.settings;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only view over a {@link SettingsMerger#merge merged} configuration tree
 * with provenance tracked per top-level key. {@link #get} returns the
 * effective value (winning layer); {@link #provenance} returns which layer
 * supplied that value — useful for "where did this setting come from?"
 * support cases.
 *
 * <p>Nested values are flattened to dotted keys ({@code llm.model.name}).
 */
@PublicApi(since = "1.0.24")
public final class MergedSettings {

    private final Map<String, Object> values;
    private final Map<String, SettingsLayer> provenance;

    public MergedSettings(Map<String, Object> values, Map<String, SettingsLayer> provenance) {
        Objects.requireNonNull(values, "values must not be null");
        Objects.requireNonNull(provenance, "provenance must not be null");
        this.values = Map.copyOf(values);
        this.provenance = Map.copyOf(provenance);
    }

    /** All effective values, flattened to dotted keys. */
    public Map<String, Object> values() {
        return values;
    }

    /** Get an effective value or {@link Optional#empty()} if unset. */
    public Optional<Object> get(String dottedKey) {
        return Optional.ofNullable(values.get(dottedKey));
    }

    /** Which layer supplied the effective value for {@code dottedKey}. */
    public Optional<SettingsLayer> provenance(String dottedKey) {
        return Optional.ofNullable(provenance.get(dottedKey));
    }
}
