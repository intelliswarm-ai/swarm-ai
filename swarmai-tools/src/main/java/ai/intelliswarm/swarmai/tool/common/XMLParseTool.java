package ai.intelliswarm.swarmai.tool.common;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.*;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * XML Parse Tool — parses XML documents and extracts data via XPath queries.
 *
 * Operations:
 * - parse: Show structure overview (root element, namespaces, child count)
 * - xpath: Evaluate an XPath expression and return matching nodes/values
 * - elements: List all element names with counts
 * - text: Extract all text content
 */
@Component
public class XMLParseTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(XMLParseTool.class);
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;
    private static final Set<String> VALID_OPERATIONS = Set.of("parse", "xpath", "elements", "text");

    @Override
    public String getFunctionName() {
        return "xml_parse";
    }

    @Override
    public String getDescription() {
        return "Parse XML documents and extract data. Operations: 'parse' (structure overview), " +
               "'xpath' (query with XPath expression), 'elements' (list element names), 'text' (extract all text).";
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String xmlContent = (String) parameters.get("xml_content");
        String pathStr = (String) parameters.get("path");
        String operation = ((String) parameters.getOrDefault("operation", "parse")).toLowerCase();
        String xpath = (String) parameters.getOrDefault("xpath", null);

        logger.info("XMLParseTool: operation={}, path={}", operation, pathStr);

        try {
            if (!VALID_OPERATIONS.contains(operation)) {
                return "Error: Invalid operation: '" + operation + "'. Valid: " + VALID_OPERATIONS;
            }

            // Get XML content
            String content;
            if (xmlContent != null && !xmlContent.trim().isEmpty()) {
                content = xmlContent;
            } else if (pathStr != null && !pathStr.trim().isEmpty()) {
                if (pathStr.contains("..")) return "Error: Access denied: path traversal not allowed";
                Path filePath = Paths.get(pathStr.trim()).normalize();
                if (!Files.exists(filePath)) return "Error: File not found: " + pathStr;
                if (Files.size(filePath) > MAX_FILE_SIZE) return "Error: File too large (max 10 MB)";
                content = Files.readString(filePath);
            } else {
                return "Error: Either 'xml_content' or 'path' is required";
            }

            // Parse XML
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); // XXE prevention
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(content)));
            doc.getDocumentElement().normalize();

            return switch (operation) {
                case "parse" -> executeParse(doc);
                case "xpath" -> executeXPath(doc, xpath, content);
                case "elements" -> executeElements(doc);
                case "text" -> executeText(doc);
                default -> "Error: Unknown operation";
            };

        } catch (org.xml.sax.SAXParseException e) {
            return "Error: Invalid XML: " + e.getMessage();
        } catch (Exception e) {
            logger.error("Error parsing XML", e);
            return "Error: " + e.getMessage();
        }
    }

    private String executeParse(Document doc) {
        Element root = doc.getDocumentElement();
        StringBuilder sb = new StringBuilder();

        sb.append("**XML Structure**\n");
        sb.append("**Root Element:** ").append(root.getTagName()).append("\n");

        if (root.getNamespaceURI() != null) {
            sb.append("**Namespace:** ").append(root.getNamespaceURI()).append("\n");
        }

        // Attributes on root
        NamedNodeMap attrs = root.getAttributes();
        if (attrs.getLength() > 0) {
            sb.append("**Root Attributes:** ");
            for (int i = 0; i < attrs.getLength(); i++) {
                Attr attr = (Attr) attrs.item(i);
                sb.append(attr.getName()).append("=\"").append(attr.getValue()).append("\" ");
            }
            sb.append("\n");
        }

        // Direct children summary
        Map<String, Integer> childCounts = new LinkedHashMap<>();
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                String name = children.item(i).getNodeName();
                childCounts.merge(name, 1, Integer::sum);
            }
        }

        sb.append("**Direct Children:** ").append(childCounts.values().stream().mapToInt(i -> i).sum()).append("\n\n");

        if (!childCounts.isEmpty()) {
            sb.append("| Element | Count |\n|---------|-------|\n");
            childCounts.forEach((name, count) -> sb.append("| ").append(name).append(" | ").append(count).append(" |\n"));
        }

        // Show first child's structure as sample
        NodeList firstChildren = root.getChildNodes();
        for (int i = 0; i < firstChildren.getLength(); i++) {
            if (firstChildren.item(i).getNodeType() == Node.ELEMENT_NODE) {
                sb.append("\n**Sample child structure (`").append(firstChildren.item(i).getNodeName()).append("`):**\n```xml\n");
                sb.append(nodeToString((Element) firstChildren.item(i), 0, 3));
                sb.append("```\n");
                break;
            }
        }

        return sb.toString();
    }

    private String executeXPath(Document doc, String xpathExpr, String content) {
        if (xpathExpr == null || xpathExpr.trim().isEmpty()) {
            return "Error: 'xpath' parameter is required for xpath operation (e.g., '//item', '/root/name/text()')";
        }

        try {
            XPathFactory xPathFactory = XPathFactory.newInstance();
            XPath xPath = xPathFactory.newXPath();

            // Try as NodeList first
            try {
                XPathExpression expr = xPath.compile(xpathExpr);
                NodeList nodes = (NodeList) expr.evaluate(doc, XPathConstants.NODESET);

                StringBuilder sb = new StringBuilder();
                sb.append("**XPath:** `").append(xpathExpr).append("`\n");
                sb.append("**Matches:** ").append(nodes.getLength()).append("\n\n");

                if (nodes.getLength() == 0) {
                    sb.append("No matching nodes found.\n");
                    return sb.toString();
                }

                for (int i = 0; i < Math.min(nodes.getLength(), 20); i++) {
                    Node node = nodes.item(i);
                    sb.append("**[").append(i + 1).append("]** ");

                    if (node.getNodeType() == Node.ELEMENT_NODE) {
                        sb.append("`<").append(node.getNodeName()).append(">`\n");
                        sb.append(nodeToString((Element) node, 0, 2)).append("\n");
                    } else if (node.getNodeType() == Node.ATTRIBUTE_NODE) {
                        sb.append("@").append(node.getNodeName()).append(" = ").append(node.getNodeValue()).append("\n");
                    } else {
                        sb.append(node.getTextContent().trim()).append("\n");
                    }
                }

                if (nodes.getLength() > 20) {
                    sb.append("\n[... ").append(nodes.getLength() - 20).append(" more results ...]\n");
                }

                return sb.toString();
            } catch (XPathExpressionException e) {
                // Try as string value
                String result = xPath.evaluate(xpathExpr, doc);
                return "**XPath:** `" + xpathExpr + "`\n**Result:** " + result + "\n";
            }

        } catch (XPathExpressionException e) {
            return "Error: Invalid XPath expression: " + e.getMessage();
        }
    }

    private String executeElements(Document doc) {
        Map<String, Integer> elementCounts = new TreeMap<>();
        countElements(doc.getDocumentElement(), elementCounts);

        StringBuilder sb = new StringBuilder();
        sb.append("**XML Elements** (").append(elementCounts.size()).append(" unique)\n\n");
        sb.append("| Element | Count |\n|---------|-------|\n");

        elementCounts.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .forEach(e -> sb.append("| ").append(e.getKey()).append(" | ").append(e.getValue()).append(" |\n"));

        return sb.toString();
    }

    private String executeText(Document doc) {
        StringBuilder sb = new StringBuilder();
        sb.append("**XML Text Content:**\n\n");

        extractText(doc.getDocumentElement(), sb, 0);

        String result = sb.toString();
        if (result.length() > getMaxResponseLength()) {
            result = result.substring(0, getMaxResponseLength()) + "\n\n[... truncated ...]";
        }
        return result;
    }

    private void countElements(Element element, Map<String, Integer> counts) {
        counts.merge(element.getTagName(), 1, Integer::sum);
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                countElements((Element) children.item(i), counts);
            }
        }
    }

    private void extractText(Element element, StringBuilder sb, int depth) {
        String text = element.getTextContent();
        if (text != null && !text.trim().isEmpty()) {
            // Only add leaf text (not text that's just aggregation of children)
            boolean hasElementChildren = false;
            NodeList children = element.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                    hasElementChildren = true;
                    break;
                }
            }

            if (!hasElementChildren) {
                sb.append("  ".repeat(depth)).append("**").append(element.getTagName()).append(":** ")
                  .append(text.trim()).append("\n");
            } else {
                for (int i = 0; i < children.getLength(); i++) {
                    if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                        extractText((Element) children.item(i), sb, depth + 1);
                    }
                }
            }
        }
    }

    private String nodeToString(Element element, int depth, int maxDepth) {
        if (depth > maxDepth) return "  ".repeat(depth) + "...\n";

        StringBuilder sb = new StringBuilder();
        String indent = "  ".repeat(depth);

        sb.append(indent).append("<").append(element.getTagName());
        NamedNodeMap attrs = element.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr attr = (Attr) attrs.item(i);
            sb.append(" ").append(attr.getName()).append("=\"").append(attr.getValue()).append("\"");
        }

        NodeList children = element.getChildNodes();
        boolean hasElements = false;
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE) { hasElements = true; break; }
        }

        if (!hasElements) {
            String text = element.getTextContent().trim();
            if (text.isEmpty()) {
                sb.append("/>\n");
            } else {
                sb.append(">").append(text.length() > 60 ? text.substring(0, 57) + "..." : text)
                  .append("</").append(element.getTagName()).append(">\n");
            }
        } else {
            sb.append(">\n");
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                    sb.append(nodeToString((Element) children.item(i), depth + 1, maxDepth));
                }
            }
            sb.append(indent).append("</").append(element.getTagName()).append(">\n");
        }

        return sb.toString();
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        properties.put("xml_content", Map.of("type", "string", "description", "XML content as string"));
        properties.put("path", Map.of("type", "string", "description", "Path to XML file"));
        properties.put("operation", Map.of("type", "string", "description", "Operation: parse, xpath, elements, text (default: parse)",
            "default", "parse", "enum", List.of("parse", "xpath", "elements", "text")));
        properties.put("xpath", Map.of("type", "string", "description", "XPath expression for xpath operation"));

        schema.put("properties", properties);
        schema.put("required", new String[]{});
        return schema;
    }

    @Override
    public boolean isAsync() { return false; }

    // ==================== Tool Routing Metadata ====================

    @Override
    public String getTriggerWhen() {
        return "User needs to parse XML, run XPath queries, or extract data from XML documents.";
    }

    @Override
    public String getAvoidWhen() {
        return "Data is JSON, CSV, or plain text.";
    }

    @Override
    public String getCategory() {
        return "data-io";
    }

    @Override
    public List<String> getTags() {
        return List.of("xml", "xpath", "parse", "extract");
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return Map.of(
            "type", "markdown",
            "description", "XML structure overview, XPath query results, element listings, or extracted text depending on operation"
        );
    }

    @Override
    public int getMaxResponseLength() { return 12000; }

    public record Request(String xmlContent, String path, String operation, String xpath) {}
}
