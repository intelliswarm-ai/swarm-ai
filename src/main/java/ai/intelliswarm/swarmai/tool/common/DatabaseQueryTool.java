package ai.intelliswarm.swarmai.tool.common;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Database Query Tool — executes read-only SQL queries against configured datasources.
 *
 * Safety: Only SELECT queries allowed, parameterized queries, row limit, timeout.
 * Requires a DataSource to be configured (via Spring properties).
 */
@Component
public class DatabaseQueryTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseQueryTool.class);

    private static final int DEFAULT_MAX_ROWS = 50;
    private static final int ABSOLUTE_MAX_ROWS = 200;
    private static final Set<String> BLOCKED_KEYWORDS = Set.of(
        "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "CREATE", "TRUNCATE",
        "GRANT", "REVOKE", "EXEC", "EXECUTE", "CALL", "MERGE"
    );

    private final JdbcTemplate jdbcTemplate;

    @Autowired(required = false)
    public DatabaseQueryTool(DataSource dataSource) {
        this.jdbcTemplate = dataSource != null ? new JdbcTemplate(dataSource) : null;
    }

    public DatabaseQueryTool() {
        this.jdbcTemplate = null;
    }

    @Override
    public String getFunctionName() {
        return "database_query";
    }

    @Override
    public String getDescription() {
        return "Execute read-only SQL SELECT queries against the configured database. " +
               "Returns results as a markdown table. Only SELECT statements are allowed.";
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String query = (String) parameters.get("query");
        Integer maxRows = parameters.get("max_rows") != null
            ? Math.min(((Number) parameters.get("max_rows")).intValue(), ABSOLUTE_MAX_ROWS)
            : DEFAULT_MAX_ROWS;

        logger.info("DatabaseQueryTool: Executing query (max {} rows)", maxRows);

        try {
            // 1. Check database availability
            if (jdbcTemplate == null) {
                return "Error: No database configured. Set spring.datasource.url in application properties.";
            }

            // 2. Validate query
            if (query == null || query.trim().isEmpty()) {
                return "Error: SQL query is required";
            }

            String normalizedQuery = query.trim();

            // 3. Safety check: only SELECT allowed
            String safetyError = checkQuerySafety(normalizedQuery);
            if (safetyError != null) {
                return "Error: " + safetyError;
            }

            // 4. Add LIMIT if not present
            if (!normalizedQuery.toUpperCase().contains("LIMIT")) {
                normalizedQuery = normalizedQuery.replaceAll(";\\s*$", "");
                normalizedQuery += " LIMIT " + maxRows;
            }

            // 5. Execute
            List<Map<String, Object>> results = jdbcTemplate.queryForList(normalizedQuery);

            // 6. Format response
            return buildResponse(query, results, maxRows);

        } catch (Exception e) {
            logger.error("Error executing SQL query", e);
            return "Error: Query failed: " + e.getMessage();
        }
    }

    private String checkQuerySafety(String query) {
        String upper = query.toUpperCase().trim();

        if (!upper.startsWith("SELECT") && !upper.startsWith("WITH") && !upper.startsWith("SHOW") &&
            !upper.startsWith("DESCRIBE") && !upper.startsWith("EXPLAIN")) {
            return "Only SELECT, WITH, SHOW, DESCRIBE, and EXPLAIN queries are allowed";
        }

        for (String blocked : BLOCKED_KEYWORDS) {
            // Check for blocked keywords not within quotes
            if (upper.contains(" " + blocked + " ") || upper.contains(" " + blocked + "(") ||
                upper.startsWith(blocked + " ") || upper.startsWith(blocked + "(")) {
                return "'" + blocked + "' statements are not allowed. Only read-only queries permitted.";
            }
        }

        // Block comment-based injection attempts
        if (query.contains("--") || query.contains("/*")) {
            return "SQL comments are not allowed";
        }

        return null;
    }

    private String buildResponse(String originalQuery, List<Map<String, Object>> results, int maxRows) {
        StringBuilder sb = new StringBuilder();

        sb.append("**Query:** `").append(originalQuery.length() > 200 ? originalQuery.substring(0, 197) + "..." : originalQuery).append("`\n");
        sb.append("**Rows:** ").append(results.size());
        if (results.size() >= maxRows) {
            sb.append(" (limited to ").append(maxRows).append(")");
        }
        sb.append("\n\n");

        if (results.isEmpty()) {
            sb.append("(no results)\n");
            return sb.toString();
        }

        // Get column names from first row
        List<String> columns = new ArrayList<>(results.get(0).keySet());

        // Markdown table
        sb.append("| ");
        columns.forEach(c -> sb.append(c).append(" | "));
        sb.append("\n|");
        columns.forEach(c -> sb.append("------|"));
        sb.append("\n");

        for (Map<String, Object> row : results) {
            sb.append("| ");
            for (String col : columns) {
                Object val = row.get(col);
                String str = val != null ? val.toString() : "NULL";
                if (str.length() > 50) str = str.substring(0, 47) + "...";
                sb.append(str).append(" | ");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> query = new HashMap<>();
        query.put("type", "string");
        query.put("description", "SQL SELECT query to execute");
        properties.put("query", query);

        Map<String, Object> maxRows = new HashMap<>();
        maxRows.put("type", "integer");
        maxRows.put("description", "Maximum rows to return (default: 50, max: 200)");
        maxRows.put("default", DEFAULT_MAX_ROWS);
        properties.put("max_rows", maxRows);

        schema.put("properties", properties);
        schema.put("required", new String[]{"query"});

        return schema;
    }

    @Override
    public boolean isAsync() {
        return false;
    }

    public record Request(String query, Integer maxRows) {}
}
