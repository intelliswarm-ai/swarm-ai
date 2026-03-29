package ai.intelliswarm.swarmai.tool.common;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PDFReadTool Tests")
class PDFReadToolTest {

    private PDFReadTool pdfReadTool;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        pdfReadTool = new PDFReadTool(tempDir);
    }

    private Path createTestPDF(String filename, String... pageTexts) throws IOException {
        Path pdfPath = tempDir.resolve(filename);
        try (PDDocument doc = new PDDocument()) {
            PDDocumentInformation info = new PDDocumentInformation();
            info.setTitle("Test Document");
            info.setAuthor("SwarmAI Test");
            doc.setDocumentInformation(info);

            for (String text : pageTexts) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream contentStream = new PDPageContentStream(doc, page)) {
                    contentStream.beginText();
                    contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    contentStream.newLineAtOffset(50, 700);
                    contentStream.showText(text);
                    contentStream.endText();
                }
            }
            doc.save(pdfPath.toFile());
        }
        return pdfPath;
    }

    // ==================== Interface Tests ====================

    @Test
    @DisplayName("Should return correct function name")
    void testGetFunctionName() {
        assertEquals("pdf_read", pdfReadTool.getFunctionName());
    }

    @Test
    @DisplayName("Should return correct description")
    void testGetDescription() {
        String description = pdfReadTool.getDescription();
        assertNotNull(description);
        assertTrue(description.contains("PDF"), "Description should mention PDF");
        assertTrue(description.contains("text"), "Description should mention text extraction");
    }

    @Test
    @DisplayName("Should not be async")
    void testIsAsync() {
        assertFalse(pdfReadTool.isAsync());
    }

    @Test
    @DisplayName("Should have correct parameter schema")
    void testGetParameterSchema() {
        Map<String, Object> schema = pdfReadTool.getParameterSchema();
        assertNotNull(schema);
        assertEquals("object", schema.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertTrue(properties.containsKey("path"));
        assertTrue(properties.containsKey("start_page"));
        assertTrue(properties.containsKey("end_page"));

        String[] required = (String[]) schema.get("required");
        assertEquals(1, required.length);
        assertEquals("path", required[0]);
    }

    // ==================== Reading Tests ====================

    @Test
    @DisplayName("Should read a single-page PDF")
    void testReadSinglePagePDF() throws IOException {
        createTestPDF("single.pdf", "Hello from page one!");

        Object result = pdfReadTool.execute(Map.of("path", "single.pdf"));

        assertNotNull(result);
        String resultStr = result.toString();
        assertFalse(resultStr.contains("Error"), "Should not error. Got: " + resultStr);
        assertTrue(resultStr.contains("Hello from page one!"), "Should contain page text");
        assertTrue(resultStr.contains("**Pages:** 1"), "Should show page count");
        assertTrue(resultStr.contains("**File:**"), "Should include metadata");
    }

    @Test
    @DisplayName("Should read a multi-page PDF")
    void testReadMultiPagePDF() throws IOException {
        createTestPDF("multi.pdf", "Page 1 content", "Page 2 content", "Page 3 content");

        Object result = pdfReadTool.execute(Map.of("path", "multi.pdf"));

        String resultStr = result.toString();
        assertFalse(resultStr.contains("Error"), "Should not error");
        assertTrue(resultStr.contains("Page 1 content"), "Should contain page 1");
        assertTrue(resultStr.contains("Page 2 content"), "Should contain page 2");
        assertTrue(resultStr.contains("Page 3 content"), "Should contain page 3");
        assertTrue(resultStr.contains("**Pages:** 3"), "Should show 3 pages");
    }

    @Test
    @DisplayName("Should extract metadata")
    void testExtractMetadata() throws IOException {
        createTestPDF("metadata.pdf", "Content here");

        Object result = pdfReadTool.execute(Map.of("path", "metadata.pdf"));

        String resultStr = result.toString();
        assertTrue(resultStr.contains("**Title:** Test Document"), "Should extract title");
        assertTrue(resultStr.contains("**Author:** SwarmAI Test"), "Should extract author");
    }

    // ==================== Page Range Tests ====================

    @Test
    @DisplayName("Should read specific page range")
    void testReadPageRange() throws IOException {
        createTestPDF("range.pdf", "Page A", "Page B", "Page C", "Page D");

        Map<String, Object> params = new HashMap<>();
        params.put("path", "range.pdf");
        params.put("start_page", 2);
        params.put("end_page", 3);

        Object result = pdfReadTool.execute(params);

        String resultStr = result.toString();
        assertFalse(resultStr.contains("Error"), "Should not error");
        assertTrue(resultStr.contains("Page B"), "Should contain page 2");
        assertTrue(resultStr.contains("Page C"), "Should contain page 3");
        assertTrue(resultStr.contains("showing 2-3"), "Should indicate page range");
    }

    @Test
    @DisplayName("Should handle start_page beyond total pages")
    void testStartPageBeyondTotal() throws IOException {
        createTestPDF("short.pdf", "Only page");

        Map<String, Object> params = new HashMap<>();
        params.put("path", "short.pdf");
        params.put("start_page", 99);

        Object result = pdfReadTool.execute(params);

        String resultStr = result.toString();
        assertTrue(resultStr.contains("Error"), "Should error when start exceeds total");
        assertTrue(resultStr.contains("exceeds"), "Should mention exceeds");
    }

    // ==================== Error Handling ====================

    @Test
    @DisplayName("Should handle null path")
    void testNullPath() {
        Map<String, Object> params = new HashMap<>();
        params.put("path", null);

        Object result = pdfReadTool.execute(params);
        assertTrue(result.toString().contains("Error"), "Should error on null path");
    }

    @Test
    @DisplayName("Should handle empty path")
    void testEmptyPath() {
        Object result = pdfReadTool.execute(Map.of("path", ""));
        assertTrue(result.toString().contains("Error"), "Should error on empty path");
    }

    @Test
    @DisplayName("Should handle non-existent file")
    void testNonExistentFile() {
        Object result = pdfReadTool.execute(Map.of("path", "missing.pdf"));
        assertTrue(result.toString().contains("Error"), "Should error on missing file");
        assertTrue(result.toString().contains("not found"), "Should mention not found");
    }

    @Test
    @DisplayName("Should handle non-PDF file gracefully")
    void testNonPDFFile() throws IOException {
        Path textFile = tempDir.resolve("notpdf.pdf");
        Files.writeString(textFile, "This is not a real PDF");

        Object result = pdfReadTool.execute(Map.of("path", "notpdf.pdf"));
        assertTrue(result.toString().contains("Error"), "Should error on invalid PDF");
    }

    @Test
    @DisplayName("Should prevent path traversal")
    void testPathTraversal() {
        Object result = pdfReadTool.execute(Map.of("path", "../../etc/passwd.pdf"));
        assertTrue(result.toString().contains("Error"), "Should block path traversal");
        assertTrue(result.toString().contains("denied"), "Should mention access denied");
    }

    // ==================== Edge Cases ====================

    @Test
    @DisplayName("Should handle empty PDF (no pages)")
    void testEmptyPDF() throws IOException {
        Path pdfPath = tempDir.resolve("empty.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.save(pdfPath.toFile());
        }

        Object result = pdfReadTool.execute(Map.of("path", "empty.pdf"));

        String resultStr = result.toString();
        assertFalse(resultStr.startsWith("Error"), "Should not hard-error on empty PDF");
        assertTrue(resultStr.contains("**Pages:** 0") || resultStr.contains("No extractable text"),
            "Should indicate no content");
    }

    @Test
    @DisplayName("Should include file size in metadata")
    void testFileSizeMetadata() throws IOException {
        createTestPDF("sized.pdf", "Content");

        Object result = pdfReadTool.execute(Map.of("path", "sized.pdf"));

        String resultStr = result.toString();
        assertTrue(resultStr.contains("**Size:**"), "Should include file size");
    }
}
