package ai.intelliswarm.swarmai.tool.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("XMLParseTool Tests")
class XMLParseToolTest {

    private XMLParseTool xmlTool;

    private static final String SAMPLE_XML = """
        <catalog>
          <book id="1" category="fiction">
            <title>The Great Gatsby</title>
            <author>F. Scott Fitzgerald</author>
            <year>1925</year>
            <price>10.99</price>
          </book>
          <book id="2" category="science">
            <title>A Brief History of Time</title>
            <author>Stephen Hawking</author>
            <year>1988</year>
            <price>15.99</price>
          </book>
          <book id="3" category="fiction">
            <title>1984</title>
            <author>George Orwell</author>
            <year>1949</year>
            <price>9.99</price>
          </book>
        </catalog>
        """;

    @BeforeEach
    void setUp() {
        xmlTool = new XMLParseTool();
    }

    // ==================== Interface Tests ====================

    @Test
    @DisplayName("Should return correct function name")
    void testGetFunctionName() {
        assertEquals("xml_parse", xmlTool.getFunctionName());
    }

    @Test
    @DisplayName("Should return correct description")
    void testGetDescription() {
        assertNotNull(xmlTool.getDescription());
        assertTrue(xmlTool.getDescription().contains("XML"));
        assertTrue(xmlTool.getDescription().contains("XPath"));
    }

    @Test
    @DisplayName("Should not be async")
    void testIsAsync() {
        assertFalse(xmlTool.isAsync());
    }

    @Test
    @DisplayName("Should have correct parameter schema")
    void testGetParameterSchema() {
        Map<String, Object> schema = xmlTool.getParameterSchema();
        assertNotNull(schema);

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertTrue(properties.containsKey("xml_content"));
        assertTrue(properties.containsKey("path"));
        assertTrue(properties.containsKey("operation"));
        assertTrue(properties.containsKey("xpath"));
    }

    // ==================== Parse Operation ====================

    @Test
    @DisplayName("Should parse XML structure")
    void testParse() {
        Object result = xmlTool.execute(Map.of("xml_content", SAMPLE_XML));

        String r = result.toString();
        assertFalse(r.contains("Error"), "Should not error. Got: " + r);
        assertTrue(r.contains("catalog"), "Should show root element");
        assertTrue(r.contains("book"), "Should show child elements");
        assertTrue(r.contains("3"), "Should show 3 book children");
    }

    @Test
    @DisplayName("Should show root attributes")
    void testParseWithAttributes() {
        String xml = "<root version=\"1.0\" encoding=\"UTF-8\"><item>test</item></root>";
        Object result = xmlTool.execute(Map.of("xml_content", xml));

        String r = result.toString();
        assertTrue(r.contains("version"), "Should show attributes");
    }

    @Test
    @DisplayName("Should default to parse operation")
    void testDefaultOperation() {
        Object result = xmlTool.execute(Map.of("xml_content", "<root><item>test</item></root>"));
        assertTrue(result.toString().contains("XML Structure"), "Should default to parse");
    }

    // ==================== XPath Operation ====================

    @Test
    @DisplayName("Should query with XPath - select elements")
    void testXPathElements() {
        Map<String, Object> params = new HashMap<>();
        params.put("xml_content", SAMPLE_XML);
        params.put("operation", "xpath");
        params.put("xpath", "//book");

        Object result = xmlTool.execute(params);

        String r = result.toString();
        assertFalse(r.contains("Error"), "Should not error. Got: " + r);
        assertTrue(r.contains("Matches:** 3"), "Should find 3 books");
    }

    @Test
    @DisplayName("Should query with XPath - extract text")
    void testXPathText() {
        Map<String, Object> params = new HashMap<>();
        params.put("xml_content", SAMPLE_XML);
        params.put("operation", "xpath");
        params.put("xpath", "//book[@id='2']/title/text()");

        Object result = xmlTool.execute(params);

        String r = result.toString();
        assertFalse(r.contains("Error"), "Should not error. Got: " + r);
        assertTrue(r.contains("A Brief History of Time"), "Should extract title text");
    }

    @Test
    @DisplayName("Should query with XPath - filter by attribute")
    void testXPathAttribute() {
        Map<String, Object> params = new HashMap<>();
        params.put("xml_content", SAMPLE_XML);
        params.put("operation", "xpath");
        params.put("xpath", "//book[@category='fiction']/title");

        Object result = xmlTool.execute(params);

        String r = result.toString();
        assertTrue(r.contains("Matches:** 2"), "Should find 2 fiction books");
        assertTrue(r.contains("Great Gatsby") || r.contains("1984"), "Should contain fiction titles");
    }

