package ai.intelliswarm.swarmai.memory.fts;

import ai.intelliswarm.swarmai.api.PublicApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * SQLite FTS5 full-text index over agent conversation transcripts. Lets a
 * long-running agent recall what was said in prior sessions ("what did we
 * decide about ACME's WACC last Tuesday?") without re-feeding entire
 * histories into the LLM context.
 *
 * <p>Backed by SQLite's FTS5 virtual table — keyword search with snippet
 * extraction, BM25 ranking, and prefix matching. Distinct from the
 * framework's vector RAG: this is fast keyword recall over <em>operator-level
 * transcripts</em>, not semantic search over indexed knowledge documents.
 *
 * <p>Schema:
 * <pre>
 * CREATE VIRTUAL TABLE session_turns USING fts5(
 *     session_id UNINDEXED,
 *     agent_id   UNINDEXED,
 *     role       UNINDEXED,
 *     content,
 *     timestamp  UNINDEXED
 * );
 * </pre>
 *
 * <p>The index file is a single SQLite database. Thread-safe — internal
 * connection-per-call discipline. For very high write rates, batch via
 * {@link #addTurns(String, String, List)}.
 *
 * <p>Optional dep: {@code org.xerial:sqlite-jdbc}. Construction will throw
 * if not on the classpath.
 */
@PublicApi(since = "1.0.24")
public final class SessionIndex implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SessionIndex.class);

    private final Path dbPath;
    private final String jdbcUrl;

    public SessionIndex(Path dbPath) {
        this.dbPath = Objects.requireNonNull(dbPath, "dbPath must not be null").toAbsolutePath().normalize();
        this.jdbcUrl = "jdbc:sqlite:" + this.dbPath;
        ensureDriverPresent();
        ensureParentExists();
        initSchema();
    }

    public Path dbPath() {
        return dbPath;
    }

    /** Index one turn. {@code session} groups turns; {@code role} is user/assistant/tool/system. */
    public void addTurn(String sessionId, String agentId, String role, String content, Instant timestamp) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(content, "content must not be null");
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO session_turns (session_id, agent_id, role, content, timestamp) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, sessionId);
            ps.setString(2, agentId == null ? "" : agentId);
            ps.setString(3, role);
            ps.setString(4, content);
            ps.setString(5, (timestamp == null ? Instant.now() : timestamp).toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new SessionIndexException("Failed to index turn for session=" + sessionId, e);
        }
    }

    /** Batch-index multiple turns under a single session/agent — much faster than calling addTurn in a loop. */
    public void addTurns(String sessionId, String agentId, List<TurnRecord> turns) {
        if (turns == null || turns.isEmpty()) return;
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        String agent = agentId == null ? "" : agentId;
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO session_turns (session_id, agent_id, role, content, timestamp) VALUES (?, ?, ?, ?, ?)")) {
                for (TurnRecord t : turns) {
                    ps.setString(1, sessionId);
                    ps.setString(2, agent);
                    ps.setString(3, t.role());
                    ps.setString(4, t.content());
                    ps.setString(5, (t.timestamp() == null ? Instant.now() : t.timestamp()).toString());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
        } catch (SQLException e) {
            throw new SessionIndexException("Failed batch-indexing " + turns.size() + " turns", e);
        }
    }

    /**
     * Search by FTS5 query syntax. {@code agentId} narrows to a single agent
     * (pass null for all). Results are BM25-ranked.
     *
     * <p>Query syntax: {@code "ACME WACC"} (AND); {@code "WACC OR cost of capital"};
     * {@code "discount*"} (prefix); {@code "ACME NEAR/5 valuation"} (proximity).
     */
    public List<SearchHit> search(String query, String agentId, int limit) {
        Objects.requireNonNull(query, "query must not be null");
        if (limit <= 0) limit = 10;
        String sql = "SELECT session_id, agent_id, role, content, timestamp, " +
                "snippet(session_turns, 3, '<b>', '</b>', '…', 16) AS snippet, " +
                "bm25(session_turns) AS rank " +
                "FROM session_turns WHERE session_turns MATCH ? " +
                (agentId != null ? "AND agent_id = ? " : "") +
                "ORDER BY rank LIMIT ?";
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, query);
            if (agentId != null) ps.setString(i++, agentId);
            ps.setInt(i, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<SearchHit> hits = new ArrayList<>();
                while (rs.next()) {
                    hits.add(new SearchHit(
                            rs.getString("session_id"),
                            rs.getString("agent_id"),
                            rs.getString("role"),
                            rs.getString("content"),
                            rs.getString("snippet"),
                            rs.getString("timestamp"),
                            rs.getDouble("rank")));
                }
                return List.copyOf(hits);
            }
        } catch (SQLException e) {
            throw new SessionIndexException("FTS5 query failed: " + query, e);
        }
    }

    /** Total indexed turns. */
    public long totalTurns() {
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) AS c FROM session_turns")) {
            return rs.next() ? rs.getLong("c") : 0L;
        } catch (SQLException e) {
            throw new SessionIndexException("count failed", e);
        }
    }

    /** Delete a single session's turns — useful when user requests deletion. */
    public int forgetSession(String sessionId) {
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM session_turns WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new SessionIndexException("delete session " + sessionId + " failed", e);
        }
    }

    @Override
    public void close() {
        // Connection-per-operation discipline — nothing to close at the index level.
    }

    private void ensureDriverPresent() {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SessionIndexException(
                    "SQLite JDBC driver not on classpath. Add: org.xerial:sqlite-jdbc", e);
        }
    }

    private void ensureParentExists() {
        try {
            Files.createDirectories(dbPath.getParent());
        } catch (Exception e) {
            log.warn("Failed to create parent directory for SessionIndex at {}: {}", dbPath, e.getMessage());
        }
    }

    private void initSchema() {
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement s = conn.createStatement()) {
            s.execute("CREATE VIRTUAL TABLE IF NOT EXISTS session_turns USING fts5("
                    + "session_id UNINDEXED, agent_id UNINDEXED, role UNINDEXED, content, timestamp UNINDEXED)");
        } catch (SQLException e) {
            throw new SessionIndexException("schema init failed", e);
        }
    }

    /** One turn ready for batch insertion. */
    @PublicApi(since = "1.0.24")
    public record TurnRecord(String role, String content, Instant timestamp) {
        public TurnRecord {
            Objects.requireNonNull(role, "role must not be null");
            Objects.requireNonNull(content, "content must not be null");
        }
    }

    /**
     * One FTS5 query match. {@code snippet} carries the matched span wrapped
     * in {@code <b>…</b>} for UI display; {@code rank} is the BM25 score
     * (lower = better match — SQLite convention).
     */
    @PublicApi(since = "1.0.24")
    public record SearchHit(
            String sessionId,
            String agentId,
            String role,
            String content,
            String snippet,
            String timestamp,
            double rank) {}

    @PublicApi(since = "1.0.24")
    public static final class SessionIndexException extends RuntimeException {
        public SessionIndexException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
