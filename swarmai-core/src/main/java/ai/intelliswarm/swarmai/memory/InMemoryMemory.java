package ai.intelliswarm.swarmai.memory;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory implementation of the Memory interface.
 * Provides a simple, thread-safe memory storage for agents.
 * Suitable for testing and single-instance deployments.
 */
public class InMemoryMemory implements Memory {

    private final Map<String, List<MemoryEntry>> agentMemories = new ConcurrentHashMap<>();
    private final List<MemoryEntry> globalMemories = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void save(String agentId, String content, Map<String, Object> metadata) {
        MemoryEntry entry = new MemoryEntry(agentId, content, metadata, LocalDateTime.now());

        if (agentId != null) {
            agentMemories.computeIfAbsent(agentId, k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(entry);
        }

        globalMemories.add(entry);
    }

    @Override
    public List<String> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        String lowerQuery = query.toLowerCase();

        return globalMemories.stream()
                .filter(entry -> entry.content().toLowerCase().contains(lowerQuery))
                .sorted(Comparator.comparing(MemoryEntry::timestamp).reversed())
                .limit(limit)
                .map(MemoryEntry::content)
                .collect(Collectors.toList());
    }

    @Override
    public List<String> getRecentMemories(String agentId, int limit) {
        if (agentId == null) {
            return globalMemories.stream()
                    .sorted(Comparator.comparing(MemoryEntry::timestamp).reversed())
                    .limit(limit)
                    .map(MemoryEntry::content)
                    .collect(Collectors.toList());
        }

        List<MemoryEntry> memories = agentMemories.get(agentId);
        if (memories == null || memories.isEmpty()) {
            return Collections.emptyList();
        }

        return memories.stream()
                .sorted(Comparator.comparing(MemoryEntry::timestamp).reversed())
                .limit(limit)
                .map(MemoryEntry::content)
                .collect(Collectors.toList());
    }

    @Override
    public void clear() {
        agentMemories.clear();
        globalMemories.clear();
    }

    @Override
    public void clearForAgent(String agentId) {
        if (agentId != null) {
            agentMemories.remove(agentId);
            globalMemories.removeIf(entry -> agentId.equals(entry.agentId()));
        }
    }

    @Override
    public int size() {
        return globalMemories.size();
    }

    @Override
    public boolean isEmpty() {
        return globalMemories.isEmpty();
    }

    /**
     * Gets the count of memories for a specific agent.
     */
    public int sizeForAgent(String agentId) {
        List<MemoryEntry> memories = agentMemories.get(agentId);
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
