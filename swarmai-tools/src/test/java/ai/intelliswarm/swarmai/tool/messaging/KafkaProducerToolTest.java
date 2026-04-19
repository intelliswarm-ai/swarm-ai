package ai.intelliswarm.swarmai.tool.messaging;

import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("KafkaProducerTool Unit Tests")
class KafkaProducerToolTest {

    private MockProducer<String, String> mock;
    private AtomicReference<Properties> capturedProps;
    private KafkaProducerTool tool;

    @BeforeEach
    void setUp() {
        mock = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        capturedProps = new AtomicReference<>();
        tool = new KafkaProducerTool(props -> {
            capturedProps.set(props);
            return (Producer<String, String>) mock;
        });
        ReflectionTestUtils.setField(tool, "defaultBootstrapServers", "broker-1:9092");
    }

    // ===== Interface =====

    @Test void functionName() { assertEquals("kafka_produce", tool.getFunctionName()); }

    @Test void writePermission() {
        assertEquals(ai.intelliswarm.swarmai.tool.base.PermissionLevel.WORKSPACE_WRITE,
            tool.getPermissionLevel());
    }

    @Test
    void requiredFields() {
        Map<String, Object> schema = tool.getParameterSchema();
        assertArrayEquals(new String[]{"topic", "value"}, (String[]) schema.get("required"));
    }

    // ===== Happy path =====

    @Test
    @DisplayName("publish: sends record with key + value, returns metadata")
    void publishHappyPath() {
        Object out = tool.execute(Map.of(
            "topic", "orders.v1",
            "key", "order-42",
            "value", "{\"id\":42,\"total\":9.99}"));

        String s = out.toString();
        assertTrue(s.contains("Published to **orders.v1**"), s);
        assertTrue(s.contains("partition:"));
        assertTrue(s.contains("offset:"));

        // MockProducer recorded the send
        assertEquals(1, mock.history().size());
        var rec = mock.history().get(0);
        assertEquals("orders.v1", rec.topic());
        assertEquals("order-42", rec.key());
        assertEquals("{\"id\":42,\"total\":9.99}", rec.value());
    }

    @Test
    @DisplayName("publish: null key is accepted (round-robin partitioning)")
    void publishNullKey() {
        Object out = tool.execute(Map.of("topic", "events", "value", "hello"));

        assertTrue(out.toString().contains("Published to"), out.toString());
        var rec = mock.history().get(0);
        assertNull(rec.key());
    }

    @Test
    @DisplayName("publish: explicit partition is honored")
    void publishExplicitPartition() {
        Object out = tool.execute(Map.of(
            "topic", "events",
            "value", "v",
            "partition", 2));

        assertTrue(out.toString().contains("Published to"), out.toString());
        assertEquals(Integer.valueOf(2), mock.history().get(0).partition());
    }

    @Test
    @DisplayName("publish: headers are attached as UTF-8 bytes")
    void publishHeaders() {
        tool.execute(Map.of(
            "topic", "events",
            "value", "v",
            "headers", Map.of("correlation-id", "abc-123", "source", "agent")));

        var rec = mock.history().get(0);
        assertEquals("abc-123",
            new String(rec.headers().lastHeader("correlation-id").value(), StandardCharsets.UTF_8));
        assertEquals("agent",
            new String(rec.headers().lastHeader("source").value(), StandardCharsets.UTF_8));
    }

    // ===== Config resolution =====

    @Test
    @DisplayName("publish: bootstrap param > default field (precedence)")
    void bootstrapPrecedence() {
        tool.execute(Map.of(
            "topic", "t",
            "value", "v",
            "bootstrap_servers", "override-broker:9093"));

        Properties p = capturedProps.get();
        assertEquals("override-broker:9093", p.getProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG));
    }

    @Test
    @DisplayName("publish: extra 'config' map is merged into producer properties")
    void extraConfigMerged() {
        tool.execute(Map.of(
            "topic", "t",
            "value", "v",
            "config", Map.of(
                "security.protocol", "SASL_SSL",
                "sasl.mechanism", "PLAIN",
                "compression.type", "zstd")));

        Properties p = capturedProps.get();
        assertEquals("SASL_SSL", p.getProperty("security.protocol"));
        assertEquals("PLAIN",    p.getProperty("sasl.mechanism"));
        assertEquals("zstd",     p.getProperty("compression.type"));
    }

    @Test
    @DisplayName("publish: default producer config enables idempotence + acks=all + 3 retries")
    void safeDefaults() {
        tool.execute(Map.of("topic", "t", "value", "v"));
        Properties p = capturedProps.get();
        // Properties.getProperty returns null for non-String values; use .get() to see Integer/Boolean too.
        assertEquals("all", p.get(ProducerConfig.ACKS_CONFIG));
        assertEquals(3,     p.get(ProducerConfig.RETRIES_CONFIG));
        assertEquals(true,  p.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG));
    }

    // ===== Validation =====

    @Test
    @DisplayName("missing topic → error, no send")
    void missingTopic() {
        Object out = tool.execute(Map.of("value", "v"));
        assertTrue(out.toString().startsWith("Error"));
        assertEquals(0, mock.history().size());
    }

    @Test
    @DisplayName("missing value → error, no send")
    void missingValue() {
        Object out = tool.execute(Map.of("topic", "t"));
        assertTrue(out.toString().startsWith("Error"));
        assertEquals(0, mock.history().size());
    }

    @Test
    @DisplayName("missing bootstrap everywhere → setup hint")
    void missingBootstrap() {
        ReflectionTestUtils.setField(tool, "defaultBootstrapServers", "");
        Object out = tool.execute(Map.of("topic", "t", "value", "v"));
        assertTrue(out.toString().contains("not configured"));
    }

    // ===== Failure modes =====

    @Test
    @DisplayName("broker error: MockProducer send exception surfaces cleanly (no stack leak)")
    void brokerRejection() {
        // Rebuild MockProducer in manual-complete mode so we can make send fail.
        MockProducer<String, String> manual = new MockProducer<>(false, new StringSerializer(), new StringSerializer());
        KafkaProducerTool failingTool = new KafkaProducerTool(props -> (Producer<String, String>) manual);
        ReflectionTestUtils.setField(failingTool, "defaultBootstrapServers", "broker:9092");

        // Fire the send on a background thread, then make it fail.
        new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            manual.errorNext(new RuntimeException("LEADER_NOT_AVAILABLE"));
        }).start();

        Object out = failingTool.execute(Map.of(
            "topic", "t", "value", "v", "timeout_seconds", 2));
        assertTrue(out.toString().startsWith("Error"), out.toString());
        assertTrue(out.toString().contains("LEADER_NOT_AVAILABLE") || out.toString().contains("broker rejected"),
            out.toString());
    }
}
