package ai.intelliswarm.swarmai.tool.finance.mcp;

import ai.intelliswarm.swarmai.api.PublicApi;
import ai.intelliswarm.swarmai.spi.LicenseProvider;
import ai.intelliswarm.swarmai.tool.finance.mcp.McpConnectorDefinition.AuthScheme;

import java.util.List;

/**
 * Curated catalog of financial MCP server definitions. The endpoints, auth
 * schemes, and licensing tiers below mirror the partner-MCP set from
 * Anthropic's financial-services repo plus a handful of common adjacent
 * sources. Each is a {@link McpConnectorDefinition} only — no live HTTP, no
 * credentials.
 *
 * <p>Adding a new connector: write one factory method, add it to
 * {@link #all()}. License-gating, registry filtering, and the actual MCP
 * wiring are handled elsewhere.
 *
 * <p>Tier conventions used here (cf. {@link LicenseProvider.Edition}):
 * <ul>
 *   <li>{@code BUSINESS} — paid market-data feeds and CIM/screen sources
 *       (Daloopa, Morningstar, Moody's, MT Newswires, Aiera)</li>
 *   <li>{@code ENTERPRISE} — premium institutional sources requiring vendor
 *       contracts (LSEG LFA, S&amp;P Capital IQ, FactSet, PitchBook,
 *       Chronograph)</li>
 *   <li>{@code TEAM} — light-touch document repository (Egnyte)</li>
 * </ul>
 */
@PublicApi(since = "1.0.24")
public final class FinancialMcpConnectors {

    private FinancialMcpConnectors() {}

    // ---------- Enterprise institutional sources ----------

    public static McpConnectorDefinition lsegLfa() {
        return McpConnectorDefinition.builder("lseg-lfa")
                .displayName("LSEG LFA")
                .vendor("LSEG")
                .endpoint("https://api.analytics.lseg.com/lfa/mcp")
                .authScheme(AuthScheme.OAUTH)
                .requiredEdition(LicenseProvider.Edition.ENTERPRISE)
                .requiredFeature("mcp.lseg")
                .toolPreview("bond_price", "bond_future_price", "fx_spot_price",
                        "fx_forward_price", "fx_forward_curve", "fx_vol_surface",
                        "interest_rate_curve", "ir_swap", "credit_curve", "inflation_curve",
                        "option_value", "equity_vol_surface", "yieldbook_scenario",
                        "fixed_income_risk_analytics")
                .description("Bond pricing, FX/rate curves, vol surfaces, scenario analysis (26 tools)")
                .build();
    }

    public static McpConnectorDefinition spCapitalIq() {
        return McpConnectorDefinition.builder("sp-capital-iq")
                .displayName("S&P Capital IQ")
                .vendor("S&P Global")
                .endpoint("https://kfinance.kensho.com/integrations/mcp")
                .authScheme(AuthScheme.OAUTH)
                .requiredEdition(LicenseProvider.Edition.ENTERPRISE)
                .requiredFeature("mcp.sp_capital_iq")
                .toolPreview("company_search", "financials_lookup", "comps_screen",
                        "transactions_search", "credit_ratings")
                .description("Fundamentals, comps, transactions, credit ratings")
                .build();
    }

    public static McpConnectorDefinition factset() {
        return McpConnectorDefinition.builder("factset")
                .displayName("FactSet")
                .vendor("FactSet")
                .endpoint("https://mcp.factset.com/mcp")
                .authScheme(AuthScheme.OAUTH)
                .requiredEdition(LicenseProvider.Edition.ENTERPRISE)
                .requiredFeature("mcp.factset")
                .toolPreview("pricing", "estimates", "fundamentals", "ownership")
                .description("Pricing, consensus estimates, fundamentals, ownership data")
                .build();
    }

    public static McpConnectorDefinition pitchbook() {
        return McpConnectorDefinition.builder("pitchbook")
                .displayName("PitchBook")
                .vendor("PitchBook")
                .endpoint("https://premium.mcp.pitchbook.com/mcp")
                .authScheme(AuthScheme.OAUTH)
                .requiredEdition(LicenseProvider.Edition.ENTERPRISE)
                .requiredFeature("mcp.pitchbook")
                .toolPreview("deal_search", "company_profile", "fund_performance",
                        "investor_lookup")
                .description("PE/VC deals, fund performance, investor profiles")
                .build();
    }

