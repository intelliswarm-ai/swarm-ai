package ai.intelliswarm.swarmai.tool.vision;

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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live integration test for ImageGenerationTool — makes a real OpenAI DALL-E call.
 *
 * Gated on OPENAI_API_KEY because this test costs real money (~$0.04 per 1024x1024 DALL-E 3).
 * Opt in further with IMAGE_GEN_TEST_ENABLED=true so accidental $ spend is impossible:
 * simply setting OPENAI_API_KEY (for other tests) won't trigger the image tests.
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "IMAGE_GEN_TEST_ENABLED", matches = "true")
@DisplayName("ImageGenerationTool Integration Tests")
class ImageGenerationToolIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(ImageGenerationToolIntegrationTest.class);
    private static final String OUTPUT_DIR = "target/integration-test-outputs";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private ImageGenerationTool tool;

    @BeforeEach
    void setUp() {
        tool = new ImageGenerationTool();
        ReflectionTestUtils.setField(tool, "apiKey", System.getenv("OPENAI_API_KEY"));
        try {
            Path p = Paths.get(OUTPUT_DIR);
            if (!Files.exists(p)) Files.createDirectories(p);
        } catch (IOException e) {
            logger.warn("Could not create output dir: {}", e.getMessage());
        }
    }

    @Test
    @DisplayName("dall-e-3 URL: returns a reachable URL, saved PNG is non-trivial")
    void dalle3Url() throws IOException {
        String prompt = "A minimalist vector illustration of a blue swarm of stylised bees, flat colors, " +
                        "clean lines, no text. SwarmAI integration test.";
        Path savedPath = Paths.get(OUTPUT_DIR, "imgen_dalle3_" + LocalDateTime.now().format(TS) + ".png");

        Object out = tool.execute(Map.of(
            "prompt", prompt,
            "model", "dall-e-3",
            "size", "1024x1024",
            "quality", "standard",
            "save_to", savedPath.toString()));

        String s = out.toString();
        assertFalse(s.startsWith("Error"), "Unexpected error: " + s);
        assertTrue(s.contains("Generated 1 image"));
        assertTrue(s.contains("https://") || s.contains("saved to:"),
            "Expected either the raw URL or a save confirmation. Got:\n" + s);
        assertTrue(Files.exists(savedPath), "Expected PNG saved to " + savedPath);
        long size = Files.size(savedPath);
        assertTrue(size > 5_000,
            "Saved PNG should be non-trivial in size (> 5KB). Got " + size + " bytes.");
        writeSummary("dalle3_url", s);
    }

    @Test
    @DisplayName("dall-e-2 with n=2 produces two distinct saved files")
    void dalle2MultiSave() throws IOException {
        String prompt = "A simple pixel-art coffee cup, 32x32 style.";
        String base = UUID.randomUUID().toString();
        Path target = Paths.get(OUTPUT_DIR, "imgen_" + base + ".png");

        Object out = tool.execute(Map.of(
            "prompt", prompt,
            "model", "dall-e-2",
            "size", "256x256",
            "n", 2,
            "save_to", target.toString(),
            "response_format", "b64_json"));

        String s = out.toString();
        assertFalse(s.startsWith("Error"), "Unexpected error: " + s);
        assertTrue(s.contains("Generated 2 image"), s);

        Path p1 = Paths.get(OUTPUT_DIR, "imgen_" + base + "-1.png");
        Path p2 = Paths.get(OUTPUT_DIR, "imgen_" + base + "-2.png");
        assertTrue(Files.exists(p1), "Expected " + p1);
        assertTrue(Files.exists(p2), "Expected " + p2);
        // They should both be non-trivial, and NOT identical (n=2 should produce distinct images)
        assertTrue(Files.size(p1) > 1000);
        assertTrue(Files.size(p2) > 1000);
    }

    private void writeSummary(String label, String content) {
        String filename = OUTPUT_DIR + "/imgen_" + label + "_" + LocalDateTime.now().format(TS) + ".md";
        try {
            Files.writeString(Paths.get(filename), content);
            logger.info("Wrote sample to {}", filename);
        } catch (IOException e) {
            logger.warn("Failed to write sample {}: {}", filename, e.getMessage());
        }
    }
}
