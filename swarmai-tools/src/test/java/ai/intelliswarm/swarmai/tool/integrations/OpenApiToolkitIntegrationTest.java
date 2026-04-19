package ai.intelliswarm.swarmai.tool.integrations;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for OpenApiToolkit — drives a real public API to prove the adapter works
 * end-to-end. Uses petstore3.swagger.io, which ships a live sandbox server and accepts
 * unauthenticated requests; no API keys needed.
 *
 * If petstore3.swagger.io is ever down these tests will fail — that's fine, the unit tests
 * cover shape/contract and this file proves integration health when the internet is available.
 */
@Tag("integration")
@DisplayName("OpenApiToolkit Integration Tests")
class OpenApiToolkitIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(OpenApiToolkitIntegrationTest.class);
    private static final String OUTPUT_DIR = "target/integration-test-outputs";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    // Swagger Petstore v3 — officially hosted for OpenAPI demos.
    private static final String PETSTORE_SPEC_URL = "https://petstore3.swagger.io/api/v3/openapi.json";

    private OpenApiToolkit tool;

    @BeforeEach
    void setUp() {
        tool = new OpenApiToolkit();
        try {
            Path p = Paths.get(OUTPUT_DIR);
            if (!Files.exists(p)) Files.createDirectories(p);
        } catch (IOException e) {
            logger.warn("Could not create output dir: {}", e.getMessage());
        }
    }

    @Test
    @DisplayName("list_operations: pulls real petstore spec, finds known operationIds")
    void listPetstoreOps() {
        Object out = tool.execute(Map.of(
            "operation", "list_operations",
            "spec_url", PETSTORE_SPEC_URL));
        String s = out.toString();
        assertFalse(s.startsWith("Error"), "Unexpected error: " + s);
        // These are canonical petstore operationIds — if any change name, the tool or spec shifted.
        assertTrue(s.contains("**findPetsByStatus**"), "Expected 'findPetsByStatus'. Got:\n" + s);
        assertTrue(s.contains("**getPetById**"), "Expected 'getPetById'");
        assertTrue(s.contains("**placeOrder**"));
        assertTrue(s.contains("GET /pet/{petId}"));
        write("petstore_list_ops", s);
    }

    @Test
    @DisplayName("invoke: findPetsByStatus?status=available returns a JSON array, pretty-printed")
    void invokeFindByStatus() {
        Object out = tool.execute(Map.of(
            "operation", "invoke",
            "spec_url", PETSTORE_SPEC_URL,
            "operation_id", "findPetsByStatus",
            "query_params", Map.of("status", "available")));
        String s = out.toString();
        assertFalse(s.startsWith("Error"), "Unexpected error: " + s);
        assertTrue(s.startsWith("HTTP 200") || s.startsWith("HTTP 2"),
            "Expected 2xx status. Got prefix: " + s.substring(0, Math.min(50, s.length())));
        // Response should contain at least one "status" field since we filtered by it.
        assertTrue(s.contains("\"status\"") || s.contains("status :"),
            "Expected 'status' in JSON body. Got:\n" + s);
        write("petstore_findByStatus", s);
    }

    @Test
    @DisplayName("invoke: getPetById with a likely-nonexistent ID → clean HTTP 404 surface")
    void invokeNotFound() {
        Object out = tool.execute(Map.of(
            "operation", "invoke",
            "spec_url", PETSTORE_SPEC_URL,
            "operation_id", "getPetById",
            "path_params", Map.of("petId", "-9999999")));
        String s = out.toString();
        assertFalse(s.startsWith("Error: request failed"),
            "Expected a clean HTTP response, not a transport error. Got: " + s);
        // Most likely 404; but sandbox could return 400 for negative ids. Accept either.
        assertTrue(s.contains("HTTP 404") || s.contains("HTTP 400"),
            "Expected 404 or 400 for bogus id. Got:\n" + s);
        write("petstore_not_found", s);
    }

    private void write(String label, String content) {
        String filename = OUTPUT_DIR + "/openapi_" + label + "_" + LocalDateTime.now().format(TS) + ".md";
        try {
            Files.writeString(Paths.get(filename), content);
            logger.info("Wrote sample to {}", filename);
        } catch (IOException e) {
            logger.warn("Failed to write sample {}: {}", filename, e.getMessage());
        }
    }
}
