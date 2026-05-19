package ai.intelliswarm.swarmai.tool.code.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link WebFetchTool}.
 *
 * <p>Network calls aren't exercised here — that's deliberate. We test the
 * parameter handling, schema, and the pure-helper HTML-stripping logic via
 * the test-injectable static helpers. Integration with a real HTTP server is
 * tested separately in {@code WebFetchToolIntegrationTest} (if added).
 */
@DisplayName("WebFetchTool")
class WebFetchToolTest {

    private final WebFetchTool tool = new WebFetchTool();

    @Test
    @DisplayName("requires url parameter")
    void requiresUrl() {
        assertTrue(tool.execute(Map.of()).toString().contains("'url' is required"));
        assertTrue(tool.execute(Map.of("url", "")).toString().contains("'url' is required"));
    }

    @Test
    @DisplayName("rejects non-http schemes")
    void rejectsNonHttp() {
        Object out = tool.execute(Map.of("url", "ftp://example.com/x"));
        assertTrue(out.toString().contains("must start with http"), out.toString());
        Object f = tool.execute(Map.of("url", "file:///etc/passwd"));
        assertTrue(f.toString().contains("must start with http"), f.toString());
    }

    @Test
    @DisplayName("htmlToText strips script and style blocks")
    void stripsScriptAndStyle() {
        String html = "<html><body><script>alert('x');</script>"
                + "<style>body{color:red}</style>"
                + "<p>Hello world</p></body></html>";
        String text = WebFetchTool.htmlToText(html);
        assertFalse(text.contains("alert"), text);
        assertFalse(text.contains("color:red"), text);
        assertTrue(text.contains("Hello world"), text);
    }

    @Test
    @DisplayName("htmlToText strips tags and decodes common entities")
    void stripsTagsAndEntities() {
        String html = "<p>a &amp; b &lt; c &gt; d&nbsp;e &quot;f&quot; &#39;g&#39;</p>";
        String text = WebFetchTool.htmlToText(html);
        assertEquals("a & b < c > d e \"f\" 'g'", text);
    }

    @Test
    @DisplayName("htmlToText collapses excessive blank lines")
    void collapsesWhitespace() {
        String html = "<p>x</p>\n\n\n\n\n<p>y</p>";
        String text = WebFetchTool.htmlToText(html);
        assertEquals("x\n\ny", text);
    }

    @Test
    @DisplayName("isPlain classifies common text content types")
    void isPlainContentTypes() {
        assertTrue(WebFetchTool.isPlain("text/plain; charset=utf-8"));
        assertTrue(WebFetchTool.isPlain("application/json"));
        assertTrue(WebFetchTool.isPlain("text/markdown"));
        assertTrue(WebFetchTool.isPlain("application/yaml"));
        assertFalse(WebFetchTool.isPlain("text/html"));
        assertFalse(WebFetchTool.isPlain("image/png"));
    }

    @Test
    @DisplayName("schema declares url required, raw optional")
    void schemaShape() {
        Map<String, Object> schema = tool.getParameterSchema();
        assertEquals("object", schema.get("type"));
        Object[] required = (Object[]) schema.get("required");
        assertEquals(1, required.length);
        assertEquals("url", required[0]);
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertTrue(props.containsKey("url"));
        assertTrue(props.containsKey("raw"));
    }

    @Test
    @DisplayName("permission level is DANGEROUS — it makes outbound network calls")
    void permissionIsDangerous() {
        assertEquals(
                ai.intelliswarm.swarmai.tool.base.PermissionLevel.DANGEROUS,
                tool.getPermissionLevel());
    }
}
