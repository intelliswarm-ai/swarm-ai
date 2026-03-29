package ai.intelliswarm.swarmai.tool.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for WebScrapeTool.
 * Tests URL validation, security checks, error handling, and interface compliance.
 * Network-dependent tests are in the integration test class.
 */
@DisplayName("WebScrapeTool Tests")
class WebScrapeToolTest {

    private WebScrapeTool webScrapeTool;

    @BeforeEach
    void setUp() {
        webScrapeTool = new WebScrapeTool();
    }

    // ==================== Interface Tests ====================

    @Test
    @DisplayName("Should return correct function name")
    void testGetFunctionName() {
        assertEquals("web_scrape", webScrapeTool.getFunctionName());
    }

    @Test
    @DisplayName("Should return correct description")
    void testGetDescription() {
        String description = webScrapeTool.getDescription();
        assertNotNull(description);
        assertTrue(description.contains("web page"), "Description should mention web pages");
        assertTrue(description.contains("content"), "Description should mention content extraction");
    }

    @Test
    @DisplayName("Should not be async")
    void testIsAsync() {
        assertFalse(webScrapeTool.isAsync());
    }

    @Test
    @DisplayName("Should be cacheable")
    void testIsCacheable() {
        assertTrue(webScrapeTool.isCacheable());
    }

