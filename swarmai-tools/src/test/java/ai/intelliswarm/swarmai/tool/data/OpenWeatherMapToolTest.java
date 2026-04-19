package ai.intelliswarm.swarmai.tool.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("OpenWeatherMapTool Unit Tests")
class OpenWeatherMapToolTest {

    private RestTemplate restTemplate;
    private OpenWeatherMapTool tool;

    private static final String CURRENT_LONDON_JSON = """
        {
          "name": "London",
          "sys": { "country": "GB", "sunrise": 1713247560, "sunset": 1713299520 },
          "timezone": 3600,
          "main": { "temp": 14.2, "feels_like": 13.1, "humidity": 72, "pressure": 1014 },
          "wind": { "speed": 3.5 },
          "weather": [ { "main": "Clouds", "description": "scattered clouds" } ]
        }
        """;

    private static final String FORECAST_3DAY_JSON = """
        {
          "city": { "name": "Berlin", "country": "DE" },
          "list": [
            { "dt": 1713340800, "main": { "temp": 10.0 }, "weather": [ { "description": "clear sky" } ] },
            { "dt": 1713384000, "main": { "temp": 18.5 }, "weather": [ { "description": "clear sky" } ] },
            { "dt": 1713427200, "main": { "temp": 16.0 }, "weather": [ { "description": "few clouds" } ] },
            { "dt": 1713510400, "main": { "temp": 11.2 }, "weather": [ { "description": "light rain" } ] },
            { "dt": 1713553600, "main": { "temp": 20.0 }, "weather": [ { "description": "light rain" } ] }
          ]
        }
        """;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        tool = new OpenWeatherMapTool(restTemplate, new ObjectMapper());
        ReflectionTestUtils.setField(tool, "apiKey", "TEST-KEY");
    }

    // ===== Interface =====

    @Test void functionName() { assertEquals("weather", tool.getFunctionName()); }

    @Test
    void schemaLayout() {
        Map<String, Object> schema = tool.getParameterSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertTrue(props.keySet().containsAll(java.util.Set.of("operation", "city", "lat", "lon", "units")));
    }

    // ===== Location resolution =====

    @Test
    @DisplayName("city → q= query param; units default to metric")
    void cityIsSent() {
        stub200(CURRENT_LONDON_JSON);

        tool.execute(Map.of("city", "London"));

        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        verify(restTemplate).exchange(uri.capture(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
        String url = uri.getValue().toString();
        assertTrue(url.contains("q=London"), url);
        assertTrue(url.contains("units=metric"), url);
        assertTrue(url.contains("appid=TEST-KEY"), url);
    }

    @Test
    @DisplayName("lat/lon → lat=&lon= query params")
    void latLonIsSent() {
        stub200(CURRENT_LONDON_JSON);

        tool.execute(Map.of("lat", 48.8566, "lon", 2.3522));

        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        verify(restTemplate).exchange(uri.capture(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
        String url = uri.getValue().toString();
        assertTrue(url.contains("lat=48.8566"), url);
        assertTrue(url.contains("lon=2.3522"), url);
    }

    @Test
    @DisplayName("out-of-range lat/lon returns an error and doesn't call the API")
    void badCoords() {
        Object out = tool.execute(Map.of("lat", 200.0, "lon", 0.0));
        assertTrue(out.toString().startsWith("Error"));
        verify(restTemplate, never())
            .exchange(any(URI.class), any(HttpMethod.class), any(HttpEntity.class), eq(String.class));
    }

    @Test
    @DisplayName("no location at all → error")
    void noLocation() {
        Object out = tool.execute(Map.of());
        assertTrue(out.toString().contains("city"));
    }

    @Test
    @DisplayName("invalid units → error")
    void invalidUnits() {
        Object out = tool.execute(Map.of("city", "London", "units", "Fahrenheit"));
        assertTrue(out.toString().startsWith("Error"));
    }

    // ===== Current formatting =====

    @Test
    @DisplayName("current: formats temperature, humidity, wind, and sun times with correct units")
    void currentFormat() {
        stub200(CURRENT_LONDON_JSON);

        Object out = tool.execute(Map.of("city", "London"));

        String s = out.toString();
        assertTrue(s.contains("London, GB"));
        assertTrue(s.contains("Scattered clouds"), "Condition should be capitalized");
        assertTrue(s.contains("14.2°C"), "Temp in °C for metric. Got: " + s);
        assertTrue(s.contains("13.1°C"), "feels_like in °C");
        assertTrue(s.contains("72%"));
        assertTrue(s.contains("3.5 m/s"));
        assertTrue(s.contains("Sunrise:"));
        assertTrue(s.contains("Sunset:"));
    }

    @Test
    @DisplayName("current with imperial → °F and mph")
    void currentImperial() {
        stub200(CURRENT_LONDON_JSON);

        Object out = tool.execute(Map.of("city", "London", "units", "imperial"));

        String s = out.toString();
        assertTrue(s.contains("°F"), "Expected °F for imperial. Got: " + s);
        assertTrue(s.contains("mph"), "Expected mph for imperial");
    }

    // ===== Forecast formatting =====

    @Test
    @DisplayName("forecast: aggregates per-day min/max + representative condition")
    void forecastFormat() {
        stub200(FORECAST_3DAY_JSON);

        Object out = tool.execute(Map.of("city", "Berlin", "operation", "forecast"));

        String s = out.toString();
        assertTrue(s.contains("Berlin, DE"), "Header should name the city");
        assertTrue(s.contains("5-day forecast"));
        // Three days should appear as bullets
        int bullets = s.split("•", -1).length - 1;
        assertTrue(bullets >= 3, "Expected ≥3 day-bullets. Got " + bullets + " in:\n" + s);
        // Min/max range separator
        assertTrue(s.contains("…"), "Expected '…' between min and max");
    }

    // ===== Auth & errors =====

    @Test
    @DisplayName("401 → friendly 'new keys take up to 2 hours' hint")
    void unauthorized() {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized", null, null, null));

        Object out = tool.execute(Map.of("city", "London"));

        assertTrue(out.toString().contains("401"), out.toString());
        assertTrue(out.toString().contains("2 hours"), out.toString());
    }

    @Test
    @DisplayName("404 → friendly 'location not found' hint")
    void notFound() {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null));

        Object out = tool.execute(Map.of("city", "Atlantis"));

        assertTrue(out.toString().contains("location not found"), out.toString());
        assertTrue(out.toString().contains("Atlantis"));
    }

    @Test
    @DisplayName("no API key → setup hint, no network call")
    void missingKey() {
        ReflectionTestUtils.setField(tool, "apiKey", "");
        Object out = tool.execute(Map.of("city", "London"));
        assertTrue(out.toString().contains("not configured"));
        verify(restTemplate, never())
            .exchange(any(URI.class), any(HttpMethod.class), any(HttpEntity.class), eq(String.class));
    }

    private void stub200(String body) {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));
    }
}
