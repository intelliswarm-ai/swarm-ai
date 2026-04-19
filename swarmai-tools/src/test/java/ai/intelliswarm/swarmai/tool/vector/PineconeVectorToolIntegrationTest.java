package ai.intelliswarm.swarmai.tool.vector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live integration tests for PineconeVectorTool.
 *
 * Required env vars:
 *   - PINECONE_API_KEY       : your Pinecone key
 *   - PINECONE_INDEX_HOST    : full index host URL (from console → CONNECT)
 *
 * Optional:
 *   - PINECONE_INDEX_DIM     : index dimension (default 8 — only works if your test index matches)
 *   - PINECONE_TEST_NAMESPACE: namespace to scope writes/reads (default "swarmai-it"). Vectors will
 *                              be upserted AND cleared via delete_all at the end so this namespace
 *                              should be disposable.
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "PINECONE_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "PINECONE_INDEX_HOST", matches = ".+")
@DisplayName("PineconeVectorTool Integration Tests")
class PineconeVectorToolIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(PineconeVectorToolIntegrationTest.class);
    private static final String OUTPUT_DIR = "target/integration-test-outputs";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private PineconeVectorTool tool;
    private int dimension;
    private String namespace;

    @BeforeEach
    void setUp() {
        tool = new PineconeVectorTool();
        ReflectionTestUtils.setField(tool, "apiKey", System.getenv("PINECONE_API_KEY"));
        ReflectionTestUtils.setField(tool, "indexHost", System.getenv("PINECONE_INDEX_HOST"));
        dimension = parseEnvInt("PINECONE_INDEX_DIM", 8);
        namespace = System.getenv().getOrDefault("PINECONE_TEST_NAMESPACE", "swarmai-it");
        try {
            Path p = Paths.get(OUTPUT_DIR);
            if (!Files.exists(p)) Files.createDirectories(p);
        } catch (IOException e) {
            logger.warn("Could not create output dir: {}", e.getMessage());
        }
    }

    @Test
    @DisplayName("round-trip: upsert 3 vectors → query → verify the expected nearest match → delete_all")
    void upsertQueryDelete() {
        long seed = System.currentTimeMillis();
        Random r = new Random(seed);

        // Two deterministic vectors at opposite poles + one near the first.
        List<Double> poleA = fill(dimension, 1.0, 0.0);          // [1,0,0,...]
        List<Double> poleB = fill(dimension, 0.0, 1.0);          // [0,1,0,...]
        List<Double> nearA = jitter(poleA, r, 0.05);             // slight noise from poleA

        // Upsert
        Object up = tool.execute(Map.of(
            "operation", "upsert",
            "namespace", namespace,
            "vectors", List.of(
                Map.of("id", "poleA", "values", poleA, "metadata", Map.of("label", "A")),
                Map.of("id", "poleB", "values", poleB, "metadata", Map.of("label", "B")),
                Map.of("id", "nearA", "values", nearA, "metadata", Map.of("label", "A-ish"))
            )));
        assertFalse(up.toString().startsWith("Error"), "Upsert failed: " + up);
        assertTrue(up.toString().contains("Upserted 3"), up.toString());
        write("upsert", up.toString());

        // Pinecone is eventually consistent — give it a moment before querying.
        sleep(2000);

        // Query for neighbors of poleA; the top match should be poleA itself or nearA, NOT poleB.
        Object q = tool.execute(Map.of(
            "operation", "query",
            "namespace", namespace,
            "vector", poleA,
            "top_k", 2,
            "include_metadata", true));
        String qs = q.toString();
        assertFalse(qs.startsWith("Error"), "Query failed: " + qs);
        assertTrue(qs.contains("id=`poleA`") || qs.contains("id=`nearA`"),
            "Top-2 should include poleA or nearA. Got:\n" + qs);
        assertFalse(qs.indexOf("id=`poleB`") > -1 && qs.indexOf("id=`poleB`") < 100,
            "poleB should NOT be the nearest match. Got:\n" + qs);
        write("query", qs);

        // Clean up the namespace so the next run starts fresh.
        Object del = tool.execute(Map.of(
            "operation", "delete",
            "namespace", namespace,
            "delete_all", true));
        // Note: delete_all may not be supported on all index types (serverless free tier).
        // We log but don't fail on this step.
        logger.info("Cleanup: {}", del);
    }

    @Test
    @DisplayName("stats: returns dimension, totalVectorCount, namespace breakdown")
    void stats() {
        Object out = tool.execute(Map.of("operation", "stats"));
        String s = out.toString();
        assertFalse(s.startsWith("Error"), "Stats failed: " + s);
        assertTrue(s.contains("Dimension:"));
        assertTrue(s.contains("Total vectors:"));
        write("stats", s);
    }

    @Test
    @DisplayName("smokeTest is healthy with real credentials")
    void smokeOk() {
        assertNull(tool.smokeTest(), "Expected healthy (null)");
    }

    // ---------- helpers ----------

    private static List<Double> fill(int dim, double first, double second) {
        List<Double> v = new java.util.ArrayList<>(dim);
        for (int i = 0; i < dim; i++) {
            v.add(i == 0 ? first : i == 1 ? second : 0.0);
        }
        return v;
    }

    private static List<Double> jitter(List<Double> base, Random r, double magnitude) {
        List<Double> out = new java.util.ArrayList<>(base.size());
        for (Double d : base) out.add(d + (r.nextDouble() - 0.5) * 2 * magnitude);
        return out;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    private static int parseEnvInt(String name, int def) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) return def;
        try { return Integer.parseInt(raw.trim()); } catch (NumberFormatException e) { return def; }
    }

    private void write(String label, String content) {
        String filename = OUTPUT_DIR + "/pinecone_" + label + "_" + LocalDateTime.now().format(TS) + ".md";
        try {
            Files.writeString(Paths.get(filename), content);
            logger.info("Wrote sample to {}", filename);
        } catch (IOException e) {
            logger.warn("Failed to write sample {}: {}", filename, e.getMessage());
        }
    }
}