    public static McpConnectorDefinition chronograph() {
        return McpConnectorDefinition.builder("chronograph")
                .displayName("Chronograph")
                .vendor("Chronograph")
                .endpoint("https://ai.chronograph.pe/mcp")
                .authScheme(AuthScheme.BEARER)
                .requiredEdition(LicenseProvider.Edition.ENTERPRISE)
                .requiredFeature("mcp.chronograph")
                .toolPreview("portfolio_company_lookup", "performance_metrics", "kpi_trends")
                .description("PE portfolio monitoring — KPIs, valuation marks, performance")
                .build();
    }

    // ---------- Business-tier paid sources ----------

    public static McpConnectorDefinition daloopa() {
        return McpConnectorDefinition.builder("daloopa")
                .displayName("Daloopa")
                .vendor("Daloopa")
                .endpoint("https://mcp.daloopa.com/server/mcp")
                .authScheme(AuthScheme.BEARER)
                .requiredEdition(LicenseProvider.Edition.BUSINESS)
                .requiredFeature("mcp.daloopa")
                .toolPreview("fundamentals_lookup", "model_pull", "segment_breakdown")
                .description("Auto-parsed historical fundamentals and segment data")
                .build();
    }

    public static McpConnectorDefinition morningstar() {
        return McpConnectorDefinition.builder("morningstar")
                .displayName("Morningstar")
                .vendor("Morningstar")
                .endpoint("https://mcp.morningstar.com/mcp")
                .authScheme(AuthScheme.BEARER)
                .requiredEdition(LicenseProvider.Edition.BUSINESS)
                .requiredFeature("mcp.morningstar")
                .toolPreview("fund_screener", "equity_research", "ratings_lookup")
                .description("Funds, equity research, ratings, ESG scoring")
                .build();
    }

    public static McpConnectorDefinition moodys() {
        return McpConnectorDefinition.builder("moodys")
                .displayName("Moody's")
                .vendor("Moody's")
                .endpoint("https://api.moodys.com/genai-ready-data/m1/mcp")
                .authScheme(AuthScheme.OAUTH)
                .requiredEdition(LicenseProvider.Edition.BUSINESS)
                .requiredFeature("mcp.moodys")
                .toolPreview("issuer_credit", "rating_action", "default_studies")
                .description("Credit ratings, default studies, methodology lookups")
                .build();
    }

    public static McpConnectorDefinition mtNewswires() {
        return McpConnectorDefinition.builder("mt-newswires")
                .displayName("MT Newswires")
                .vendor("MT Newswires")
                .endpoint("https://vast-mcp.blueskyapi.com/mtnewswires")
                .authScheme(AuthScheme.BEARER)
                .requiredEdition(LicenseProvider.Edition.BUSINESS)
                .requiredFeature("mcp.mt_newswires")
                .toolPreview("news_search", "company_news_feed", "macro_headlines")
                .description("Real-time financial news, company filings, macro headlines")
                .build();
    }

    public static McpConnectorDefinition aiera() {
        return McpConnectorDefinition.builder("aiera")
                .displayName("Aiera")
                .vendor("Aiera")
                .endpoint("https://mcp-pub.aiera.com")
                .authScheme(AuthScheme.OAUTH)
                .requiredEdition(LicenseProvider.Edition.BUSINESS)
                .requiredFeature("mcp.aiera")
                .toolPreview("earnings_transcript", "event_calendar", "investor_day_capture")
                .description("Earnings transcripts, investor events, real-time event capture")
                .build();
    }

    // ---------- Team-tier doc repositories ----------

    public static McpConnectorDefinition egnyte() {
        return McpConnectorDefinition.builder("egnyte")
                .displayName("Egnyte")
                .vendor("Egnyte")
                .endpoint("https://mcp-server.egnyte.com/mcp")
                .authScheme(AuthScheme.OAUTH)
                .requiredEdition(LicenseProvider.Edition.TEAM)
                .requiredFeature("mcp.egnyte")
                .toolPreview("file_search", "folder_list", "document_metadata")
                .description("Document repository — search, retrieve, audit document access")
                .build();
    }

    /** Every predefined connector. Order is stable (enterprise → business → team). */
    public static List<McpConnectorDefinition> all() {
        return List.of(
                lsegLfa(),
                spCapitalIq(),
                factset(),
                pitchbook(),
                chronograph(),
                daloopa(),
                morningstar(),
                moodys(),
                mtNewswires(),
                aiera(),
                egnyte());
    }
}
