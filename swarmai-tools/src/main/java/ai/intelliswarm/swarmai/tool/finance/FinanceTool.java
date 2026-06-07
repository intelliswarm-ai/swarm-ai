package ai.intelliswarm.swarmai.tool.finance;

import ai.intelliswarm.swarmai.api.PublicApi;
import ai.intelliswarm.swarmai.tool.base.BaseTool;

import java.util.List;

/**
 * Marker interface for finance-domain tools — anything an FS workflow agent
 * is likely to invoke when answering valuation, modelling, comparable-company,
 * or fund-admin questions.
 *
 * <p>Two reasons it's worth a dedicated marker rather than just {@code BaseTool}:
 *
 * <ol>
 *   <li><b>Discovery.</b> {@link FinanceToolBundle#instances()} returns every
 *       implementation of this interface so a non-Spring caller can register
 *       the full finance suite in one line.</li>
 *   <li><b>Auto-tagging.</b> {@link #getCategory()} defaults to {@code "finance"}
 *       and {@link #getTags()} prepends {@code "finance"} so the tool catalog
 *       can filter / scope cleanly without each tool having to remember.</li>
 * </ol>
 *
 * <p>Subclasses that need shared behaviour (ticker validation, audit-trail
 * emission, currency normalisation) should extend {@link AbstractFinanceTool}.
 */
@PublicApi(since = "1.0.24")
public interface FinanceTool extends BaseTool {

    @Override
    default String getCategory() {
        return "finance";
    }

    /** Domain-stable tag prepended in front of any tool-specific tags. */
    String FINANCE_DOMAIN_TAG = "finance";

    /**
     * Whether this tool requires market-data or company-data egress (used by
     * audit/governance to decide if the call needs a network-allowlist gate).
     * Default false — most computation tools are pure-math.
     */
    default boolean requiresMarketData() {
        return false;
    }

    /** Tag list always begins with {@link #FINANCE_DOMAIN_TAG}. */
    default List<String> defaultTags(String... extras) {
        List<String> out = new java.util.ArrayList<>();
        out.add(FINANCE_DOMAIN_TAG);
        for (String e : extras) {
            if (!FINANCE_DOMAIN_TAG.equals(e)) out.add(e);
        }
        return List.copyOf(out);
    }
}
