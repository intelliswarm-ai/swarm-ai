package ai.intelliswarm.swarmai.tool.security;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Looks up vulnerabilities from the Open Source Vulnerabilities (OSV) database.
 * Queries by package name and ecosystem (npm, PyPI, Maven, etc.).
 *
 * <p>Permission: READ_ONLY.
 */
public class OSVLookupTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(OSVLookupTool.class);
    private static final String OSV_API = "https://api.osv.dev/v1/query";
    private static final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public String getFunctionName() { return "osv_lookup"; }

    @Override
    public String getDescription() {
        return "Look up known vulnerabilities for an open-source package from the OSV database. " +
               "Provide 'package' (e.g. 'log4j-core'), 'ecosystem' (e.g. 'Maven', 'npm', 'PyPI'), " +
               "and optional 'version'. Returns vulnerability IDs, severity, and affected versions.";
    }

    @Override
    public PermissionLevel getPermissionLevel() { return PermissionLevel.READ_ONLY; }

    @Override
    public boolean isAsync() { return false; }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
                "package", Map.of("type", "string", "description", "Package name"),
                "ecosystem", Map.of("type", "string", "description", "Ecosystem: Maven, npm, PyPI, Go, etc."),
                "version", Map.of("type", "string", "description", "Specific version (optional)")
        );
    }

    @Override
    public String execute(Map<String, Object> params) {
        String pkg = (String) params.getOrDefault("package", "");
        String ecosystem = (String) params.getOrDefault("ecosystem", "");
        String version = (String) params.getOrDefault("version", "");

        if (pkg.isBlank() || ecosystem.isBlank()) {
            return "Error: 'package' and 'ecosystem' parameters are required";
        }

        logger.info("OSVLookupTool: querying OSV for {}:{} {}", ecosystem, pkg, version);

        try {
            Map<String, Object> body = new HashMap<>();
            Map<String, String> pkgInfo = new HashMap<>();
            pkgInfo.put("name", pkg);
            pkgInfo.put("ecosystem", ecosystem);
            body.put("package", pkgInfo);
            if (!version.isBlank()) {
                body.put("version", version);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> request = new HttpEntity<>(mapper.writeValueAsString(body), headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    OSV_API, HttpMethod.POST, request, String.class);

            if (response.getBody() == null) return "No results from OSV API";

            JsonNode root = mapper.readTree(response.getBody());
            JsonNode vulns = root.path("vulns");

            if (!vulns.isArray() || vulns.isEmpty()) {
                return "No vulnerabilities found for " + ecosystem + ":" + pkg;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Found ").append(vulns.size()).append(" vulnerability(ies) for ")
              .append(ecosystem).append(":").append(pkg).append(":\n\n");

            int count = 0;
            for (JsonNode v : vulns) {
                if (count >= 10) { sb.append("... and more\n"); break; }
                String id = v.path("id").asText();
                String summary = v.path("summary").asText("No summary");
                if (summary.length() > 200) summary = summary.substring(0, 200) + "...";

                String severity = "UNKNOWN";
                JsonNode severityNode = v.path("database_specific").path("severity");
                if (!severityNode.isMissingNode()) severity = severityNode.asText();

                sb.append("### ").append(id).append("\n");
                sb.append("Severity: ").append(severity).append("\n");
                sb.append("Summary: ").append(summary).append("\n");

                // Affected versions
                JsonNode affected = v.path("affected");
                if (affected.isArray() && !affected.isEmpty()) {
                    JsonNode ranges = affected.get(0).path("ranges");
                    if (ranges.isArray() && !ranges.isEmpty()) {
                        JsonNode events = ranges.get(0).path("events");
                        if (events.isArray()) {
                            sb.append("Affected: ");
                            for (JsonNode e : events) {
                                if (e.has("introduced")) sb.append("introduced ").append(e.get("introduced").asText());
                                if (e.has("fixed")) sb.append(", fixed ").append(e.get("fixed").asText());
                            }
                            sb.append("\n");
                        }
                    }
                }
                sb.append("\n");
                count++;
            }

            return sb.toString();

        } catch (Exception e) {
            logger.error("OSV lookup failed: {}", e.getMessage());
            return "OSV lookup error: " + e.getMessage();
        }
    }
}
