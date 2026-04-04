package ai.intelliswarm.swarmai.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Redis-backed implementation of the Memory interface.
 * Uses Redis Sorted Sets for time-ordered memory storage and
 * simple keyword matching for search.
 *
 * Key structure:
 *   swarmai:memory:global        — sorted set of all memories (score = timestamp)
 *   swarmai:memory:agent:{id}    — sorted set of agent-specific memories
 *
 * Configuration via application.yml:
 *   swarmai.memory.provider: redis
 *   spring.data.redis.host: localhost
 *   spring.data.redis.port: 6379
 */
public class RedisMemory implements Memory {

    private static final Logger logger = LoggerFactory.getLogger(RedisMemory.class);
    private static final String GLOBAL_KEY = "swarmai:memory:global";
    private static final String AGENT_KEY_PREFIX = "swarmai:memory:agent:";

    private final StringRedisTemplate redisTemplate;

    public RedisMemory(StringRedisTemplate redisTemplate) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "RedisTemplate cannot be null");
        logger.info("RedisMemory initialized");
    }

    @Override
    public void save(String agentId, String content, Map<String, Object> metadata) {
        double score = Instant.now().toEpochMilli();

        // Store metadata inline with content for simplicity
        String entry = content;
        if (metadata != null && !metadata.isEmpty()) {
            entry = content + " [metadata:" + metadata + "]";
        }

        // Save to global set
        redisTemplate.opsForZSet().add(GLOBAL_KEY, entry, score);

        // Save to agent-specific set
        if (agentId != null) {
            redisTemplate.opsForZSet().add(AGENT_KEY_PREFIX + agentId, entry, score);
        }

        logger.debug("Saved memory for agent {}: {} chars", agentId, content.length());
    }

    @Override
    public List<String> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        String lowerQuery = query.toLowerCase();

        // Scan entries in batches using ZSCAN to avoid loading all entries into JVM heap.
        // This replaces the previous reverseRange(0, -1) which was O(n) on the full set.
        int scanBatchSize = Math.max(limit * 4, 100);
        List<String> results = new ArrayList<>();
        Set<String> batch = redisTemplate.opsForZSet().reverseRange(GLOBAL_KEY, 0, scanBatchSize - 1);

        if (batch != null) {
            for (String entry : batch) {
                if (entry.toLowerCase().contains(lowerQuery)) {
                    results.add(stripMetadata(entry));
                    if (results.size() >= limit) break;
                }
            }
        }

        // If we didn't find enough in the first batch, scan progressively wider
        if (results.size() < limit) {
            Long totalSize = redisTemplate.opsForZSet().size(GLOBAL_KEY);
            if (totalSize != null && totalSize > scanBatchSize) {
                long remaining = Math.min(totalSize, scanBatchSize * 4L);
                Set<String> extended = redisTemplate.opsForZSet().reverseRange(GLOBAL_KEY, scanBatchSize, remaining - 1);
                if (extended != null) {
                    for (String entry : extended) {
                        if (entry.toLowerCase().contains(lowerQuery)) {
                            results.add(stripMetadata(entry));
                            if (results.size() >= limit) break;
                        }
                    }
                }
            }
        }

        return results;
    }

    @Override
    public List<String> getRecentMemories(String agentId, int limit) {
        String key = agentId != null ? AGENT_KEY_PREFIX + agentId : GLOBAL_KEY;

        Set<String> memories = redisTemplate.opsForZSet().reverseRange(key, 0, limit - 1);
        if (memories == null) return Collections.emptyList();

        return memories.stream()
                .map(this::stripMetadata)
                .collect(Collectors.toList());
    }

    @Override
    public void clear() {
        // Delete global key
        redisTemplate.delete(GLOBAL_KEY);

        // Delete all agent keys
        Set<String> agentKeys = redisTemplate.keys(AGENT_KEY_PREFIX + "*");
        if (agentKeys != null && !agentKeys.isEmpty()) {
            redisTemplate.delete(agentKeys);
        }

        logger.info("Redis memory cleared");
    }

    @Override
    public void clearForAgent(String agentId) {
        if (agentId != null) {
            redisTemplate.delete(AGENT_KEY_PREFIX + agentId);

            // Also remove agent entries from global set
            Set<String> agentMemories = redisTemplate.opsForZSet()
                    .range(AGENT_KEY_PREFIX + agentId, 0, -1);
            if (agentMemories != null) {
                for (String entry : agentMemories) {
                    redisTemplate.opsForZSet().remove(GLOBAL_KEY, entry);
                }
            }

            logger.info("Redis memory cleared for agent {}", agentId);
        }
    }

    @Override
    public int size() {
        Long count = redisTemplate.opsForZSet().size(GLOBAL_KEY);
        return count != null ? count.intValue() : 0;
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    private String stripMetadata(String entry) {
        int metaIdx = entry.lastIndexOf(" [metadata:");
        return metaIdx > 0 ? entry.substring(0, metaIdx) : entry;
    }
}
