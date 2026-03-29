package ai.intelliswarm.swarmai.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AgentState Tests")
class AgentStateTest {

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("creates empty state")
        void createsEmptyState() {
            AgentState state = AgentState.empty();
            assertTrue(state.isEmpty());
            assertEquals(0, state.size());
        }

        @Test
        @DisplayName("creates state from map")
        void createsFromMap() {
            AgentState state = AgentState.of(Map.of("topic", "AI", "count", 42));
            assertEquals(2, state.size());
            assertEquals(Optional.of("AI"), state.value("topic"));
            assertEquals(Optional.of(42), state.value("count"));
        }

        @Test
        @DisplayName("creates with schema and applies defaults")
        void createsWithSchemaDefaults() {
            StateSchema schema = StateSchema.builder()
                    .channel("count", Channels.counter())
                    .channel("items", Channels.appender())
                    .build();

            AgentState state = AgentState.of(schema, Map.of());
            assertEquals(Optional.of(0L), state.value("count"));
            assertEquals(Optional.of(List.of()), state.value("items"));
        }

        @Test
        @DisplayName("initial data overrides schema defaults")
        void initialDataOverridesDefaults() {
            StateSchema schema = StateSchema.builder()
                    .channel("count", Channels.counter())
                    .build();

            AgentState state = AgentState.of(schema, Map.of("count", 100L));
            assertEquals(Optional.of(100L), state.value("count"));
        }

        @Test
        @DisplayName("data map is unmodifiable")
        void dataIsUnmodifiable() {
            AgentState state = AgentState.of(Map.of("key", "value"));
            assertThrows(UnsupportedOperationException.class, () -> state.data().put("new", "value"));
        }

