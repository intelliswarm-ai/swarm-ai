package ai.intelliswarm.swarmai.tool.research;

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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for WolframAlphaTool — live calls against Wolfram Alpha.
 * Requires WOLFRAM_APPID env var (free at https://developer.wolframalpha.com/).
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "WOLFRAM_APPID", matches = ".+")
@DisplayName("WolframAlphaTool Integration Tests")
class WolframAlphaToolIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(WolframAlphaToolIntegrationTest.class);
    private static final String OUTPUT_DIR = "target/integration-test-outputs";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private WolframAlphaTool tool;

    @BeforeEach
    void setUp() {
        tool = new WolframAlphaTool();
        ReflectionTestUtils.setField(tool, "appId", System.getenv("WOLFRAM_APPID"));
        try {
            Path p = Paths.get(OUTPUT_DIR);
            if (!Files.exists(p)) Files.createDirectories(p);
        } catch (IOException e) {
            logger.warn("Could not create output dir: {}", e.getMessage());
        }
    }

    @Test
    @DisplayName("short: '2+2' returns '4'")
    void shortArithmetic() {
        Object out = tool.execute(Map.of("input", "2+2"));
        assertFalse(out.toString().startsWith("Error"), out.toString());
        assertTrue(out.toString().contains("4"), "Expected '4' in answer. Got: " + out);
        write("short_2plus2", out.toString());
    }

    @Test
    @DisplayName("short: unit conversion has a sensible string")
    void shortUnitConversion() {
        Object out = tool.execute(Map.of("input", "10 km in miles"));
        assertFalse(out.toString().startsWith("Error"), out.toString());
        assertTrue(out.toString().toLowerCase().contains("mile"),
            "Expected 'mile' in result. Got: " + out);
        write("short_km_to_miles", out.toString());
    }

    @Test
    @DisplayName("full: integrate x^2 dx produces pods with an antiderivative")
    void fullIntegral() {
        Object out = tool.execute(Map.of("input", "integrate x^2 dx", "mode", "full"));
        assertFalse(out.toString().startsWith("Error"), out.toString());
        // Should mention the antiderivative x^3/3
        assertTrue(out.toString().contains("x^3/3") || out.toString().contains("x³/3"),
            "Expected antiderivative. Got: " + out);
        write("full_integral", out.toString());
    }

    @Test
    @DisplayName("smokeTest passes with real app id")
    void smokeOk() {
        assertNull(tool.smokeTest(), "Expected healthy (null)");
    }

    private void write(String label, String content) {
        String filename = OUTPUT_DIR + "/wolfram_" + label + "_" + LocalDateTime.now().format(TS) + ".md";
        try {
            Files.writeString(Paths.get(filename), content);
            logger.info("Wrote sample to {}", filename);
        } catch (IOException e) {
            logger.warn("Failed to write sample {}: {}", filename, e.getMessage());
        }
    }
}
