package ai.intelliswarm.swarmai.state;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Defines the shape of an {@link AgentState} by declaring named channels and their types.
 * A schema is used at compile time to validate that state updates reference valid channels,
 * and at runtime to apply the correct merge semantics.
 *
 * <p>Schemas are immutable once built. Use {@link #builder()} to construct one.
 *
 * <p>Usage example:
 * <pre>{@code
 * StateSchema schema = StateSchema.builder()
 *     .channel("messages", Channels.appender())
 *     .channel("totalTokens", Channels.counter())
 *     .channel("status", Channels.lastWriteWins("PENDING"))
 *     .build();
 * }</pre>
 */
public final class StateSchema {

    /**
     * A permissive schema that accepts any key with last-write-wins semantics.
     * Used as default for backward compatibility with {@code Map<String, Object>} inputs.
     */
    public static final StateSchema PERMISSIVE = new StateSchema(Map.of(), true);

    private final Map<String, Channel<?>> channels;
    private final boolean allowUndeclaredKeys;

    private StateSchema(Map<String, Channel<?>> channels, boolean allowUndeclaredKeys) {
        this.channels = Collections.unmodifiableMap(new LinkedHashMap<>(channels));
        this.allowUndeclaredKeys = allowUndeclaredKeys;
    }

    /**
     * Returns the channel definition for the given key, or empty if not declared.
     */
    public Channel<?> getChannel(String key) {
        return channels.get(key);
    }

    /**
     * Returns all declared channels.
     */
    public Map<String, Channel<?>> getChannels() {
        return channels;
    }

    /**
     * Whether this schema accepts keys not explicitly declared as channels.
     * When true, undeclared keys use last-write-wins semantics.
     */
    public boolean isAllowUndeclaredKeys() {
        return allowUndeclaredKeys;
    }

    /**
     * Returns true if the given key is either declared or allowed by permissive mode.
     */
    public boolean isValidKey(String key) {
        return channels.containsKey(key) || allowUndeclaredKeys;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final Map<String, Channel<?>> channels = new LinkedHashMap<>();
        private boolean allowUndeclaredKeys = false;

        public Builder channel(String name, Channel<?> channel) {
            Objects.requireNonNull(name, "Channel name cannot be null");
            Objects.requireNonNull(channel, "Channel cannot be null");
            this.channels.put(name, channel);
            return this;
        }

        /**
         * When enabled, state updates can include keys not declared in the schema.
         * Undeclared keys use last-write-wins semantics. Default is false (strict mode).
         */
        public Builder allowUndeclaredKeys(boolean allow) {
            this.allowUndeclaredKeys = allow;
            return this;
        }

        public StateSchema build() {
            return new StateSchema(channels, allowUndeclaredKeys);
        }
    }

    @Override
    public String toString() {
        return "StateSchema{channels=" + channels.keySet() +
                ", allowUndeclaredKeys=" + allowUndeclaredKeys + '}';
    }
}
