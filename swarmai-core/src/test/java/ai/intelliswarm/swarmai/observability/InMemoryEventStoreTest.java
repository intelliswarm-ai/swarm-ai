package ai.intelliswarm.swarmai.observability;

import ai.intelliswarm.swarmai.observability.config.ObservabilityProperties;
import ai.intelliswarm.swarmai.observability.event.EnrichedSwarmEvent;
import ai.intelliswarm.swarmai.observability.replay.InMemoryEventStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for InMemoryEventStore — the event persistence layer for workflow replay.
 *
 * If events aren't stored correctly, decision tracing and audit trails break.
 * These tests verify storage, retrieval, eviction, and boundary conditions.
 */
@DisplayName("InMemoryEventStore — Event Storage and Retrieval")
class InMemoryEventStoreTest {

    private InMemoryEventStore store;
    private ObservabilityProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ObservabilityProperties();
        properties.setReplayEnabled(true);
        properties.getReplay().setMaxEventsInMemory(100);
        store = new InMemoryEventStore(properties);
    }

    @Nested
    @DisplayName("Basic Store and Retrieve")
    class BasicStoreRetrieve {

        @Test
        @DisplayName("store and retrieve by correlationId")
        void storeAndRetrieve() {
            EnrichedSwarmEvent event = mockEvent("corr-1", "swarm-1", "TASK_COMPLETED", Instant.now());
            store.store(event);

            List<EnrichedSwarmEvent> events = store.getByCorrelationId("corr-1");
            assertEquals(1, events.size());
            assertEquals("corr-1", events.get(0).getCorrelationId());
        }

        @Test
        @DisplayName("retrieve by swarmId aggregates across correlations")
        void retrieveBySwarm() {
            store.store(mockEvent("corr-1", "swarm-1", "TASK_STARTED", Instant.now()));
            store.store(mockEvent("corr-2", "swarm-1", "TASK_COMPLETED", Instant.now()));

            List<EnrichedSwarmEvent> events = store.getBySwarmId("swarm-1");
            assertEquals(2, events.size(), "Should find events from both correlations for same swarm");
        }

        @Test
        @DisplayName("returns empty list for unknown correlationId")
        void emptyForUnknown() {
            List<EnrichedSwarmEvent> events = store.getByCorrelationId("nonexistent");
            assertTrue(events.isEmpty());
        }

        @Test
        @DisplayName("ignores events with null correlationId")
        void ignoresNullCorrelation() {
            EnrichedSwarmEvent event = mockEvent(null, "swarm-1", "TASK_STARTED", Instant.now());
            store.store(event);
            assertEquals(0, store.getTotalEventCount());
        }

        @Test
        @DisplayName("ignores events when replay is disabled")
        void ignoresWhenDisabled() {
            properties.setReplayEnabled(false);
            store = new InMemoryEventStore(properties);

            store.store(mockEvent("corr-1", "swarm-1", "TASK_STARTED", Instant.now()));
            assertEquals(0, store.getTotalEventCount());
        }
    }

    @Nested
    @DisplayName("Event Count Tracking")
    class EventCounting {

        @Test
        @DisplayName("totalEventCount tracks correctly")
        void totalCount() {
            store.store(mockEvent("c1", "s1", "E1", Instant.now()));
            store.store(mockEvent("c1", "s1", "E2", Instant.now()));
            store.store(mockEvent("c2", "s1", "E3", Instant.now()));

            assertEquals(3, store.getTotalEventCount());
        }

        @Test
        @DisplayName("hasEvents returns true for existing correlation")
        void hasEvents() {
            store.store(mockEvent("c1", "s1", "E1", Instant.now()));
            assertTrue(store.hasEvents("c1"));
            assertFalse(store.hasEvents("c2"));
        }
    }

    @Nested
    @DisplayName("Eviction (Max Events Boundary)")
    class Eviction {

        @Test
        @DisplayName("evicts oldest correlation when max events reached")
        void evictsOldest() {
            properties.getReplay().setMaxEventsInMemory(5);
            store = new InMemoryEventStore(properties);

            // Store 5 events in correlation "old"
            Instant oldTime = Instant.now().minusSeconds(3600);
            for (int i = 0; i < 5; i++) {
                store.store(mockEvent("old-corr", "s1", "E" + i, oldTime.plusSeconds(i)));
            }

            assertEquals(5, store.getTotalEventCount());

            // Store 1 more in "new" — should trigger eviction of "old"
            store.store(mockEvent("new-corr", "s1", "E-new", Instant.now()));

            assertTrue(store.getTotalEventCount() <= 5,
                "Should evict to stay within max. Got: " + store.getTotalEventCount());
            assertTrue(store.hasEvents("new-corr"),
                "New correlation should survive eviction");
        }
    }

    @Nested
    @DisplayName("Time Range Queries")
    class TimeRangeQueries {

        @Test
        @DisplayName("getByTimeRange returns events within [start, end)")
        void timeRangeInclusive() {
            Instant t1 = Instant.parse("2026-04-01T10:00:00Z");
            Instant t2 = Instant.parse("2026-04-01T11:00:00Z");
            Instant t3 = Instant.parse("2026-04-01T12:00:00Z");

            store.store(mockEvent("c1", "s1", "E1", t1));
            store.store(mockEvent("c1", "s1", "E2", t2));
            store.store(mockEvent("c1", "s1", "E3", t3));

            Instant start = Instant.parse("2026-04-01T10:30:00Z");
            Instant end = Instant.parse("2026-04-01T12:00:00Z");

            List<EnrichedSwarmEvent> results = store.getByTimeRange(start, end);

            assertEquals(1, results.size(),
                "Should return events in [10:30, 12:00) — only t2 (11:00) qualifies");
        }
    }

    @Nested
    @DisplayName("Deletion")
    class Deletion {

        @Test
        @DisplayName("deleteByCorrelationId removes all events and updates count")
        void deleteByCorrelation() {
            store.store(mockEvent("c1", "s1", "E1", Instant.now()));
            store.store(mockEvent("c1", "s1", "E2", Instant.now()));
            store.store(mockEvent("c2", "s1", "E3", Instant.now()));

            int deleted = store.deleteByCorrelationId("c1");

            assertEquals(2, deleted);
            assertEquals(1, store.getTotalEventCount());
            assertFalse(store.hasEvents("c1"));
            assertTrue(store.hasEvents("c2"));
        }

        @Test
        @DisplayName("clear() removes everything")
        void clearAll() {
            store.store(mockEvent("c1", "s1", "E1", Instant.now()));
            store.store(mockEvent("c2", "s1", "E2", Instant.now()));

            store.clear();

            assertEquals(0, store.getTotalEventCount());
            assertFalse(store.hasEvents("c1"));
            assertFalse(store.hasEvents("c2"));
        }
    }

    @Nested
    @DisplayName("Statistics")
    class Statistics {

        @Test
        @DisplayName("getStats returns meaningful distribution data")
        void statsArePopulated() {
            store.store(mockEvent("c1", "s1", "E1", Instant.now()));
            store.store(mockEvent("c1", "s1", "E2", Instant.now()));
            store.store(mockEvent("c2", "s1", "E3", Instant.now()));

            var stats = store.getStats();

            assertEquals(2, stats.get("totalCorrelations"));
            assertEquals(3, stats.get("totalEvents"));
        }
    }

    // ================================================================
    // Helpers
    // ================================================================

    private EnrichedSwarmEvent mockEvent(String correlationId, String swarmId,
                                          String eventType, Instant timestamp) {
        EnrichedSwarmEvent event = mock(EnrichedSwarmEvent.class);
        when(event.getCorrelationId()).thenReturn(correlationId);
        when(event.getSwarmId()).thenReturn(swarmId);
        when(event.getEventInstant()).thenReturn(timestamp);
        // eventType is stored in the base SwarmEvent, not exposed directly on EnrichedSwarmEvent
        return event;
    }
}
