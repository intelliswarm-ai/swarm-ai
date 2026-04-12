package ai.intelliswarm.swarmai.tool.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link FinancialDataTool} — exercise the tool's schema, metadata,
 * and the no-API-key stub path. Live API calls are in {@link FinancialDataToolIntegrationTest}.
 */
@DisplayName("FinancialDataTool Unit Tests")
class FinancialDataToolTest {

    @Test
    @DisplayName("Tool metadata matches contract")
    void metadata() {
        FinancialDataTool tool = new FinancialDataTool();

        assertThat(tool.getFunctionName()).isEqualTo("financial_data");
        assertThat(tool.getDescription())
                .contains("income statement")
                .contains("balance sheet")
                .contains("insider");
        assertThat(tool.getCategory()).isEqualTo("finance");
        assertThat(tool.getTags())
                .contains("finance", "income-statement", "balance-sheet", "insider", "finnhub");
        assertThat(tool.isAsync()).isFalse();
        assertThat(tool.getMaxResponseLength()).isGreaterThanOrEqualTo(10000);
    }

    @Test
    @DisplayName("Schema advertises required 'input' parameter")
    void parameterSchema() {
        FinancialDataTool tool = new FinancialDataTool();
        Map<String, Object> schema = tool.getParameterSchema();

        assertThat(schema.get("type")).isEqualTo("object");
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertThat(props).containsKey("input");

        Object required = schema.get("required");
        // required might be List or String[] — accept either
        if (required instanceof List<?> list) {
            assertThat(list.toString()).contains("input");
        } else if (required instanceof String[] arr) {
            assertThat(arr).contains("input");
        }
    }

    @Test
    @DisplayName("Blank input returns an error message (not a crash)")
    void blankInputHandled() {
        FinancialDataTool tool = new FinancialDataTool();
        Object result = tool.execute(Map.of("input", ""));
        assertThat(result.toString().toLowerCase()).contains("error");
    }

    @Test
    @DisplayName("Without API key tool returns graceful unavailability stub (not a crash)")
    void noApiKeyStub() {
        FinancialDataTool tool = new FinancialDataTool();
        // No key injected — @Value default is empty string
        Object result = tool.execute(Map.of("input", "AAPL"));
        assertThat(result.toString())
                .contains("Financial Data Unavailable")
                .contains("FINNHUB_API_KEY");
    }

    @Test
    @DisplayName("Ticker with colon suffix is normalized (compat with sec_filings input format)")
    void tickerWithColonSuffix() {
        FinancialDataTool tool = new FinancialDataTool();
        Object result = tool.execute(Map.of("input", "AAPL:revenue trends"));
        // Should not crash; should report about AAPL (not an unknown ticker)
        assertThat(result.toString()).doesNotContain("null");
    }

    @Test
    @DisplayName("isHealthy reflects API key configuration")
    void health() {
        FinancialDataTool tool = new FinancialDataTool();
        // Without a key configured the tool reports unhealthy
        assertThat(tool.isHealthy()).isFalse();
    }
}
