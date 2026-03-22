package ai.intelliswarm.swarmai.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Auto-configuration for Memory provider selection.
 *
 * Configure via application.yml:
 *   swarmai.memory.provider: in-memory    (default)
 *   swarmai.memory.provider: redis        (requires spring-data-redis)
 *   swarmai.memory.provider: jdbc         (requires spring-jdbc + datasource)
 */
@Configuration
public class MemoryAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(MemoryAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(Memory.class)
    @ConditionalOnProperty(name = "swarmai.memory.provider", havingValue = "redis")
    @ConditionalOnBean(StringRedisTemplate.class)
    public Memory redisMemory(StringRedisTemplate redisTemplate) {
        logger.info("Using Redis-backed memory");
        return new RedisMemory(redisTemplate);
    }

    @Bean
    @ConditionalOnMissingBean(Memory.class)
    @ConditionalOnProperty(name = "swarmai.memory.provider", havingValue = "jdbc")
    @ConditionalOnBean(JdbcTemplate.class)
    public Memory jdbcMemory(JdbcTemplate jdbcTemplate) {
        logger.info("Using JDBC-backed memory (PostgreSQL/MySQL/H2)");
        return new JdbcMemory(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean(Memory.class)
    public Memory inMemoryMemory() {
        logger.info("Using in-memory memory (data lost on restart)");
        return new InMemoryMemory();
    }
}
