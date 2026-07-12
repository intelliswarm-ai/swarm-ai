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
        //
        // SECURITY: agentPrefix is tenant-derived and TenantContext accepts arbitrary tenant IDs,
        // so it must not reach Redis as a raw glob. A tenant id of `tenant-*` would otherwise
        // match every other tenant's keys and return their memories. We escape the glob
        // metacharacters, AND re-check the literal prefix on each returned key below — tenant
        // isolation should not hinge on getting Redis's glob semantics exactly right.
        List<Match> matches = new ArrayList<>();
        List<String> keywords = MemoryKeywords.tokenize(query);
        String literalKeyPrefix = AGENT_KEY_PREFIX + agentPrefix;
        for (String key : scanKeys(AGENT_KEY_PREFIX + escapeGlob(agentPrefix) + "*")) {
            if (!key.startsWith(literalKeyPrefix)) {
                logger.warn("Discarding key '{}' that matched the scan pattern but not the literal "
                        + "tenant prefix '{}' — possible glob-injection attempt", key, literalKeyPrefix);
                continue;
            }
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
     * Escapes Redis glob metacharacters so a caller-supplied string matches literally.
     * Redis patterns treat {@code * ? [ ] \} specially; a tenant id containing any of them would
     * otherwise widen the match beyond that tenant.
     */
    static String escapeGlob(String literal) {
        StringBuilder escaped = new StringBuilder(literal.length() + 8);
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            if (c == '*' || c == '?' || c == '[' || c == ']' || c == '\\') {
                escaped.append('\\');
            }
            escaped.append(c);
        }
        return escaped.toString();
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

    // One-character type tags that prefix every stored member. They make decode() depend on a
    // byte WE control rather than on the shape of the content — otherwise a caller who saves the
    // literal string {"c":"spoofed"} (with no metadata, so it would be stored verbatim) would have
    // it decode back to "spoofed" instead of round-tripping. Both are C0 control characters that
    // never appear in legacy entries (which stored content or content + " [metadata:...]").
    private static final char RAW_TAG = '\u0000';
    private static final char ENVELOPE_TAG = '\u0001';

    /**
     * Encodes content + metadata for storage. A type tag prefixes the member so decoding is
     * unambiguous: raw content is stored verbatim after {@link #RAW_TAG} (never guessed from its
     * shape), and content with metadata is a JSON envelope after {@link #ENVELOPE_TAG} — which also
     * keeps two identical contents with different metadata as distinct ZSet members.
     */
    String encode(String content, Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return RAW_TAG + content;
        }
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("c", content);
            node.set("m", objectMapper.valueToTree(metadata));
            return ENVELOPE_TAG + objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            logger.warn("Failed to encode memory metadata, storing content only: {}", e.getMessage());
            return RAW_TAG + content;
        }
    }

    /** Decodes a stored entry back to its content. Unambiguous via the leading type tag. */
    String decode(String entry) {
        if (entry.isEmpty()) {
            return entry;
        }
        char tag = entry.charAt(0);
        if (tag == RAW_TAG) {
            return entry.substring(1);
        }
        if (tag == ENVELOPE_TAG) {
            try {
                JsonNode content = objectMapper.readTree(entry.substring(1)).get("c");
                if (content != null && content.isTextual()) {
                    return content.asText();
                }
            } catch (JsonProcessingException e) {
                // corrupt envelope — fall through and return the payload as-is
            }
            return entry.substring(1);
        }
        // Legacy (pre-tag) format: content, or content + " [metadata:...]"
        int metaIdx = entry.lastIndexOf(" [metadata:");
        return metaIdx > 0 ? entry.substring(0, metaIdx) : entry;
    }
}