    @Test
    @DisplayName("Should have correct parameter schema")
    void testGetParameterSchema() {
        Map<String, Object> schema = webScrapeTool.getParameterSchema();

        assertNotNull(schema);
        assertEquals("object", schema.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertNotNull(properties);
        assertTrue(properties.containsKey("url"), "Should have 'url' parameter");
        assertTrue(properties.containsKey("selector"), "Should have 'selector' parameter");
        assertTrue(properties.containsKey("include_links"), "Should have 'include_links' parameter");
        assertTrue(properties.containsKey("include_tables"), "Should have 'include_tables' parameter");

        @SuppressWarnings("unchecked")
        Map<String, Object> urlParam = (Map<String, Object>) properties.get("url");
        assertEquals("string", urlParam.get("type"));

        String[] required = (String[]) schema.get("required");
        assertNotNull(required);
        assertEquals(1, required.length);
        assertEquals("url", required[0]);
    }

    @Test
    @DisplayName("Should have higher max response length for web content")
    void testMaxResponseLength() {
        assertTrue(webScrapeTool.getMaxResponseLength() >= 10000,
            "Web scrape should allow at least 10K chars");
    }

    // ==================== URL Validation ====================

    @Test
    @DisplayName("Should handle null URL gracefully")
    void testNullUrl() {
        Map<String, Object> params = new HashMap<>();
        params.put("url", null);

        Object result = webScrapeTool.execute(params);

        assertNotNull(result);
        String resultStr = result.toString();
        assertTrue(resultStr.contains("Error"), "Should return error for null URL");
        assertTrue(resultStr.contains("required"), "Should indicate URL is required");
    }

    @Test
    @DisplayName("Should handle empty URL gracefully")
    void testEmptyUrl() {
        Map<String, Object> params = Map.of("url", "");

        Object result = webScrapeTool.execute(params);

        assertNotNull(result);
        assertTrue(result.toString().contains("Error"), "Should return error for empty URL");
    }

    @Test
    @DisplayName("Should reject non-http/https protocols")
    void testRejectNonHttpProtocols() {
        for (String badUrl : new String[]{"ftp://example.com", "file:///etc/passwd", "javascript:alert(1)"}) {
            Object result = webScrapeTool.execute(Map.of("url", badUrl));

            assertNotNull(result);
            assertTrue(result.toString().contains("Error"),
                "Should reject protocol in URL: " + badUrl + ". Got: " + result);
        }
    }

    @Test
    @DisplayName("Should reject invalid URL format")
    void testRejectInvalidUrlFormat() {
        Object result = webScrapeTool.execute(Map.of("url", "not a url at all"));

        assertNotNull(result);
        assertTrue(result.toString().contains("Error"), "Should reject invalid URL format");
    }

    @Test
    @DisplayName("Should reject URL without host")
    void testRejectUrlWithoutHost() {
        Object result = webScrapeTool.execute(Map.of("url", "http://"));

        assertNotNull(result);
        assertTrue(result.toString().contains("Error"), "Should reject URL without host");
    }

    // ==================== SSRF Prevention ====================

    @Test
    @DisplayName("Should block localhost URLs")
    void testBlockLocalhost() {
        Object result = webScrapeTool.execute(Map.of("url", "http://localhost/admin"));

        assertNotNull(result);
        String resultStr = result.toString();
        assertTrue(resultStr.contains("Error"), "Should block localhost");
        assertTrue(resultStr.contains("denied") || resultStr.contains("private") || resultStr.contains("internal"),
            "Should mention access denied. Got: " + resultStr);
    }

    @Test
    @DisplayName("Should block 127.0.0.1 URLs")
    void testBlockLoopback() {
        Object result = webScrapeTool.execute(Map.of("url", "http://127.0.0.1:8080/api"));

        assertNotNull(result);
        assertTrue(result.toString().contains("Error"), "Should block loopback IP");
    }

    @Test
    @DisplayName("Should block private IP ranges")
    void testBlockPrivateIPs() {
        String[] privateUrls = {
            "http://10.0.0.1/internal",
            "http://192.168.1.1/router",
            "http://172.16.0.1/admin"
        };

        for (String url : privateUrls) {
            Object result = webScrapeTool.execute(Map.of("url", url));
            assertNotNull(result);
            assertTrue(result.toString().contains("Error"),
                "Should block private IP: " + url + ". Got: " + result);
        }
    }

    @Test
    @DisplayName("Should block cloud metadata URLs")
    void testBlockCloudMetadata() {
        Object result = webScrapeTool.execute(Map.of("url", "http://169.254.169.254/latest/meta-data/"));

        assertNotNull(result);
        assertTrue(result.toString().contains("Error"), "Should block cloud metadata URL");
    }

    @Test
    @DisplayName("Should block metadata.google.internal")
    void testBlockGoogleMetadata() {
        Object result = webScrapeTool.execute(Map.of("url", "http://metadata.google.internal/computeMetadata/v1/"));

        assertNotNull(result);
        assertTrue(result.toString().contains("Error"), "Should block Google metadata URL");
    }

    // ==================== Connection Error Handling ====================

    @Test
    @DisplayName("Should handle unreachable host gracefully")
    void testUnreachableHost() {
        Object result = webScrapeTool.execute(Map.of("url", "https://this-domain-does-not-exist-xyz123.com"));

        assertNotNull(result);
        assertTrue(result.toString().contains("Error"), "Should return error for unreachable host");
    }

    @Test
    @DisplayName("Should handle connection refused gracefully")
    void testConnectionRefused() {
        // Port 1 is typically not open
        Object result = webScrapeTool.execute(Map.of("url", "http://example.com:1/page"));

        assertNotNull(result);
        assertTrue(result.toString().contains("Error"), "Should return error for connection refused");
    }

    // ==================== Parameter Combinations ====================

    @Test
    @DisplayName("Should accept all optional parameters without error")
    void testAllParameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("url", "https://this-domain-does-not-exist-xyz123.com"); // Will fail on connect, not on param validation
        params.put("selector", "article");
        params.put("include_links", true);
        params.put("include_tables", false);

        Object result = webScrapeTool.execute(params);

        assertNotNull(result);
        // Should fail on connection, not on parameter validation
        String resultStr = result.toString();
        assertTrue(resultStr.contains("Error"), "Should fail on connection");
        assertFalse(resultStr.contains("parameter") || resultStr.contains("required"),
            "Should not fail on parameter validation");
    }
}
