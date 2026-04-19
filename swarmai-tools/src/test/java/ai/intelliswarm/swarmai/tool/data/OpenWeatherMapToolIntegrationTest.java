package ai.intelliswarm.swarmai.tool.data;

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
 * Live integration tests for OpenWeatherMapTool.
 * Requires a free OPENWEATHER_API_KEY at https://openweathermap.org/api.
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "OPENWEATHER_API_KEY", matches = ".+")
@DisplayName("OpenWeatherMapTool Integration Tests")
class OpenWeatherMapToolIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(OpenWeatherMapToolIntegrationTest.class);
    private static final String OUTPUT_DIR = "target/integration-test-outputs";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private OpenWeatherMapTool tool;

    @BeforeEach
    void setUp() {
        tool = new OpenWeatherMapTool();
        ReflectionTestUtils.setField(tool, "apiKey", System.getenv("OPENWEATHER_API_KEY"));
        try {
            Path p = Paths.get(OUTPUT_DIR);
            if (!Files.exists(p)) Files.createDirectories(p);
        } catch (IOException e) {
            logger.warn("Could not create output dir: {}", e.getMessage());
        }
    }

    @Test
    @DisplayName("current: London returns a sensible temperature and GB country code")
    void currentLondon() {
        Object out = tool.execute(Map.of("city", "London", "operation", "current"));
        String s = out.toString();
        assertFalse(s.startsWith("Error"), "Unexpected error: " + s);
        assertTrue(s.contains("London, GB"), "Expected 'London, GB' in header. Got:\n" + s);
        assertTrue(s.contains("°C"), "Expected °C (metric default)");
        assertTrue(s.contains("Humidity:"));
        assertTrue(s.contains("Wind:"));
        // Temperature of London is almost certainly between -20°C and +45°C
        double temp = extractTemp(s);
        assertTrue(temp > -20 && temp < 45,
            "Temperature outside sanity range (-20..45). Got " + temp + "°C in:\n" + s);
        write("current_london", s);
    }

    @Test
    @DisplayName("current by lat/lon: Paris coords resolve to the correct city")
    void currentLatLonParis() {
        Object out = tool.execute(Map.of(
            "lat", 48.8566, "lon", 2.3522, "operation", "current"));
        String s = out.toString();
        assertFalse(s.startsWith("Error"), "Unexpected error: " + s);
        assertTrue(s.contains("Paris"), "Expected 'Paris' in output. Got:\n" + s);
        write("current_lat_lon_paris", s);
    }

    @Test
    @DisplayName("forecast: Berlin returns at least 5 daily bullets")
    void forecastBerlin() {
        Object out = tool.execute(Map.of("city", "Berlin", "operation", "forecast"));
        String s = out.toString();
        assertFalse(s.startsWith("Error"), "Unexpected error: " + s);
        assertTrue(s.contains("5-day forecast"));
        long dayBullets = s.chars().filter(c -> c == '•').count();
        assertTrue(dayBullets >= 5,
            "Expected ≥5 day bullets, got " + dayBullets + " in:\n" + s);
        write("forecast_berlin", s);
    }

    @Test
    @DisplayName("unknown city: clear error message, no exception leaks")
    void unknownCity() {
        Object out = tool.execute(Map.of("city", "Qwertyville-Nowhere-12345"));
        String s = out.toString();
        assertTrue(s.contains("location not found") || s.contains("Error"),
            "Expected location-not-found error. Got: " + s);
    }

    @Test
    @DisplayName("smokeTest passes with real key")
    void smokeOk() {
        assertNull(tool.smokeTest(), "Expected healthy (null)");
    }

    // ---------- helpers ----------

    private static double extractTemp(String out) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("Temperature: ([-\\d.]+)").matcher(out);
        return m.find() ? Double.parseDouble(m.group(1)) : Double.NaN;
    }

    private void write(String label, String content) {
        String filename = OUTPUT_DIR + "/openweather_" + label + "_" + LocalDateTime.now().format(TS) + ".md";
        try {
            Files.writeString(Paths.get(filename), content);
            logger.info("Wrote sample to {}", filename);
        } catch (IOException e) {
            logger.warn("Failed to write sample {}: {}", filename, e.getMessage());
        }
    }
}
