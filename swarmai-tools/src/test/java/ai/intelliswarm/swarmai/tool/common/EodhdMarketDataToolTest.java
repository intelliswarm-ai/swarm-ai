package ai.intelliswarm.swarmai.tool.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link EodhdMarketDataTool} — exercises metadata, schema, input parsing,
 * and the no-API-key stub path. Live API calls are out of scope here.
 */
@DisplayName("EodhdMarketDataTool Unit Tests")
class EodhdMarketDataToolTest {

    @Test
    @DisplayName("Tool metadata matches contract")
    void metadata() {
        EodhdMarketDataTool tool = new EodhdMarketDataTool();

        assertThat(tool.getFunctionName()).isEqualTo("eodhd_market_data");
        assertThat(tool.getDescription())
                .contains("EODHD")
                .contains("OHLCV")
                .contains("dividend");
        assertThat(tool.getCategory()).isEqualTo("finance");
        assertThat(tool.getTags())
                .contains("finance", "market-data", "ohlcv", "fundamentals", "eodhd");
        assertThat(tool.isAsync()).isFalse();
        assertThat(tool.getMaxResponseLength()).isGreaterThanOrEqualTo(10_000);
    }

    @Test
    @DisplayName("Schema advertises required 'input' parameter")
    void parameterSchema() {
        EodhdMarketDataTool tool = new EodhdMarketDataTool();
        Map<String, Object> schema = tool.getParameterSchema();

        assertThat(schema.get("type")).isEqualTo("object");
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertThat(props).containsKey("input");

        Object required = schema.get("required");
        if (required instanceof List<?> list) {
            assertThat(list.toString()).contains("input");
        } else if (required instanceof String[] arr) {
            assertThat(arr).contains("input");
        }
    }

    @Test
    @DisplayName("Blank input returns an error message (not a crash)")
    void blankInputHandled() {
        EodhdMarketDataTool tool = new EodhdMarketDataTool();
        Object result = tool.execute(Map.of("input", ""));
        assertThat(result.toString().toLowerCase()).contains("error");
    }

    @Test
    @DisplayName("Null input returns an error message (not a crash)")
    void nullInputHandled() {
        EodhdMarketDataTool tool = new EodhdMarketDataTool();
        java.util.HashMap<String, Object> params = new java.util.HashMap<>();
        params.put("input", null);
        Object result = tool.execute(params);
        assertThat(result.toString().toLowerCase()).contains("error");
    }

    @Test
    @DisplayName("Without API key tool returns graceful unavailability stub (not a crash)")
    void noApiKeyStub() {
        EodhdMarketDataTool tool = new EodhdMarketDataTool();
        Object result = tool.execute(Map.of("input", "AAPL"));
        assertThat(result.toString())
                .contains("EODHD Market Data Unavailable")
                .contains("EODHD_API_KEY")
                .contains("AAPL.US");
    }

    @Test
    @DisplayName("isHealthy reflects API key configuration")
    void health() {
        EodhdMarketDataTool tool = new EodhdMarketDataTool();
        // No key configured — tool reports unhealthy
        assertThat(tool.isHealthy()).isFalse();
    }

    @Test
    @DisplayName("parseInput defaults to .US suffix and 'all' endpoint")
    void parseInputBareSymbol() {
        EodhdMarketDataTool.ParsedInput p = EodhdMarketDataTool.parseInput("aapl");
        assertThat(p.symbol).isEqualTo("AAPL.US");
        assertThat(p.endpoint).isEqualTo("all");
        assertThat(p.fromIso).isNull();
        assertThat(p.toIso).isNull();
    }

    @Test
    @DisplayName("parseInput preserves explicit exchange suffix")
    void parseInputWithExchange() {
        EodhdMarketDataTool.ParsedInput p = EodhdMarketDataTool.parseInput("BMW.XETRA");
        assertThat(p.symbol).isEqualTo("BMW.XETRA");
        assertThat(p.endpoint).isEqualTo("all");
    }

    @Test
    @DisplayName("parseInput honors endpoint selector")
    void parseInputWithEndpoint() {
        EodhdMarketDataTool.ParsedInput p = EodhdMarketDataTool.parseInput("VOD.LSE:eod");
        assertThat(p.symbol).isEqualTo("VOD.LSE");
        assertThat(p.endpoint).isEqualTo("eod");
    }

    @Test
    @DisplayName("parseInput captures full date range")
    void parseInputWithDateRange() {
        EodhdMarketDataTool.ParsedInput p = EodhdMarketDataTool.parseInput("AAPL.US:eod:2024-01-01:2026-04-26");
        assertThat(p.symbol).isEqualTo("AAPL.US");
        assertThat(p.endpoint).isEqualTo("eod");
        assertThat(p.fromIso).isEqualTo("2024-01-01");
        assertThat(p.toIso).isEqualTo("2026-04-26");
    }

    @Test
    @DisplayName("parseInput intraday defaults to 5m interval")
    void parseInputIntradayDefault() {
        EodhdMarketDataTool.ParsedInput p = EodhdMarketDataTool.parseInput("AAPL.US:intraday");
        assertThat(p.symbol).isEqualTo("AAPL.US");
        assertThat(p.endpoint).isEqualTo("intraday");
        assertThat(p.interval).isEqualTo("5m");
    }

    @Test
    @DisplayName("parseInput intraday respects explicit interval")
    void parseInputIntradayExplicit() {
        EodhdMarketDataTool.ParsedInput p = EodhdMarketDataTool.parseInput("AAPL.US:intraday:1m");
        assertThat(p.endpoint).isEqualTo("intraday");
        assertThat(p.interval).isEqualTo("1m");
    }

    @Test
    @DisplayName("parseInput technical defaults to RSI period 14")
    void parseInputTechnicalDefault() {
        EodhdMarketDataTool.ParsedInput p = EodhdMarketDataTool.parseInput("AAPL.US:technical");
        assertThat(p.endpoint).isEqualTo("technical");
        assertThat(p.function).isEqualTo("rsi");
        assertThat(p.period).isEqualTo(14);
    }

    @Test
    @DisplayName("parseInput technical accepts function and period")
    void parseInputTechnicalExplicit() {
        EodhdMarketDataTool.ParsedInput p = EodhdMarketDataTool.parseInput("AAPL.US:technical:macd:26");
        assertThat(p.function).isEqualTo("macd");
        assertThat(p.period).isEqualTo(26);
    }

    @Test
    @DisplayName("parseInput macro keeps country code (no .US suffix)")
    void parseInputMacro() {
        EodhdMarketDataTool.ParsedInput p = EodhdMarketDataTool.parseInput("USA:macro:gdp_current_usd");
        assertThat(p.endpoint).isEqualTo("macro");
        assertThat(p.country).isEqualTo("USA");
        assertThat(p.indicator).isEqualTo("gdp_current_usd");
        // Symbol field mirrors the country for macro (no .US auto-append)
        assertThat(p.symbol).isEqualTo("USA");
    }
}
