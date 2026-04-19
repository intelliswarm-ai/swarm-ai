package ai.intelliswarm.swarmai.tool.research;

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

@DisplayName("WolframAlphaTool Unit Tests")
class WolframAlphaToolTest {

    private RestTemplate restTemplate;
    private WolframAlphaTool tool;

    private static final String FULL_RESPONSE_JSON = """
        {
          "queryresult": {
            "success": true,
            "pods": [
              { "title": "Input interpretation", "subpods": [ { "plaintext": "integral of x^2 dx" } ] },
              { "title": "Indefinite integral",  "subpods": [ { "plaintext": "x^3/3 + constant" } ] }
            ]
          }
        }
        """;

    private static final String FULL_NO_RESULT_JSON = """
        { "queryresult": { "success": false, "pods": [] } }
        """;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        tool = new WolframAlphaTool(restTemplate, new ObjectMapper());
        ReflectionTestUtils.setField(tool, "appId", "TEST-APPID");
    }

    // ===== Interface =====

    @Test void functionName() { assertEquals("wolfram_alpha", tool.getFunctionName()); }

    @Test
    void parameterSchema() {
        Map<String, Object> schema = tool.getParameterSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertTrue(props.containsKey("input"));
        assertTrue(props.containsKey("mode"));
        assertArrayEquals(new String[]{"input"}, (String[]) schema.get("required"));
    }

    @Test
    void metadata() {
        assertFalse(tool.isAsync());
        assertTrue(tool.isCacheable());
        assertEquals("research", tool.getCategory());
        assertTrue(tool.getRequirements().env().contains("WOLFRAM_APPID"));
    }

    // ===== Short mode =====

    @Test
    @DisplayName("short mode returns the plain-text body")
    void shortModeHappy() {
        stub200("4");

        Object out = tool.execute(Map.of("input", "2+2"));

        assertEquals("4", out.toString());
    }

    @Test
    @DisplayName("short mode is the default when no 'mode' is specified")
    void shortIsDefault() {
        stub200("4");

        tool.execute(Map.of("input", "2+2"));

        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        verify(restTemplate).exchange(uri.capture(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
        assertTrue(uri.getValue().toString().startsWith("https://api.wolframalpha.com/v1/result"),
            uri.getValue().toString());
    }

    @Test
    @DisplayName("short mode URL-encodes the input and embeds the app id")
    void shortModeEncoding() {
        stub200("c ≈ 299792458 m/s");

        tool.execute(Map.of("input", "speed of light in m/s"));

        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        verify(restTemplate).exchange(uri.capture(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
        String url = uri.getValue().toString();
        assertTrue(url.contains("appid=TEST-APPID"), url);
        assertTrue(url.contains("speed+of+light") || url.contains("speed%20of%20light"), url);
    }

    // ===== Full mode =====

    @Test
    @DisplayName("full mode formats pods into markdown")
    void fullModeFormatsPods() {
        stub200(FULL_RESPONSE_JSON);

        Object out = tool.execute(Map.of("input", "integrate x^2", "mode", "full"));

        String s = out.toString();
        assertTrue(s.contains("Input interpretation"), s);
        assertTrue(s.contains("Indefinite integral"), s);
        assertTrue(s.contains("x^3/3 + constant"), s);
    }

    @Test
    @DisplayName("full mode returns a clear message when Wolfram can't interpret")
    void fullModeNoResult() {
        stub200(FULL_NO_RESULT_JSON);

        Object out = tool.execute(Map.of("input", "asdlkfj", "mode", "full"));

        assertTrue(out.toString().startsWith("No result"), out.toString());
    }

    // ===== Validation =====

    @Test
    @DisplayName("missing input returns an error without calling out")
    void missingInput() {
        Object out = tool.execute(Map.of());
        assertTrue(out.toString().startsWith("Error"));
        verify(restTemplate, never())
            .exchange(any(URI.class), any(HttpMethod.class), any(HttpEntity.class), eq(String.class));
    }

    @Test
    @DisplayName("missing app id returns a setup-hint error")
    void missingAppId() {
        ReflectionTestUtils.setField(tool, "appId", "");
        // Also no env var in this test JVM
        Object out = tool.execute(Map.of("input", "2+2"));
        assertTrue(out.toString().contains("app ID not configured"), out.toString());
    }

    @Test
    @DisplayName("unknown mode returns a helpful error")
    void unknownMode() {
        Object out = tool.execute(Map.of("input", "2+2", "mode", "diagram"));
        assertTrue(out.toString().contains("unknown mode"), out.toString());
    }

    // ===== Helpers =====

    private void stub200(String body) {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));
    }
}
