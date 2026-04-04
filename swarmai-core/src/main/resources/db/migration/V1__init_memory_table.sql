-- SwarmAI Memory Table
-- Used by JdbcMemory for persistent agent memory storage

CREATE TABLE IF NOT EXISTS swarmai_memory (
    id BIGSERIAL PRIMARY KEY,
    agent_id VARCHAR(255),
    content TEXT NOT NULL,
    metadata JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_swarmai_memory_agent ON swarmai_memory(agent_id);
CREATE INDEX IF NOT EXISTS idx_swarmai_memory_created ON swarmai_memory(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_swarmai_memory_content_gin ON swarmai_memory USING gin(to_tsvector('english', content));
