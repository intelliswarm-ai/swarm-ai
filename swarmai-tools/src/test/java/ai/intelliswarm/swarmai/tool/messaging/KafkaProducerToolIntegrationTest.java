package ai.intelliswarm.swarmai.tool.messaging;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live integration test for KafkaProducerTool.
 *
 * Required env:
 *   - KAFKA_BOOTSTRAP_SERVERS : broker list (e.g. "localhost:9092")
 *   - KAFKA_TEST_TOPIC        : pre-created topic the test may write to and consume from
 *
 * Tip for local dev: run `docker run --rm -p 9092:9092 apache/kafka:3.7.1` and
 * create the topic with `kafka-topics.sh --create --topic swarmai-it --partitions 1 --replication-factor 1`.
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "KAFKA_BOOTSTRAP_SERVERS", matches = ".+")
@EnabledIfEnvironmentVariable(named = "KAFKA_TEST_TOPIC", matches = ".+")
@DisplayName("KafkaProducerTool Integration Tests")
class KafkaProducerToolIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(KafkaProducerToolIntegrationTest.class);
    private static final String OUTPUT_DIR = "target/integration-test-outputs";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private KafkaProducerTool tool;
    private String bootstrap;
    private String topic;

    @BeforeEach
    void setUp() {
        tool = new KafkaProducerTool();
        bootstrap = System.getenv("KAFKA_BOOTSTRAP_SERVERS");
        topic = System.getenv("KAFKA_TEST_TOPIC");
        try {
            Path p = Paths.get(OUTPUT_DIR);
            if (!Files.exists(p)) Files.createDirectories(p);
        } catch (IOException e) {
            logger.warn("Could not create output dir: {}", e.getMessage());
        }
    }

    @Test
    @DisplayName("publish + consume: message round-trips through real Kafka broker")
    void publishAndConsume() {
        String key = "it-" + UUID.randomUUID();
        String value = "{\"from\":\"SwarmAI KafkaProducerTool\",\"at\":\"" + LocalDateTime.now() + "\"}";

        Object out = tool.execute(Map.of(
            "topic", topic,
            "key", key,
            "value", value,
            "bootstrap_servers", bootstrap,
            "headers", Map.of("x-test", "swarmai-it")));

        String s = out.toString();
        assertFalse(s.startsWith("Error"), "Publish failed: " + s);
        assertTrue(s.contains("Published to **" + topic + "**"), s);
        write("publish", s);

        // Consume back to prove the broker actually received it.
        Properties cprops = new Properties();
        cprops.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        cprops.put(ConsumerConfig.GROUP_ID_CONFIG, "swarmai-it-" + UUID.randomUUID());
        cprops.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        cprops.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        cprops.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(cprops)) {
            consumer.subscribe(List.of(topic));

            // Poll up to 15s for our specific key to show up — the topic may already have other
            // traffic so scan every poll's records.
            long deadline = System.currentTimeMillis() + 15_000;
            boolean found = false;
            while (!found && System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
                for (ConsumerRecord<String, String> r : records) {
                    if (key.equals(r.key())) {
                        found = true;
                        assertEquals(value, r.value(), "Round-trip value mismatch");
                        assertNotNull(r.headers().lastHeader("x-test"), "Expected x-test header");
                        assertEquals("swarmai-it",
                            new String(r.headers().lastHeader("x-test").value(), StandardCharsets.UTF_8));
                        break;
                    }
                }
            }
            assertTrue(found, "Did not see our key '" + key + "' on topic " + topic + " within 15s");
        }
    }

    @Test
    @DisplayName("smokeTest is healthy when bootstrap env is set")
    void smokeOk() {
        assertNull(tool.smokeTest(), "Expected healthy (null)");
    }

    private void write(String label, String content) {
        String filename = OUTPUT_DIR + "/kafka_" + label + "_" + LocalDateTime.now().format(TS) + ".md";
        try {
            Files.writeString(Paths.get(filename), content);
            logger.info("Wrote sample to {}", filename);
        } catch (IOException e) {
            logger.warn("Failed to write sample {}: {}", filename, e.getMessage());
        }
    }
}
