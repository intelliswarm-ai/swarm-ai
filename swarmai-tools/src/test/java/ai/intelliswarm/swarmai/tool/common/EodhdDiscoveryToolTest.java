package ai.intelliswarm.swarmai.tool.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EodhdDiscoveryTool Unit Tests")
class EodhdDiscoveryToolTest {

    @Test
    @DisplayName("Tool metadata matches contract")
    void metadata() {
        EodhdDiscoveryTool tool = new EodhdDiscoveryTool();

        assertThat(tool.getFunctionName()).isEqualTo("eodhd_discovery");
        assertThat(tool.getCategory()).isEqualTo("finance");
        assertThat(tool.getTags()).contains("finance", "discovery", "screener", "search",
                "earnings", "ipos", "eodhd");
        assertThat(tool.isAsync()).isFalse();
    }

    @Test
    @DisplayName("Schema advertises required 'input' parameter")
    void parameterSchema() {
        EodhdDiscoveryTool tool = new EodhdDiscoveryTool();
        Map<String, Object> schema = tool.getParameterSchema();

        assertThat(schema.get("type")).isEqualTo("object");
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertThat(props).containsKey("input");
        Object required = schema.get("required");
        if (required instanceof List<?> list) {
            assertThat(list.toString()).contains("input");
        }
    }

    @Test
    @DisplayName("Without API key tool returns graceful unavailability stub")
    void noApiKeyStub() {
        EodhdDiscoveryTool tool = new EodhdDiscoveryTool();
        Object result = tool.execute(Map.of("input", "search:apple"));
        assertThat(result.toString())
                .contains("EODHD Discovery Unavailable")
                .contains("EODHD_API_KEY");
    }

    @Test
    @DisplayName("Blank input returns an error")
    void blankInputHandled() {
        EodhdDiscoveryTool tool = new EodhdDiscoveryTool();
        Object result = tool.execute(Map.of("input", ""));
        assertThat(result.toString().toLowerCase()).contains("error");
    }

    @Test
    @DisplayName("parseInput splits operation from args")
    void parseSearch() {
        EodhdDiscoveryTool.ParsedInput p = EodhdDiscoveryTool.parseInput("search:apple");
        assertThat(p.operation).isEqualTo("search");
        assertThat(p.args).containsExactly("apple");
    }

    @Test
    @DisplayName("parseInput captures multiple args")
    void parseEarningsRange() {
        EodhdDiscoveryTool.ParsedInput p = EodhdDiscoveryTool.parseInput(
                "earnings:2026-05-01:2026-05-31:AAPL.US,MSFT.US");
        assertThat(p.operation).isEqualTo("earnings");
        assertThat(p.args).containsExactly("2026-05-01", "2026-05-31", "AAPL.US,MSFT.US");
    }

    @Test
    @DisplayName("parseInput keeps JSON-array filter intact (does not split inside brackets)")
    void parseScreenerWithFilters() {
        EodhdDiscoveryTool.ParsedInput p = EodhdDiscoveryTool.parseInput(
                "screener:[[\"market_capitalization\",\">\",1000000000]]:market_capitalization.desc");
        assertThat(p.operation).isEqualTo("screener");
        assertThat(p.args).hasSize(2);
        assertThat(p.args.get(0)).isEqualTo("[[\"market_capitalization\",\">\",1000000000]]");
        assertThat(p.args.get(1)).isEqualTo("market_capitalization.desc");
    }

    @Test
    @DisplayName("parseInput handles operation-only input (no args)")
    void parseOperationOnly() {
        EodhdDiscoveryTool.ParsedInput p = EodhdDiscoveryTool.parseInput("ipos");
        assertThat(p.operation).isEqualTo("ipos");
        assertThat(p.args).isEmpty();
    }
}
