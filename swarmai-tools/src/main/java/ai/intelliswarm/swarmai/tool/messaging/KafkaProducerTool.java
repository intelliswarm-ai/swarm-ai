package ai.intelliswarm.swarmai.tool.messaging;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import ai.intelliswarm.swarmai.tool.base.ToolRequirements;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * KafkaProducerTool — publish string-valued messages to a Kafka topic.
 *
 * <p>Configuration (resolved per call): {@code bootstrap_servers} param →
 * {@code swarmai.tools.kafka.bootstrap-servers} property →
 * {@code KAFKA_BOOTSTRAP_SERVERS} env var.
 *
 * <p>Extra Kafka client properties can be passed as a {@code config} map for SASL /
 * SSL / acks / compression-type / etc. Keys follow the standard {@code ProducerConfig}
 * names (e.g. {@code "security.protocol": "SASL_SSL"}).
 *
 * <p>Permission level is {@code WORKSPACE_WRITE} — the tool can publish side-effecting
 * events into shared infrastructure. Message value is always a String; binary not supported.
 */
@Component
public class KafkaProducerTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(KafkaProducerTool.class);
    private static final int DEFAULT_SEND_TIMEOUT_S = 15;

    @Value("${swarmai.tools.kafka.bootstrap-servers:}")
    private String defaultBootstrapServers = "";

    private final Function<Properties, Producer<String, String>> producerFactory;

    public KafkaProducerTool() {
        this(KafkaProducer::new);
    }

    /** Test-friendly: inject a custom factory that returns a mock producer. */
    KafkaProducerTool(Function<Properties, Producer<String, String>> producerFactory) {
        this.producerFactory = producerFactory;
    }

    @Override public String getFunctionName() { return "kafka_produce"; }

    @Override
    public String getDescription() {
        return "Publish a message to a Kafka topic. Keys and values are strings (JSON, text, or " +
               "anything UTF-8). Extra producer config (SASL, SSL, acks, compression) can be passed " +
               "as a 'config' map. Requires KAFKA_BOOTSTRAP_SERVERS env or an explicit 'bootstrap_servers' param.";
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String topic = asString(parameters.get("topic"));
        if (topic == null || topic.isBlank()) {
            return "Error: 'topic' parameter is required.";
        }
        String value = asString(parameters.get("value"));
        if (value == null) {
            return "Error: 'value' parameter is required (the message body as a string).";
        }
        String bootstrap = pickBootstrap(parameters);
        if (bootstrap == null) {
            return "Error: Kafka bootstrap servers not configured. Provide 'bootstrap_servers' or set " +
                   "KAFKA_BOOTSTRAP_SERVERS env / swarmai.tools.kafka.bootstrap-servers property.";
        }

        String key = asString(parameters.get("key"));
        Map<String, String> headers = toStringMap(parameters.get("headers"));
        Integer partition = parseOptionalInt(parameters.get("partition"));
        int timeoutSeconds = parseInt(parameters.get("timeout_seconds"), DEFAULT_SEND_TIMEOUT_S, 1, 120);

        Properties props = buildProps(bootstrap, parameters.get("config"));

        logger.info("KafkaProducerTool: topic={} key={} bootstrap={} value={} bytes",
            topic, key == null ? "-" : truncate(key, 60), bootstrap, value.getBytes(StandardCharsets.UTF_8).length);

        try (Producer<String, String> producer = producerFactory.apply(props)) {
            ProducerRecord<String, String> record = partition == null
                ? new ProducerRecord<>(topic, key, value)
                : new ProducerRecord<>(topic, partition, key, value);
            headers.forEach((k, v) -> record.headers().add(
                new RecordHeader(k, v == null ? null : v.getBytes(StandardCharsets.UTF_8))));

            RecordMetadata md = producer.send(record).get(timeoutSeconds, TimeUnit.SECONDS);
            producer.flush();

            StringBuilder out = new StringBuilder();
            out.append("Published to **").append(md.topic()).append("**\n");
            out.append("partition: ").append(md.partition()).append('\n');
            out.append("offset:    ").append(md.offset()).append('\n');
            out.append("timestamp: ").append(md.timestamp()).append('\n');
            if (md.hasOffset()) {
                long latency = System.currentTimeMillis() - md.timestamp();
                if (latency > 0) out.append("latencyMs: ").append(latency).append('\n');
            }
            return out.toString().trim();

        } catch (TimeoutException | java.util.concurrent.TimeoutException e) {
            return "Error: Kafka send timed out after " + timeoutSeconds + "s. Broker unreachable or slow.";
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return "Error: Kafka broker rejected the message — " + cause.getClass().getSimpleName() +
                   ": " + cause.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: Kafka send interrupted.";
        } catch (Exception e) {
            logger.error("KafkaProducerTool unexpected error", e);
            return "Error: " + e.getClass().getSimpleName() + " — " + e.getMessage();
        }
    }

    // ---------- helpers ----------

    private Properties buildProps(String bootstrap, Object extraConfig) {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.RETRIES_CONFIG, 3);
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        p.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);
        p.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 20_000);
        p.put("client.id", "swarmai-kafka-tool");
        if (extraConfig instanceof Map<?, ?> raw) {
            raw.forEach((k, v) -> {
                if (k != null) p.put(k.toString(), v == null ? "" : v.toString());
            });
        }
        return p;
    }

    private String pickBootstrap(Map<String, Object> parameters) {
        String param = asString(parameters.get("bootstrap_servers"));
        if (param != null && !param.isBlank()) return param;
        if (defaultBootstrapServers != null && !defaultBootstrapServers.isBlank()) return defaultBootstrapServers;
        String env = System.getenv("KAFKA_BOOTSTRAP_SERVERS");
        return env != null && !env.isBlank() ? env : null;
    }

    private static Map<String, String> toStringMap(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) return new LinkedHashMap<>();
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (e.getKey() == null) continue;
            out.put(e.getKey().toString(), e.getValue() == null ? null : e.getValue().toString());
        }
        return out;
    }

    private static int parseInt(Object raw, int def, int min, int max) {
        if (raw == null) return def;
        try {
            int n = raw instanceof Number ? ((Number) raw).intValue() : Integer.parseInt(raw.toString().trim());
            return Math.max(min, Math.min(max, n));
        } catch (NumberFormatException e) { return def; }
    }

    private static Integer parseOptionalInt(Object raw) {
        if (raw == null) return null;
        try {
            return raw instanceof Number ? ((Number) raw).intValue() : Integer.parseInt(raw.toString().trim());
        } catch (NumberFormatException e) { return null; }
    }

    private static String asString(Object v) { return v == null ? null : v.toString(); }
    private static String truncate(String s, int n) { return s.length() <= n ? s : s.substring(0, n) + "…"; }

    // ---------- metadata ----------

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();

        addStringProp(props, "topic", "Target Kafka topic name (required).");
        addStringProp(props, "value", "Message value as a string (required). JSON strings are common.");
        addStringProp(props, "key",   "Optional message key for partitioning.");

        Map<String, Object> headers = new HashMap<>();
        headers.put("type", "object");
        headers.put("description", "Map of record header name → string value.");
        props.put("headers", headers);

        Map<String, Object> partition = new HashMap<>();
        partition.put("type", "integer");
        partition.put("description", "Optional explicit partition index (usually omit and let Kafka hash the key).");
        props.put("partition", partition);

        addStringProp(props, "bootstrap_servers", "Comma-separated broker list (overrides env).");

        Map<String, Object> config = new HashMap<>();
        config.put("type", "object");
        config.put("description", "Extra Kafka producer properties (security.protocol, sasl.*, ssl.*, acks, etc.).");
        props.put("config", config);

        Map<String, Object> timeout = new HashMap<>();
        timeout.put("type", "integer");
        timeout.put("description", "Send timeout in seconds (1..120, default 15).");
        props.put("timeout_seconds", timeout);

        schema.put("properties", props);
        schema.put("required", new String[]{"topic", "value"});
        return schema;
    }

    private static void addStringProp(Map<String, Object> props, String name, String desc) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", "string");
        m.put("description", desc);
        props.put(name, m);
    }

    @Override public boolean isAsync() { return false; }
    @Override public boolean isCacheable() { return false; }
    @Override public String getCategory() { return "messaging"; }
    @Override public List<String> getTags() { return List.of("kafka", "event-streaming", "publish", "integration"); }
    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.WORKSPACE_WRITE; }

    @Override
    public String getTriggerWhen() {
        return "User wants to publish an event / message to a Kafka topic: emit a domain event, dispatch " +
               "a task to downstream consumers, or integrate an agent's decision into an event-driven system.";
    }

    @Override
    public String getAvoidWhen() {
        return "Target is a different broker (RabbitMQ, SQS, Pub/Sub) or a synchronous REST API — " +
               "use the appropriate tool instead.";
    }

    @Override
    public ToolRequirements getRequirements() {
        return ToolRequirements.builder().env("KAFKA_BOOTSTRAP_SERVERS").build();
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return Map.of("type", "markdown",
            "description", "Publish confirmation with topic, partition, offset, and server timestamp.");
    }

    @Override
    public String smokeTest() {
        return pickBootstrap(Map.of()) == null ? "KAFKA_BOOTSTRAP_SERVERS not configured" : null;
    }

    public record Request(String topic, String value, String key, Map<String, String> headers,
                          Integer partition, String bootstrap_servers, Map<String, Object> config,
                          Integer timeout_seconds) {}
}