        @Test
        @DisplayName("rejects undeclared keys at construction in strict mode")
        void rejectsUndeclaredKeysAtConstructionInStrictMode() {
            StateSchema schema = StateSchema.builder()
                    .channel("allowed", Channels.lastWriteWins())
                    .allowUndeclaredKeys(false)
                    .build();

            assertThrows(IllegalArgumentException.class,
                    () -> AgentState.of(schema, Map.of("forbidden", "value")));
        }
    }

    @Nested
    @DisplayName("value()")
    class ValueTests {

        @Test
        @DisplayName("returns value when present")
        void returnsValueWhenPresent() {
            AgentState state = AgentState.of(Map.of("name", "test"));
            Optional<String> result = state.value("name");
            assertTrue(result.isPresent());
            assertEquals("test", result.get());
        }

        @Test
        @DisplayName("returns empty when absent")
        void returnsEmptyWhenAbsent() {
            AgentState state = AgentState.of(Map.of("name", "test"));
            Optional<String> result = state.value("missing");
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("valueOrDefault returns default when absent")
        void valueOrDefaultReturnsDefault() {
            AgentState state = AgentState.empty();
            assertEquals("fallback", state.valueOrDefault("missing", "fallback"));
        }

        @Test
        @DisplayName("valueOrDefault returns value when present")
        void valueOrDefaultReturnsValue() {
            AgentState state = AgentState.of(Map.of("key", "real"));
            assertEquals("real", state.valueOrDefault("key", "fallback"));
        }
    }

    @Nested
    @DisplayName("withUpdate()")
    class WithUpdateTests {

        @Test
        @DisplayName("produces new state without modifying original")
        void immutableUpdate() {
            AgentState original = AgentState.of(Map.of("a", 1));
            AgentState updated = original.withValue("b", 2);

            assertFalse(original.hasKey("b"));
            assertTrue(updated.hasKey("b"));
            assertEquals(Optional.of(1), updated.value("a"));
        }

        @Test
        @DisplayName("last-write-wins for undeclared channels in permissive mode")
        void lastWriteWinsForUndeclaredKeys() {
            AgentState state = AgentState.of(Map.of("status", "old"));
            AgentState updated = state.withValue("status", "new");
            assertEquals(Optional.of("new"), updated.value("status"));
        }

        @Test
        @DisplayName("uses channel reducer for declared channels")
        void usesChannelReducer() {
            StateSchema schema = StateSchema.builder()
                    .channel("count", Channels.counter())
                    .allowUndeclaredKeys(true)
                    .build();

            AgentState state = AgentState.of(schema, Map.of());
            AgentState updated = state.withUpdate(Map.of("count", 5L));
            updated = updated.withUpdate(Map.of("count", 3L));

            assertEquals(Optional.of(8L), updated.value("count"));
        }

        @Test
        @DisplayName("appender channel accumulates list items")
        void appenderChannelAccumulates() {
            StateSchema schema = StateSchema.builder()
                    .channel("messages", Channels.<String>appender())
                    .allowUndeclaredKeys(true)
                    .build();

            AgentState state = AgentState.of(schema, Map.of());
            state = state.withUpdate(Map.of("messages", List.of("hello")));
            state = state.withUpdate(Map.of("messages", List.of("world")));

            Optional<List<String>> msgs = state.value("messages");
            assertTrue(msgs.isPresent());
            assertEquals(List.of("hello", "world"), msgs.get());
        }

        @Test
        @DisplayName("appender channel deduplicates by default")
        void appenderDeduplicates() {
            StateSchema schema = StateSchema.builder()
                    .channel("items", Channels.<String>appender())
                    .allowUndeclaredKeys(true)
                    .build();

            AgentState state = AgentState.of(schema, Map.of());
            state = state.withUpdate(Map.of("items", List.of("a", "b")));
            state = state.withUpdate(Map.of("items", List.of("b", "c")));

            Optional<List<String>> items = state.value("items");
            assertEquals(List.of("a", "b", "c"), items.get());
        }

        @Test
        @DisplayName("rejects undeclared keys in strict mode")
        void rejectsUndeclaredKeysInStrictMode() {
            StateSchema schema = StateSchema.builder()
                    .channel("allowed", Channels.lastWriteWins())
                    .allowUndeclaredKeys(false)
                    .build();

            AgentState state = AgentState.of(schema, Map.of());
            assertThrows(IllegalArgumentException.class, () -> state.withValue("forbidden", "value"));
        }

        @Test
        @DisplayName("null update returns same state")
        void nullUpdateReturnsSame() {
            AgentState state = AgentState.of(Map.of("a", 1));
            assertSame(state, state.withUpdate(null));
            assertSame(state, state.withUpdate(Map.of()));
        }
    }

    @Nested
    @DisplayName("withoutKey()")
    class WithoutKeyTests {

        @Test
        @DisplayName("removes key from state")
        void removesKey() {
            AgentState state = AgentState.of(Map.of("a", 1, "b", 2));
            AgentState updated = state.withoutKey("a");
            assertFalse(updated.hasKey("a"));
            assertTrue(updated.hasKey("b"));
        }
    }

    @Nested
    @DisplayName("mergeState() static utility")
    class MergeStateTests {

        @Test
        @DisplayName("merges with channel reducer")
        void mergesWithReducer() {
            Map<String, Object> current = Map.of("count", 10L);
            Map<String, Object> update = Map.of("count", 5L);
            Map<String, Channel<?>> channels = Map.of("count", Channels.counter());

            Map<String, Object> merged = AgentState.mergeState(current, update, channels);
            assertEquals(15L, merged.get("count"));
        }

        @Test
        @DisplayName("last-write-wins without channel")
        void lastWriteWinsWithoutChannel() {
            Map<String, Object> current = Map.of("status", "old");
            Map<String, Object> update = Map.of("status", "new");

            Map<String, Object> merged = AgentState.mergeState(current, update, null);
            assertEquals("new", merged.get("status"));
        }
    }

    @Nested
    @DisplayName("Equality and toString")
    class EqualityTests {

        @Test
        @DisplayName("equal states are equal")
        void equalStates() {
            AgentState a = AgentState.of(Map.of("key", "val"));
            AgentState b = AgentState.of(Map.of("key", "val"));
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("toString includes keys")
        void toStringIncludesKeys() {
            AgentState state = AgentState.of(Map.of("topic", "AI"));
            assertTrue(state.toString().contains("topic"));
        }
    }
}
