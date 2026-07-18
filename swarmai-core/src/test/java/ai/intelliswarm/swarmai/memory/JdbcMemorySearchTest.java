package ai.intelliswarm.swarmai.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ranking behaviour of {@link JdbcMemory#search} against a real database (SQLite in-memory).
 *
 * <p>The regression this pins: an earlier version fetched the newest {@code limit * 5} rows and
 * scored them in Java. That truncates by recency BEFORE ranking, so a strongly-matching older row
 * can never be seen. Scoring now happens in SQL, so the ranking sees every matching row.
 */
@DisplayName("JdbcMemory — keyword ranking")
class JdbcMemorySearchTest {

    private JdbcMemory memory;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
                "jdbc:sqlite::memory:", true);
        dataSource.setDriverClassName("org.sqlite.JDBC");
        jdbcTemplate = new JdbcTemplate(dataSource);

        // SQLite doesn't speak the identity-column DDL, so create the table explicitly and let
        // JdbcMemory operate on it (autoCreateTable=false). The search SQL under test is portable.
        jdbcTemplate.execute("CREATE TABLE swarmai_memory ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "agent_id VARCHAR(255), "
                + "content TEXT NOT NULL, "
                + "metadata TEXT, "
                + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");

        memory = new JdbcMemory(jdbcTemplate, false);
    }

    /** Inserts with an explicit created_at so recency ordering is deterministic, not clock-raced. */
    private void saveAt(String agentId, String content, String createdAt) {
        jdbcTemplate.update(
                "INSERT INTO swarmai_memory (agent_id, content, metadata, created_at) VALUES (?, ?, ?, ?)",
                agentId, content, null, createdAt);
    }

    @Test
    @DisplayName("a strongly-matching OLD row outranks weakly-matching NEW rows")
    void strongOldMatchBeatsWeakRecentMatches() {
        // Codex's scenario: the one row that matches all three keywords is the OLDEST. Under the
        // old "take the newest N, then score" approach it fell outside the candidate window and
        // could never be returned.
        saveAt("agent-1", "database migration rollback plan tested", "2026-01-01 00:00:00");
        for (int i = 1; i <= 5; i++) {
            saveAt("agent-1", "database note number " + i, "2026-02-0" + i + " 00:00:00");
        }

        List<String> results = memory.search("database migration rollback", 1);

        assertEquals(1, results.size());
        assertEquals("database migration rollback plan tested", results.get(0),
                "the row matching the most keywords must win, even though five newer rows "
                        + "also match a keyword");
    }

    @Test
    @DisplayName("ranks by keyword count, then recency")
    void ranksByKeywordCountThenRecency() {
        saveAt("agent-1", "database only", "2026-03-01 00:00:00");
        saveAt("agent-1", "database migration", "2026-01-01 00:00:00");
        saveAt("agent-1", "database migration rollback", "2026-02-01 00:00:00");

        List<String> results = memory.search("database migration rollback", 3);

        assertEquals(List.of("database migration rollback", "database migration", "database only"),
                results);
    }

    @Test
    @DisplayName("rows matching no keyword are excluded")
    void nonMatchingRowsExcluded() {
        saveAt("agent-1", "database migration rollback", "2026-01-01 00:00:00");
        saveAt("agent-1", "completely unrelated content", "2026-02-01 00:00:00");

        List<String> results = memory.search("database migration rollback", 10);

        assertEquals(1, results.size());
        assertEquals("database migration rollback", results.get(0));
    }

    @Test
    @DisplayName("LIKE wildcards in the query are matched literally, not as wildcards")
    void likeWildcardsEscaped() {
        memory.save("agent-1", "revenue grew 12 percent", Map.of());

        // '%%%' would match everything if it were interpolated as a LIKE wildcard.
        assertTrue(memory.search("%%%", 10).isEmpty(),
                "wildcards in the query must not match every row");
    }

    @Test
    @DisplayName("agent-prefix search stays inside the prefix")
    void agentPrefixScoped() {
        saveAt("tenant-a::agent-1", "database migration rollback", "2026-01-01 00:00:00");
        saveAt("tenant-b::agent-1", "database migration rollback", "2026-02-01 00:00:00");

        List<String> results = memory.searchByAgentPrefix(
                "database migration rollback", "tenant-a::", 10);

        assertEquals(1, results.size(), "must not return the other tenant's row");
    }
}
