package ai.intelliswarm.swarmai.tool.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for HttpRequestTool.
 * Tests URL validation, security checks, method validation, and error handling.
 * Network-dependent tests are in the integration test class.
 */
@DisplayName("HttpRequestTool Tests")
class HttpRequestToolTest {

    private HttpRequestTool httpRequestTool;

    @BeforeEach
    void setUp() {
        httpRequestTool = new HttpRequestTool();
    }

    // ==================== Interface Tests ====================

    @Test
    @DisplayName("Should return correct function name")
    void testGetFunctionName() {
        assertEquals("http_request", httpRequestTool.getFunctionName());
    }

    @Test
    @DisplayName("Should return correct description")
    void testGetDescription() {
        String description = httpRequestTool.getDescription();
        assertNotNull(description);
        assertTrue(description.contains("HTTP"), "Description should mention HTTP");
        assertTrue(description.contains("REST"), "Description should mention REST APIs");
        assertTrue(description.contains("GET"), "Description should mention GET");
    }

    @Test
    @DisplayName("Should not be async")
    void testIsAsync() {
        assertFalse(httpRequestTool.isAsync());
    }

    @Test
    @DisplayName("Should have correct parameter schema")
    void testGetParameterSchema() {
        Map<String, Object> schema = httpRequestTool.getParameterSchema();

        assertNotNull(schema);
        assertEquals("object", schema.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertNotNull(properties);
        assertTrue(properties.containsKey("url"), "Should have 'url' parameter");
        assertTrue(properties.containsKey("method"), "Should have 'method' parameter");
        assertTrue(properties.containsKey("body"), "Should have 'body' parameter");
        assertTrue(properties.containsKey("headers"), "Should have 'headers' parameter");
        assertTrue(properties.containsKey("auth_token"), "Should have 'auth_token' parameter");

        @SuppressWarnings("unchecked")
        Map<String, Object> urlParam = (Map<String, Object>) properties.get("url");
        assertEquals("string", urlParam.get("type"));

        String[] required = (String[]) schema.get("required");
        assertNotNull(required);
        assertEquals(1, required.length);
        assertEquals("url", required[0]);
    }

    // ==================== URL Validation ====================

    @Test
    @DisplayName("Should handle null URL gracefully")
    void testNullUrl() {
        Map<String, Object> params = new HashMap<>();
        params.put("url", null);

        Object result = httpRequestTool.execute(params);

        assertNotNull(result);
        assertTrue(result.toString().contains("Error"), "Should return error for null URL");
    }

    @Test
    @DisplayName("Should handle empty URL gracefully")
    void testEmptyUrl() {
        Object result = httpRequestTool.execute(Map.of("url", ""));

        assertNotNull(result);
        assertTrue(result.toString().contains("Error"), "Should return error for empty URL");
    }

    @Test
    @DisplayName("Should reject non-http protocols")
    void testRejectNonHttpProtocols() {
        for (String badUrl : new String[]{"ftp://example.com", "file:///etc/passwd", "javascript:alert(1)"}) {
            Object result = httpRequestTool.execute(Map.of("url", badUrl));
            assertNotNull(result);
            assertTrue(result.toString().contains("Error"),
                "Should reject " + badUrl + ". Got: " + result);
        }
    }

    @Test
    @DisplayName("Should reject invalid URL format")
    void testRejectInvalidUrl() {
        Object result = httpRequestTool.execute(Map.of("url", "not a url"));
        assertNotNull(result);
        assertTrue(result.toString().contains("Error"), "Should reject invalid URL");
    }

    // ==================== HTTP Method Validation ====================

    @Test
    @DisplayName("Should default to GET method")
    void testDefaultGetMethod() {
        // Will fail on connection, but should not fail on method validation
        Object result = httpRequestTool.execute(Map.of("url", "https://nonexistent-test-host-xyz.com"));
        assertNotNull(result);
        // Should fail on connection, not method
        assertFalse(result.toString().contains("Invalid HTTP method"),
            "Should not reject default GET method");
    }

    @Test
    @DisplayName("Should accept all valid HTTP methods")
    void testValidHttpMethods() {
        for (String method : List.of("GET", "POST", "PUT", "DELETE")) {
            Map<String, Object> params = new HashMap<>();
            params.put("url", "https://nonexistent-test-host-xyz.com");
            params.put("method", method);

            Object result = httpRequestTool.execute(params);
            assertNotNull(result);
            assertFalse(result.toString().contains("Invalid HTTP method"),
                "Should accept method: " + method);
        }
    }

    @Test
    @DisplayName("Should accept lowercase HTTP methods")
    void testLowercaseMethod() {
        Map<String, Object> params = new HashMap<>();
        params.put("url", "https://nonexistent-test-host-xyz.com");
        params.put("method", "post");

        Object result = httpRequestTool.execute(params);
        assertNotNull(result);
        assertFalse(result.toString().contains("Invalid HTTP method"),
            "Should accept lowercase 'post'");
    }

    @Test
    @DisplayName("Should reject invalid HTTP methods")
    void testRejectInvalidMethod() {
        Map<String, Object> params = new HashMap<>();
        params.put("url", "https://example.com");
        params.put("method", "INVALID");

        Object result = httpRequestTool.execute(params);
        assertNotNull(result);
        assertTrue(result.toString().contains("Error"), "Should reject INVALID method");
        assertTrue(result.toString().contains("Invalid HTTP method"),
            "Should mention invalid method. Got: " + result);
    }

    // ==================== SSRF Prevention ====================

    @Test
    @DisplayName("Should block localhost URLs")
    void testBlockLocalhost() {
        Object result = httpRequestTool.execute(Map.of("url", "http://localhost/api"));
        assertNotNull(result);
        assertTrue(result.toString().contains("Error"), "Should block localhost");
        assertTrue(result.toString().contains("denied"),
            "Should mention access denied. Got: " + result);
    }

    @Test
    @DisplayName("Should block 127.0.0.1")
    void testBlockLoopback() {
        Object result = httpRequestTool.execute(Map.of("url", "http://127.0.0.1:8080/api"));
        assertNotNull(result);
        assertTrue(result.toString().contains("Error"), "Should block loopback");
    }

    @Test
    @DisplayName("Should block private IP ranges")
    void testBlockPrivateIPs() {
        for (String url : List.of(
            "http://10.0.0.1/internal",
            "http://192.168.1.1/router",
            "http://172.16.0.1/admin")) {

            Object result = httpRequestTool.execute(Map.of("url", url));
            assertNotNull(result);
            assertTrue(result.toString().contains("Error"),
                "Should block private IP: " + url);
        }
    }

    @Test
    @DisplayName("Should block cloud metadata endpoint")
    void testBlockCloudMetadata() {
        Object result = httpRequestTool.execute(Map.of("url", "http://169.254.169.254/latest/meta-data/"));
        assertNotNull(result);
        assertTrue(result.toString().contains("Error"), "Should block cloud metadata");
    }

    // ==================== Connection Error Handling ====================

    @Test
    @DisplayName("Should handle unreachable host gracefully")
    void testUnreachableHost() {
        Object result = httpRequestTool.execute(Map.of("url", "https://nonexistent-test-host-xyz.com/api"));
        assertNotNull(result);
        assertTrue(result.toString().contains("Error"), "Should return error for unreachable host");
    }

    // ==================== Request Body & Headers ====================

    @Test
    @DisplayName("Should accept request body parameter")
    void testRequestBody() {
        Map<String, Object> params = new HashMap<>();
        params.put("url", "https://nonexistent-test-host-xyz.com/api");
        params.put("method", "POST");
        params.put("body", "{\"key\": \"value\"}");

        Object result = httpRequestTool.execute(params);
        assertNotNull(result);
        // Should fail on connection, not on body parameter
        assertFalse(result.toString().contains("body"),
            "Should not reject body parameter. Got: " + result);
    }

    @Test
    @DisplayName("Should accept custom headers parameter")
    void testCustomHeaders() {
        Map<String, Object> params = new HashMap<>();
        params.put("url", "https://nonexistent-test-host-xyz.com/api");
        params.put("headers", Map.of("X-Custom-Header", "test-value", "Accept", "text/plain"));

        Object result = httpRequestTool.execute(params);
        assertNotNull(result);
        // Should fail on connection, not on headers
        assertFalse(result.toString().contains("header") && result.toString().contains("Error: Invalid"),
            "Should not reject custom headers");
    }

    @Test
    @DisplayName("Should accept auth token parameter")
    void testAuthToken() {
        Map<String, Object> params = new HashMap<>();
        params.put("url", "https://nonexistent-test-host-xyz.com/api");
        params.put("auth_token", "my-secret-token");

        Object result = httpRequestTool.execute(params);
        assertNotNull(result);
        // Should fail on connection, not on auth token
        assertFalse(result.toString().contains("auth") && result.toString().contains("Error: Invalid"),
            "Should not reject auth token parameter");
    }

    // ==================== Edge Cases ====================

    @Test
    @DisplayName("Should handle all parameters together")
    void testAllParametersCombined() {
        Map<String, Object> params = new HashMap<>();
        params.put("url", "https://nonexistent-test-host-xyz.com/api/resource");
        params.put("method", "PUT");
        params.put("body", "{\"update\": true}");
        params.put("headers", Map.of("Content-Type", "application/json"));
        params.put("auth_token", "bearer-token-123");

        Object result = httpRequestTool.execute(params);
        assertNotNull(result);
        // Should fail on connection, not on parameter validation
        assertTrue(result.toString().contains("Error"), "Should error on connection");
        assertTrue(result.toString().contains("HTTP request failed"),
            "Should be a connection error, not validation error. Got: " + result);
    }

    @Test
    @DisplayName("Should handle URL with query parameters")
    void testUrlWithQueryParams() {
        Map<String, Object> params = Map.of(
            "url", "https://nonexistent-test-host-xyz.com/api?key=value&page=1");

        Object result = httpRequestTool.execute(params);
        assertNotNull(result);
        // Should not error on URL validation
        assertFalse(result.toString().contains("Invalid URL"),
            "Should accept URL with query params. Got: " + result);
    }
}
