package ai.intelliswarm.swarmai.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Tenant prefixes reach Redis as a SCAN {@code MATCH} pattern, and {@code TenantContext} accepts
 * arbitrary tenant IDs. Redis treats {@code * ? [ ] \} as glob metacharacters, so an unescaped
 * tenant id of {@code tenant-*} would match every other tenant's
 * {@code swarmai:memory:agent:} keys and return their memories.
 */
@DisplayName("RedisMemory — glob escaping in tenant prefixes")
class RedisMemoryGlobEscapeTest {

    @Test
    @DisplayName("escapes every Redis glob metacharacter")
    void escapesGlobMetacharacters() {
        assertEquals("tenant\\*", RedisMemory.escapeGlob("tenant*"));
        assertEquals("tenant\\?", RedisMemory.escapeGlob("tenant?"));
        assertEquals("tenant\\[a\\]", RedisMemory.escapeGlob("tenant[a]"));
        assertEquals("tenant\\\\", RedisMemory.escapeGlob("tenant\\"));
    }

    @Test
    @DisplayName("a wildcard tenant id cannot widen the scan beyond itself")
    void wildcardTenantIdCannotEscapeItsScope() {
        String hostile = "tenant-*";

        String pattern = "swarmai:memory:agent:" + RedisMemory.escapeGlob(hostile) + "*";

        // The trailing '*' is ours; the one inside the tenant id must be escaped, so the pattern
        // matches keys literally beginning "tenant-*" rather than every "tenant-..." key.
        assertEquals("swarmai:memory:agent:tenant-\\**", pattern);
        assertFalse(pattern.contains("tenant-**"),
                "TENANT LEAK: the tenant's own '*' survived unescaped and now globs other tenants");
    }

    @Test
    @DisplayName("ordinary tenant ids pass through unchanged")
    void ordinaryPrefixesUnchanged() {
        assertEquals("tenant-a::", RedisMemory.escapeGlob("tenant-a::"));
        assertEquals("acme_prod::agent-1", RedisMemory.escapeGlob("acme_prod::agent-1"));
    }

    @Nested
    @DisplayName("encode/decode round-trip — no envelope spoofing")
    class EnvelopeRoundTrip {

        private final RedisMemory memory = new RedisMemory(mock(StringRedisTemplate.class));

        @Test
        @DisplayName("content that LOOKS like an envelope round-trips verbatim")
        void jsonLikeContentIsNotSpoofed() {
            // The spoof: content stored with no metadata used to be written raw, and decode()
            // treated any '{'-prefixed string as an envelope — so this decoded to "spoofed".
            String hostile = "{\"c\":\"spoofed\",\"m\":0}";

            String stored = memory.encode(hostile, null);

            assertEquals(hostile, memory.decode(stored),
                    "content that mimics the envelope shape must round-trip unchanged, not be "
                            + "reinterpreted as an envelope");
        }

        @Test
        @DisplayName("ordinary content round-trips")
        void plainContentRoundTrips() {
            String content = "Task: analyze revenue\nResult: up 12%";
            assertEquals(content, memory.decode(memory.encode(content, null)));
        }

        @Test
        @DisplayName("content WITH metadata round-trips its content")
        void envelopeContentRoundTrips() {
            String content = "some finding";
            String stored = memory.encode(content, Map.of("taskId", "t1"));
            assertEquals(content, memory.decode(stored));
        }

        @Test
        @DisplayName("legacy pre-tag entries still decode")
        void legacyEntriesStillDecode() {
            assertEquals("old content", memory.decode("old content"));
            assertEquals("old content", memory.decode("old content [metadata:{k=v}]"));
        }
    }
}
