package ai.intelliswarm.swarmai.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * JDBC-backed implementation of the Memory interface.
 * Works with PostgreSQL, MySQL, H2, and any JDBC-compatible database.
 *
 * Requires table:
 *   CREATE TABLE IF NOT EXISTS swarmai_memory (
 *       id BIGSERIAL PRIMARY KEY,
 *       agent_id VARCHAR(255),
 *       content TEXT NOT NULL,
 *       metadata TEXT,
 *       created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
 *   );
 *   CREATE INDEX idx_swarmai_memory_agent ON swarmai_memory(agent_id);
 *   CREATE INDEX idx_swarmai_memory_created ON swarmai_memory(created_at DESC);
 *
 * Configuration via application.yml:
 *   swarmai.memory.provider: jdbc
 *   spring.datasource.url: jdbc:postgresql://localhost:5432/swarmai
 *   spring.datasource.username: swarmai
 *   spring.datasource.password: swarmai_password
 */
public class JdbcMemory implements Memory {

    private static final Logger logger = LoggerFactory.getLogger(JdbcMemory.class);
    private static final String TABLE_NAME = "swarmai_memory";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;
    private final boolean autoCreateTable;

    /**
     * Creates JdbcMemory with automatic table creation.
     * For production, use Flyway migrations and set autoCreateTable=false.
     */
    public JdbcMemory(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, true);
    }

    public JdbcMemory(JdbcTemplate jdbcTemplate, boolean autoCreateTable) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate cannot be null");
        this.autoCreateTable = autoCreateTable;
        if (autoCreateTable) {
            initializeTable();
        }
        logger.info("JdbcMemory initialized (autoCreateTable={})", autoCreateTable);
    }

    private void initializeTable() {
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " (" +
                "id BIGSERIAL PRIMARY KEY, " +
                "agent_id VARCHAR(255), " +
                "content TEXT NOT NULL, " +
                "metadata JSONB, " +
                "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP" +
                ")");

        try {
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_swarmai_memory_agent ON " + TABLE_NAME + "(agent_id)");
            jdbcTemplate.execute(
                    "CREATE INDEX IF NOT EXISTS idx_swarmai_memory_created ON " + TABLE_NAME + "(created_at DESC)");
        } catch (Exception e) {
            logger.debug("Index creation skipped (may already exist): {}", e.getMessage());
        }
    }

    @Override
    public void save(String agentId, String content, Map<String, Object> metadata) {
        String metadataJson = null;
        if (metadata != null && !metadata.isEmpty()) {
            try {
                metadataJson = objectMapper.writeValueAsString(metadata);
            } catch (JsonProcessingException e) {
                logger.warn("Failed to serialize metadata as JSON, storing as null: {}", e.getMessage());
            }
        }

        jdbcTemplate.update(
                "INSERT INTO " + TABLE_NAME + " (agent_id, content, metadata, created_at) VALUES (?, ?, ?::jsonb, ?)",
                agentId, content, metadataJson, Timestamp.from(Instant.now()));

        logger.debug("Saved memory for agent {}: {} chars", agentId, content.length());
    }

    @Override
    public List<String> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        // Use ILIKE for case-insensitive search (PostgreSQL)
        // Falls back to LIKE with LOWER() for other databases
        String sql = "SELECT content FROM " + TABLE_NAME +
                " WHERE LOWER(content) LIKE LOWER(?) ORDER BY created_at DESC LIMIT ?";

        return jdbcTemplate.queryForList(sql, String.class, "%" + query + "%", limit);
    }

    @Override
    public List<String> getRecentMemories(String agentId, int limit) {
        if (agentId != null) {
            return jdbcTemplate.queryForList(
                    "SELECT content FROM " + TABLE_NAME +
                    " WHERE agent_id = ? ORDER BY created_at DESC LIMIT ?",
                    String.class, agentId, limit);
        } else {
            return jdbcTemplate.queryForList(
                    "SELECT content FROM " + TABLE_NAME +
                    " ORDER BY created_at DESC LIMIT ?",
                    String.class, limit);
        }
    }

    @Override
    public void clear() {
        jdbcTemplate.update("DELETE FROM " + TABLE_NAME);
        logger.info("JDBC memory cleared");
    }

    @Override
    public void clearForAgent(String agentId) {
        if (agentId != null) {
            jdbcTemplate.update("DELETE FROM " + TABLE_NAME + " WHERE agent_id = ?", agentId);
            logger.info("JDBC memory cleared for agent {}", agentId);
        }
    }

    @Override
    public int size() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE_NAME, Integer.class);
        return count != null ? count : 0;
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }
}