    @Test
    @DisplayName("Should handle XPath with no matches")
    void testXPathNoMatches() {
        Map<String, Object> params = new HashMap<>();
        params.put("xml_content", SAMPLE_XML);
        params.put("operation", "xpath");
        params.put("xpath", "//nonexistent");

        Object result = xmlTool.execute(params);
        assertTrue(result.toString().contains("No matching"), "Should indicate no matches");
    }

    @Test
    @DisplayName("Should require xpath parameter")
    void testXPathMissing() {
        Map<String, Object> params = new HashMap<>();
        params.put("xml_content", SAMPLE_XML);
        params.put("operation", "xpath");

        Object result = xmlTool.execute(params);
        assertTrue(result.toString().contains("Error"), "Should error without xpath param");
    }

    // ==================== Elements Operation ====================

    @Test
    @DisplayName("Should list all element names with counts")
    void testElements() {
        Map<String, Object> params = new HashMap<>();
        params.put("xml_content", SAMPLE_XML);
        params.put("operation", "elements");

        Object result = xmlTool.execute(params);

        String r = result.toString();
        assertFalse(r.contains("Error"), "Should not error");
        assertTrue(r.contains("book"), "Should list book element");
        assertTrue(r.contains("title"), "Should list title element");
        assertTrue(r.contains("author"), "Should list author element");
        assertTrue(r.contains("3"), "Should show count of 3 for book");
    }

    // ==================== Text Operation ====================

    @Test
    @DisplayName("Should extract all text content")
    void testText() {
        Map<String, Object> params = new HashMap<>();
        params.put("xml_content", SAMPLE_XML);
        params.put("operation", "text");

        Object result = xmlTool.execute(params);

        String r = result.toString();
        assertFalse(r.contains("Error"), "Should not error");
        assertTrue(r.contains("The Great Gatsby"), "Should extract text");
        assertTrue(r.contains("Stephen Hawking"), "Should extract all authors");
        assertTrue(r.contains("1925"), "Should extract years");
    }

    // ==================== Error Handling ====================

    @Test
    @DisplayName("Should handle missing input")
    void testMissingInput() {
        Object result = xmlTool.execute(Map.of("operation", "parse"));
        assertTrue(result.toString().contains("Error"), "Should error without input");
    }

    @Test
    @DisplayName("Should handle invalid XML")
    void testInvalidXml() {
        Object result = xmlTool.execute(Map.of("xml_content", "not xml at all"));
        assertTrue(result.toString().contains("Error"), "Should error on invalid XML");
    }

    @Test
    @DisplayName("Should handle invalid operation")
    void testInvalidOperation() {
        Map<String, Object> params = new HashMap<>();
        params.put("xml_content", SAMPLE_XML);
        params.put("operation", "invalid");

        Object result = xmlTool.execute(params);
        assertTrue(result.toString().contains("Error"), "Should error on invalid operation");
    }

    @Test
    @DisplayName("Should prevent path traversal")
    void testPathTraversal() {
        Object result = xmlTool.execute(Map.of("path", "../../etc/passwd"));
        assertTrue(result.toString().contains("Error"), "Should block traversal");
    }

    @Test
    @DisplayName("Should prevent XXE attacks")
    void testXXEPrevention() {
        String xxeXml = """
            <?xml version="1.0"?>
            <!DOCTYPE foo [
              <!ENTITY xxe SYSTEM "file:///etc/passwd">
            ]>
            <root>&xxe;</root>
            """;

        Object result = xmlTool.execute(Map.of("xml_content", xxeXml));
        assertTrue(result.toString().contains("Error"), "Should block XXE. Got: " + result);
    }

    // ==================== Edge Cases ====================

    @Test
    @DisplayName("Should handle minimal XML")
    void testMinimalXml() {
        Object result = xmlTool.execute(Map.of("xml_content", "<root/>"));

        String r = result.toString();
        assertFalse(r.contains("Error"), "Should handle minimal XML");
        assertTrue(r.contains("root"), "Should show root element");
    }

    @Test
    @DisplayName("Should handle XML with namespaces")
    void testNamespaces() {
        String nsXml = "<root xmlns:ns=\"http://example.com\"><ns:item>value</ns:item></root>";
        Object result = xmlTool.execute(Map.of("xml_content", nsXml));

        String r = result.toString();
        assertFalse(r.contains("Error"), "Should handle namespaces. Got: " + r);
    }
}
