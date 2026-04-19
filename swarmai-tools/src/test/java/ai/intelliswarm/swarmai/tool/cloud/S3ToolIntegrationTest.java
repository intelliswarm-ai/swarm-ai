package ai.intelliswarm.swarmai.tool.cloud;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live integration tests for S3Tool.
 *
 * Required:
 *   - AWS_REGION        : region of the test bucket
 *   - S3_TEST_BUCKET    : bucket you OWN and can write to — the test creates and deletes
 *                         an object keyed under "swarmai-it/<uuid>.txt".
 *
 * Credentials are picked up from the AWS default chain (env vars, profile, IAM role, etc.).
 *
 * Tips:
 *   - For local development without AWS, run LocalStack:
 *       docker run --rm -it -p 4566:4566 localstack/localstack
 *     then set AWS_ENDPOINT_URL_S3=http://localhost:4566 and use a bucket created inside LocalStack.
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "S3_TEST_BUCKET", matches = ".+")
@DisplayName("S3Tool Integration Tests")
class S3ToolIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(S3ToolIntegrationTest.class);
    private static final String OUTPUT_DIR = "target/integration-test-outputs";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private S3Tool tool;
    private String bucket;

    @BeforeEach
    void setUp() {
        tool = new S3Tool();
        bucket = System.getenv("S3_TEST_BUCKET");
        try {
            Path p = Paths.get(OUTPUT_DIR);
            if (!Files.exists(p)) Files.createDirectories(p);
        } catch (IOException e) {
            logger.warn("Could not create output dir: {}", e.getMessage());
        }
    }

    @Test
    @DisplayName("round-trip: put → head → get → list → delete")
    void roundTrip() {
        String key = "swarmai-it/" + UUID.randomUUID() + ".txt";
        String body = "hello from SwarmAI S3Tool at " + LocalDateTime.now();

        // put
        Object put = tool.execute(Map.of(
            "operation", "put",
            "bucket", bucket,
            "key", key,
            "content", body,
            "content_type", "text/plain; charset=utf-8"));
        assertFalse(put.toString().startsWith("Error"), "Put failed: " + put);
        assertTrue(put.toString().contains("Wrote"), put.toString());

        // head
        Object head = tool.execute(Map.of("operation", "head", "bucket", bucket, "key", key));
        assertFalse(head.toString().startsWith("Error"), "Head failed: " + head);
        assertTrue(head.toString().contains("Size:"));
        assertTrue(head.toString().contains("text/plain"));

        // get — content must match what we wrote
        Object get = tool.execute(Map.of("operation", "get", "bucket", bucket, "key", key));
        String getStr = get.toString();
        assertFalse(getStr.startsWith("Error"), "Get failed: " + getStr);
        assertTrue(getStr.contains(body), "Round-trip body mismatch. Got:\n" + getStr);

        // list — must see our key under the prefix
        Object list = tool.execute(Map.of(
            "operation", "list",
            "bucket", bucket,
            "prefix", "swarmai-it/",
            "max_keys", 50));
        assertFalse(list.toString().startsWith("Error"), "List failed: " + list);
        assertTrue(list.toString().contains(key), "Expected to see newly-written key in list. Got:\n" + list);
        write("round_trip_list", list.toString());

        // delete
        Object del = tool.execute(Map.of("operation", "delete", "bucket", bucket, "key", key));
        assertFalse(del.toString().startsWith("Error"), "Delete failed: " + del);
        assertTrue(del.toString().contains("Deleted s3://" + bucket + "/" + key));
    }

    @Test
    @DisplayName("nonexistent key → clean 'not found' error")
    void getMissing() {
        Object out = tool.execute(Map.of(
            "operation", "get",
            "bucket", bucket,
            "key", "swarmai-it/nonexistent-" + UUID.randomUUID()));
        assertTrue(out.toString().contains("not found") || out.toString().startsWith("Error"),
            "Expected not-found. Got: " + out);
    }

    @Test
    @DisplayName("smokeTest is healthy with resolvable region/creds")
    void smokeOk() {
        assertNull(tool.smokeTest(), "Expected healthy (null)");
    }

    private void write(String label, String content) {
        String filename = OUTPUT_DIR + "/s3_" + label + "_" + LocalDateTime.now().format(TS) + ".md";
        try {
            Files.writeString(Paths.get(filename), content);
            logger.info("Wrote sample to {}", filename);
        } catch (IOException e) {
            logger.warn("Failed to write sample {}: {}", filename, e.getMessage());
        }
    }
}
