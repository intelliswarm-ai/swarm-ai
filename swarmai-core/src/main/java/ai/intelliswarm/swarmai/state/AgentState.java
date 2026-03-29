package ai.intelliswarm.swarmai.state;

import java.util.*;
import java.util.function.Supplier;

/**
 * Immutable, type-safe state container for swarm workflow execution.
 * Replaces raw {@code Map<String, Object>} with structured access and
 * channel-based merge semantics for concurrent updates.
 *
 * <p>State is read via typed {@link #value(String)} and updated via
 * {@link #withUpdate(Map)} which applies channel reducers to produce a new state.
 *
 * <p>Usage example:
 * <pre>{@code
 * StateSchema schema = StateSchema.builder()
 *     .channel("messages", Channels.appender())
 *     .channel("count", Channels.counter())
 *     .build();
 *
 * AgentState state = AgentState.of(schema, Map.of("topic", "AI"));
 * Optional<String> topic = state.value("topic");
 *
 * // Merge with channel semantics
 * AgentState updated = state.withUpdate(Map.of("count", 5L));
 * }</pre>
 */
public class AgentState {

    private final Map<String, Object> data;
    private final StateSchema schema;

    /**
     * Creates a new AgentState with the given schema and initial data.
     */
    public AgentState(StateSchema schema, Map<String, Object> data) {
        this.schema = schema != null ? schema : StateSchema.PERMISSIVE;
        Map<String, Object> initialized = new LinkedHashMap<>();
        if (data != null) {
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                String key = entry.getKey();
                if (!this.schema.isValidKey(key)) {
                    throw new IllegalArgumentException(
                            "Key '" + key + "' is not declared in the state schema. " +
                            "Declared keys: " + this.schema.getChannels().keySet());
                }
                initialized.put(key, entry.getValue());
            }
        }
        // Apply default values from schema channels where data doesn't already have a value
        for (Map.Entry<String, Channel<?>> entry : this.schema.getChannels().entrySet()) {
            if (!initialized.containsKey(entry.getKey())) {
                entry.getValue().getDefault()
                        .map(Supplier::get)
                        .ifPresent(defaultVal -> initialized.put(entry.getKey(), defaultVal));
            }
        }
        this.data = Collections.unmodifiableMap(initialized);
    }

    /**
     * Creates an AgentState with the permissive schema (backward-compatible).
     */
    public AgentState(Map<String, Object> data) {
        this(StateSchema.PERMISSIVE, data);
    }

    /**
     * Creates an empty state with the permissive schema.
     */
    public AgentState() {
        this(StateSchema.PERMISSIVE, Map.of());
    }

    // ========================================
    // Factory methods
    // ========================================

    /**
     * Creates an AgentState from a map, using the permissive schema.
     * Primary backward-compatibility bridge for existing {@code Map<String, Object>} callers.
     */
    public static AgentState of(Map<String, Object> data) {
        return new AgentState(StateSchema.PERMISSIVE, data);
    }

    /**
     * Creates an AgentState with a specific schema and initial data.
     */
    public static AgentState of(StateSchema schema, Map<String, Object> data) {
        return new AgentState(schema, data);
    }

    /**
     * Creates an empty AgentState with the permissive schema.
     */
    public static AgentState empty() {
        return new AgentState(StateSchema.PERMISSIVE, Map.of());
    }

    // ========================================
    // Typed value access
    // ========================================

    /**
     * Retrieves a typed value from the state.
     *
     * @param key the state key
     * @param <T> the expected type
     * @return the value, or empty if not present
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> value(String key) {
        return Optional.ofNullable((T) data.get(key));
    }

    /**
     * Retrieves a typed value with a default fallback.
     *
     * @param key          the state key
     * @param defaultValue value returned if key is absent
     * @param <T>          the expected type
     * @return the value, or defaultValue if not present
     */
    @SuppressWarnings("unchecked")
    public <T> T valueOrDefault(String key, T defaultValue) {
        Object val = data.get(key);
        return val != null ? (T) val : defaultValue;
    }

    /**
     * Returns the raw underlying data as an unmodifiable map.
     * Primary backward-compatibility bridge for existing code that expects {@code Map<String, Object>}.
     */
    public Map<String, Object> data() {
        return data;
    }

    /**
     * Returns true if the state contains the given key.
     */
    public boolean hasKey(String key) {
        return data.containsKey(key);
    }

    /**
     * Returns the number of entries in the state.
     */
    public int size() {
        return data.size();
    }

    /**
     * Returns true if the state has no entries.
     */
    public boolean isEmpty() {
        return data.isEmpty();
    }

    /**
     * Returns the schema for this state.
     */
    public StateSchema schema() {
        return schema;
    }

    // ========================================
    // State updates (immutable — returns new state)
    // ========================================

    /**
     * Produces a new state by merging the given partial updates using channel reducers.
     * For channels with a reducer, the old and new values are merged.
     * For channels without a reducer (or undeclared keys in permissive mode), last-write-wins.
     *
     * @param partialUpdate a map of key-value updates
     * @return a new AgentState with the updates applied
     * @throws IllegalArgumentException if a key is not valid for this schema
     */
    public AgentState withUpdate(Map<String, Object> partialUpdate) {
        if (partialUpdate == null || partialUpdate.isEmpty()) {
            return this;
        }

        Map<String, Object> newData = new LinkedHashMap<>(this.data);

        for (Map.Entry<String, Object> entry : partialUpdate.entrySet()) {
            String key = entry.getKey();
            Object newValue = entry.getValue();

            if (!schema.isValidKey(key)) {
                throw new IllegalArgumentException(
                        "Key '" + key + "' is not declared in the state schema. " +
                        "Declared keys: " + schema.getChannels().keySet());
            }

            Channel<?> channel = schema.getChannel(key);
            if (channel != null) {
                // Use channel's reducer for merge semantics
                Object oldValue = newData.get(key);
                newData.put(key, channel.update(oldValue, newValue));
            } else {
                // Undeclared key in permissive mode — last-write-wins
                newData.put(key, newValue);
            }
        }

        return new AgentState(schema, newData);
    }

    /**
     * Produces a new state with a single key-value update.
     */
    public AgentState withValue(String key, Object value) {
        return withUpdate(Map.of(key, value));
    }

    /**
     * Produces a new state with the given key removed.
     */
    public AgentState withoutKey(String key) {
        Map<String, Object> newData = new LinkedHashMap<>(this.data);
        newData.remove(key);
        return new AgentState(schema, newData);
    }

    // ========================================
    // Static merge utility
    // ========================================

    /**
     * Merges a partial update into an existing data map using channel definitions.
     * This is a static utility used by process implementations during concurrent state updates.
     */
    public static Map<String, Object> mergeState(
            Map<String, Object> currentState,
            Map<String, Object> partialUpdate,
            Map<String, Channel<?>> channels) {
        if (partialUpdate == null || partialUpdate.isEmpty()) {
            return currentState;
        }

        Map<String, Object> merged = new LinkedHashMap<>(currentState);

        for (Map.Entry<String, Object> entry : partialUpdate.entrySet()) {
            String key = entry.getKey();
            Object newValue = entry.getValue();
            Channel<?> channel = channels != null ? channels.get(key) : null;

            if (channel != null) {
                Object oldValue = merged.get(key);
                merged.put(key, channel.update(oldValue, newValue));
            } else {
                merged.put(key, newValue);
            }
        }

        return merged;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AgentState that)) return false;
        return Objects.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(data);
    }

    @Override
    public String toString() {
        return "AgentState{keys=" + data.keySet() + ", size=" + data.size() + "}";
    }
}
