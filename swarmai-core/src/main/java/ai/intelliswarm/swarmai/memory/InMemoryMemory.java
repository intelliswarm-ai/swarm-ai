package ai.intelliswarm.swarmai.memory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * In-memory implementation of the Memory interface.
 * Provides a simple, thread-safe memory storage for agents.
 * Suitable for testing and single-instance deployments.
 *
 * <p>Storage is bounded: once {@code maxEntries} is reached the oldest entry is
 * evicted on each save (ring-buffer semantics), so long-running processes cannot
 * grow without limit. Entries are kept in insertion (chronological) order, so
 * recency queries iterate newest-first without re-sorting.
 *
 * <p>Search uses OR keyword semantics (see {@link MemoryKeywords}): results are
 * ranked by number of distinct query keywords matched, then by recency.
 */
public class InMemoryMemory implements Memory {

    /** Default cap; roughly a few MB of truncated task summaries. */
    public static final int DEFAULT_MAX_ENTRIES = 10_000;

    /** All entries in insertion order — the single source of truth. */
    private final Deque<MemoryEntry> entries = new ConcurrentLinkedDeque<>();
    /** Per-agent views holding the same entry references (no content duplication). */
    private final Map<String, Deque<MemoryEntry>> agentMemories = new ConcurrentHashMap<>();
    private final AtomicInteger count = new AtomicInteger();
    private final int maxEntries;

    public InMemoryMemory() {
        this(DEFAULT_MAX_ENTRIES);
    }

    /**
     * @param maxEntries maximum number of entries kept; oldest are evicted first.
     *                   Must be positive.
     */
    public InMemoryMemory(int maxEntries) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive, got " + maxEntries);
        }
        this.maxEntries = maxEntries;
    }

    @Override
    public void save(String agentId, String content, Map<String, Object> metadata) {
        MemoryEntry entry = new MemoryEntry(agentId, content, metadata, LocalDateTime.now());

        entries.addLast(entry);
        if (agentId != null) {
            agentMemories.computeIfAbsent(agentId, k -> new ConcurrentLinkedDeque<>())
                    .addLast(entry);
        }

        if (count.incrementAndGet() > maxEntries) {
            evictOldest();
        }
    }

    private void evictOldest() {
        MemoryEntry oldest = entries.pollFirst();
        if (oldest == null) {
            return;
        }
        count.decrementAndGet();
        if (oldest.agentId() != null) {
            Deque<MemoryEntry> agentDeque = agentMemories.get(oldest.agentId());
            if (agentDeque != null) {
                agentDeque.removeFirstOccurrence(oldest);
            }
        }
    }

    @Override
    public List<String> search(String query, int limit) {
        return searchInternal(query, limit, entry -> true);
    }

    @Override
    public List<String> searchByAgentPrefix(String query, String agentPrefix, int limit) {
        if (agentPrefix == null || agentPrefix.isEmpty()) {
            return search(query, limit);
        }
        return searchInternal(query, limit,
                entry -> entry.agentId() != null && entry.agentId().startsWith(agentPrefix));
    }

    /**
     * Scores entries newest-first with keyword OR semantics, then returns the top
     * {@code limit} by (keywords matched, recency). Only matches are sorted, so the
     * per-query cost is a single linear scan of the bounded store.
     */
    private List<String> searchInternal(String query, int limit, Predicate<MemoryEntry> scope) {
        if (query == null || query.isBlank() || limit <= 0) {
            return Collections.emptyList();
        }

        List<String> keywords = MemoryKeywords.tokenize(query);
        String rawQuery = query.toLowerCase();

        record Match(MemoryEntry entry, int score, long recency) {
        }

        List<Match> matches = new ArrayList<>();
        long recency = 0;
        // descendingIterator: entries are stored in insertion order, so this is newest-first
        for (Iterator<MemoryEntry> it = entries.descendingIterator(); it.hasNext(); ) {
            MemoryEntry entry = it.next();
            recency++;
            if (!scope.test(entry)) {
                continue;
            }
            int score = keywords.isEmpty()
                    ? (entry.content().toLowerCase().contains(rawQuery) ? 1 : 0)
                    : MemoryKeywords.score(entry.content(), keywords);
            if (score > 0) {
                matches.add(new Match(entry, score, recency));
            }
        }

        return matches.stream()
                .sorted(Comparator.comparingInt(Match::score).reversed()
                        .thenComparingLong(Match::recency))
                .limit(limit)
                .map(m -> m.entry().content())
                .toList();
    }

    @Override
    public List<String> getRecentMemories(String agentId, int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }
        Deque<MemoryEntry> source;
        if (agentId == null) {
            source = entries;
        } else {
            source = agentMemories.get(agentId);
            if (source == null || source.isEmpty()) {
                return Collections.emptyList();
            }
        }

        List<String> recent = new ArrayList<>(Math.min(limit, 16));
        for (Iterator<MemoryEntry> it = source.descendingIterator(); it.hasNext() && recent.size() < limit; ) {
            recent.add(it.next().content());
        }
        return recent;
    }

    @Override
    public void clear() {
        entries.clear();
        agentMemories.clear();
        count.set(0);
    }

    @Override
    public void clearForAgent(String agentId) {
        if (agentId != null) {
            agentMemories.remove(agentId);
            entries.removeIf(entry -> {
                if (agentId.equals(entry.agentId())) {
                    count.decrementAndGet();
                    return true;
                }
                return false;
            });
        }
    }

    @Override
    public int size() {
        return count.get();
    }

    @Override
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * Gets the count of memories for a specific agent.
     */
    public int sizeForAgent(String agentId) {
        Deque<MemoryEntry> memories = agentMemories.get(agentId);
        return memories != null ? memories.size() : 0;
    }

    /**
     * Internal record to store memory entries with metadata.
     */
    private record MemoryEntry(
            String agentId,
            String content,
            Map<String, Object> metadata,
            LocalDateTime timestamp
    ) {
    }
}
