package ai.intelliswarm.swarmai.tool.common.eodhd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EodhdDiscoveryFormatter Unit Tests")
class EodhdDiscoveryFormatterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("formatTrends flattens nested trends arrays and renders earnings-trend fields")
    void formatTrendsFlattensAndUsesEarningsFields() throws Exception {
        String json = """
                {
                  "trends": [
                    [
                      {
                        "date": "2026-01-31",
                        "code": "AAPL.US",
                        "period": "Q1 2026",
                        "earningsEstimateAvg": 1.23,
                        "epsTrendCurrent": 1.25,
                        "epsTrend7daysAgo": 1.20,
                        "epsTrend30daysAgo": 1.10,
                        "epsTrend60daysAgo": 1.00,
                        "upLast7days": 5,
                        "downLast7days": 1
                      }
                    ],
                    [
                      {
                        "date": "2026-01-31",
                        "code": "MSFT.US",
                        "period": "Q1 2026",
                        "earningsEstimateAvg": 2.34,
                        "epsTrendCurrent": 2.40,
                        "epsTrend7daysAgo": 2.35,
                        "epsTrend30daysAgo": 2.20,
                        "epsTrend60daysAgo": 2.10,
                        "upLast7days": 6,
                        "downLast7days": 2
                      }
                    ]
                  ]
                }
                """;

        JsonNode payload = MAPPER.readTree(json);
        EodhdDiscoveryFormatter formatter = new EodhdDiscoveryFormatter();

        String markdown = formatter.formatTrends("AAPL.US,MSFT.US", payload);

        assertThat(markdown).contains("# EODHD Earnings Trends for AAPL.US,MSFT.US");
        assertThat(markdown)
                .contains("| Date | Code | Period | Earnings Est Avg | EPS Trend Current | EPS Trend 7D | EPS Trend 30D | EPS Trend 60D | Revisions Up | Revisions Down |");
        assertThat(markdown).contains("| 2026-01-31 | AAPL.US | Q1 2026 | 1.2300 | 1.2500 | 1.2000 | 1.1000 | 1.0000 | 5 | 1 |");
        assertThat(markdown).contains("| 2026-01-31 | MSFT.US | Q1 2026 | 2.3400 | 2.4000 | 2.3500 | 2.2000 | 2.1000 | 6 | 2 |");
        assertThat(markdown).doesNotContain("strong_buy");
    }
}
