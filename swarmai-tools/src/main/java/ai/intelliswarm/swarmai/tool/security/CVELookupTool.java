package ai.intelliswarm.swarmai.tool.security;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Looks up vulnerabilities from the NIST National Vulnerability Database (NVD).
 * Queries the CVE 2.0 API by keyword, CVE ID, or CPE name.
 *
 * <p>Permission: READ_ONLY (no side effects, public API).
 */
public class CVELookupTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(CVELookupTool.class);
    private static final String NVD_API = "https://services.nvd.nist.gov/rest/json/cves/2.0";
    private static final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public String getFunctionName() { return "cve_lookup"; }

    @Override
    public String getDescription() {
        return "Look up known vulnerabilities from the NIST NVD database. " +
               "Provide a 'query' (keyword or CVE ID like 'CVE-2021-44228') and optional 'maxResults' (default 5). " +
               "Returns CVE ID, description, severity, CVSS score, and affected products.";
    }

    @Override
    public PermissionLevel getPermissionLevel() { return PermissionLevel.READ_ONLY; }

    @Override
    public boolean isAsync() { return false; }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
                "query", Map.of("type", "string", "description", "CVE ID or keyword to search"),
                "maxResults", Map.of("type", "integer", "description", "Max results (default 5)")
        );
    }

    @Override
    public String execute(Map<String, Object> params) {
        String query = (String) params.getOrDefault("query", "");
        int maxResults = params.containsKey("maxResults")
                ? ((Number) params.get("maxResults")).intValue() : 5;

        if (query == null || query.isBlank()) {
            return "Error: 'query' parameter is required";
        }

        logger.info("CVELookupTool: searching NVD for '{}'", query);

        try {
            String url;
            if (query.toUpperCase().startsWith("CVE-")) {
                url = NVD_API + "?cveId=" + query;
            } else {
                url = NVD_API + "?keywordSearch=" + query + "&resultsPerPage=" + maxResults;
            }

            String response = restTemplate.getForObject(url, String.class);
            if (response == null) return "No results from NVD API";

            JsonNode root = mapper.readTree(response);
            JsonNode vulnerabilities = root.path("vulnerabilities");

            if (!vulnerabilities.isArray() || vulnerabilities.isEmpty()) {
                return "No CVEs found for: " + query;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Found ").append(vulnerabilities.size()).append(" CVE(s):\n\n");

            int count = 0;
            for (JsonNode vuln : vulnerabilities) {
                if (count >= maxResults) break;
                JsonNode cve = vuln.path("cve");
                String id = cve.path("id").asText("unknown");
                String desc = "";
                JsonNode descriptions = cve.path("descriptions");
                for (JsonNode d : descriptions) {
                    if ("en".equals(d.path("lang").asText())) {
                        desc = d.path("value").asText("");
                        break;
                    }
                }
                if (desc.length() > 300) desc = desc.substring(0, 300) + "...";

                // CVSS score
                String severity = "UNKNOWN";
                double cvss = 0;
                JsonNode metrics = cve.path("metrics");
                if (metrics.has("cvssMetricV31")) {
                    JsonNode cvssData = metrics.path("cvssMetricV31").get(0).path("cvssData");
                    cvss = cvssData.path("baseScore").asDouble(0);
                    severity = cvssData.path("baseSeverity").asText("UNKNOWN");
                }

                sb.append("### ").append(id).append("\n");
                sb.append("Severity: ").append(severity).append(" (CVSS ").append(cvss).append(")\n");
                sb.append("Description: ").append(desc).append("\n\n");
                count++;
            }

            return sb.toString();

        } catch (Exception e) {
            logger.error("CVE lookup failed: {}", e.getMessage());
            return "CVE lookup error: " + e.getMessage();
        }
    }
}
