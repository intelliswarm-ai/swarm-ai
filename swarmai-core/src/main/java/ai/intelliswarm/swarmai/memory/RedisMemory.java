package ai.intelliswarm.swarmai.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Redis-backed implementation of the Memory interface.
 * Uses Redis Sorted Sets for time-ordered memory storage and
 * keyword-scored matching for search (see {@link MemoryKeywords}).
 *
 * Key structure:
 *   swarmai:memory:global        — sorted set of all memories (score = timestamp)
 *   swarmai:memory:agent:{id}    — sorted set of agent-specific memories
 *
 * <p>Entries with metadata are stored as a JSON envelope {@code {"c":content,"m":metadata}}
 * so metadata round-trips losslessly; plain contents are stored as-is. Entries written
 * by older versions with the legacy {@code " [metadata:...]"} suffix are still read.
 *
 * <p>Each sorted set is capped at {@code maxEntries}: on save, the oldest members
 * beyond the cap are trimmed with ZREMRANGEBYRANK, so memory cannot grow unbounded.
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
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** Default per-key cap; keeps the global set to a few MB of task summaries. */
    public static final int DEFAULT_MAX_ENTRIES = 10_000;

    private final StringRedisTemplate redisTemplate;
    private final int maxEntries;

    public RedisMemory(StringRedisTemplate redisTemplate) {
        this(redisTemplate, DEFAULT_MAX_ENTRIES);
    }

    public RedisMemory(StringRedisTemplate redisTemplate, int maxEntries) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "RedisTemplate cannot be null");
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive, got " + maxEntries);
        }
        this.maxEntries = maxEntries;
        logger.info("RedisMemory initialized (maxEntries={})", maxEntries);
    }

    @Override
    public void save(String agentId, String content, Map<String, Object> metadata) {
        double score = Instant.now().toEpochMilli();
        String entry = encode(content, metadata);

        redisTemplate.opsForZSet().add(GLOBAL_KEY, entry, score);
        trimToCap(GLOBAL_KEY);

        if (agentId != null) {
            String agentKey = AGENT_KEY_PREFIX + agentId;
            redisTemplate.opsForZSet().add(agentKey, entry, score);
            trimToCap(agentKey);
        }

        logger.debug("Saved memory for agent {}: {} chars", agentId, content.length());
    }

    /** Removes the oldest members beyond the cap (ranks 0 .. -(max+1)). */
    private void trimToCap(String key) {
        redisTemplate.opsForZSet().removeRange(key, 0, -((long) maxEntries + 1));
    }

    @Override
    public List<String> search(String query, int limit) {
        return searchKey(GLOBAL_KEY, query, limit);
    }

    @Override
    public List<String> searchByAgentPrefix(String query, String agentPrefix, int limit) {
        if (agentPrefix == null || agentPrefix.isEmpty()) {
            return search(query, limit);
        }
        if (query == null || query.isBlank() || limit <= 0) {
            return Collections.emptyList();
        }
        // Collect the scoped agents' entries newest-first, then score like search().
        // Agent keys are discovered with SCAN (non-blocking), never KEYS.
        List<Match> matches = new ArrayList<>();
        List<String> keywords = MemoryKeywords.tokenize(query);
        for (String key : scanKeys(AGENT_KEY_PREFIX + agentPrefix + "*")) {
            collectMatches(key, query, keywords, limit, matches);
        }
        return topContents(matches, limit);
    }

    private List<String> searchKey(String key, String query, int limit) {
        if (query == null || query.isBlank() || limit <= 0) {
            return Collections.emptyList();
        }
        List<String> keywords = MemoryKeywords.tokenize(query);
        List<Match> matches = new ArrayList<>();
        collectMatches(key, query, keywords, limit, matches);
        return topContents(matches, limit);
    }

    /**
     * Scans the newest entries of a sorted set in widening batches, scoring each
     * against the query keywords. Stops early once enough fully-matching entries
     * are found. Never loads more than {@code maxEntries} (the key is capped anyway).
     */
    private void collectMatches(String key, String query, List<String> keywords,
                                int limit, List<Match> matches) {
        String rawQuery = query.toLowerCase();
        int batchSize = Math.max(limit * 4, 100);
        long offset = 0;
        int fullMatches = 0;

        while (true) {
            Set<String> batch = redisTemplate.opsForZSet()
                    .reverseRange(key, offset, offset + batchSize - 1);
            if (batch == null || batch.isEmpty()) {
                return;
            }
            for (String rawEntry : batch) {
                offset++;
                String content = decode(rawEntry);
                int score = keywords.isEmpty()
                        ? (content.toLowerCase().contains(rawQuery) ? 1 : 0)
                        : MemoryKeywords.score(content, keywords);
                if (score > 0) {
                    matches.add(new Match(content, score, offset));
                    if (score >= Math.max(1, keywords.size()) && ++fullMatches >= limit) {
                        return;
                    }
                }
            }
            if (batch.size() < batchSize) {
                return; // exhausted the set
            }
        }
    }

    private static List<String> topContents(List<Match> matches, int limit) {
        return matches.stream()
                .sorted(Comparator.comparingInt(Match::score).reversed()
                        .thenComparingLong(Match::recency))
                .limit(limit)
                .map(Match::content)
                .toList();
    }

    private record Match(String content, int score, long recency) {
    }

    @Override
    public List<String> getRecentMemories(String agentId, int limit) {
        String key = agentId != null ? AGENT_KEY_PREFIX + agentId : GLOBAL_KEY;

        Set<String> memories = redisTemplate.opsForZSet().reverseRange(key, 0, limit - 1);
        if (memories == null) return Collections.emptyList();

        return memories.stream()
                .map(this::decode)
                .toList();
    }

    @Override
    public void clear() {
        redisTemplate.delete(GLOBAL_KEY);

        Set<String> agentKeys = scanKeys(AGENT_KEY_PREFIX + "*");
        if (!agentKeys.isEmpty()) {
            redisTemplate.delete(agentKeys);
        }

        logger.info("Redis memory cleared");
    }

    @Override
    public void clearForAgent(String agentId) {
        if (agentId == null) {
            return;
        }
        String agentKey = AGENT_KEY_PREFIX + agentId;

        // Remove this agent's entries from the global set in one round-trip,
        // then drop the agent's own key.
        Set<String> agentMemories = redisTemplate.opsForZSet().range(agentKey, 0, -1);
        if (agentMemories != null && !agentMemories.isEmpty()) {
            redisTemplate.opsForZSet().remove(GLOBAL_KEY, agentMemories.toArray());
        }
        redisTemplate.delete(agentKey);

        logger.info("Redis memory cleared for agent {}", agentId);
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

    /**
     * Discovers keys matching a pattern with SCAN, which iterates incrementally
     * and does not block Redis the way KEYS does on large keyspaces.
     */
    private Set<String> scanKeys(String pattern) {
        Set<String> keys = new HashSet<>();
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(500).build();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
        }
        return keys;
    }

    /**
     * Encodes content + metadata as a JSON envelope so metadata is lossless and
     * two identical contents with different metadata remain distinct ZSet members.
     * Content without metadata is stored raw to stay readable in redis-cli.
     */
    private String encode(String content, Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return content;
        }
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("c", content);
            node.set("m", objectMapper.valueToTree(metadata));
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            logger.warn("Failed to encode memory metadata, storing content only: {}", e.getMessage());
            return content;
        }
    }

    /** Decodes a stored entry back to its content, handling all historical formats. */
    private String decode(String entry) {
        if (entry.startsWith("{")) {
            try {
                JsonNode node = objectMapper.readTree(entry);
                JsonNode content = node.get("c");
                if (content != null && content.isTextual()) {
                    return content.asText();
                }
            } catch (JsonProcessingException e) {
                // not an envelope — fall through to legacy handling
            }
        }
        // Legacy format: content + " [metadata:...]"
        int metaIdx = entry.lastIndexOf(" [metadata:");
        return metaIdx > 0 ? entry.substring(0, metaIdx) : entry;
    }
}
