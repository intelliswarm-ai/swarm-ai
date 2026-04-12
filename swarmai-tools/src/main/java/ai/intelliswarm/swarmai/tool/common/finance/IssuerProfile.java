package ai.intelliswarm.swarmai.tool.common.finance;

/**
 * Describes the SEC filing cadence of a publicly-traded company. Used by multi-agent
 * workflows to adapt task prompts — e.g. asking for "the latest 10-K" for domestic
 * filers vs. "the latest 20-F" for foreign private issuers like Imperial Petroleum
 * (Greek shipping co. IMPP).
 *
 * <p>Without this distinction, prompts hardcoded to "10-K/10-Q" caused analysts to
 * declare foreign issuers' filings missing when they simply use different forms.
 *
 * @param isForeign              true if the filer is a foreign private issuer
 * @param primaryAnnualForm      "10-K" for domestic, "20-F" for foreign
 * @param primaryQuarterlyForm   "10-Q" for domestic, "6-K" for foreign
 */
public record IssuerProfile(boolean isForeign, String primaryAnnualForm, String primaryQuarterlyForm) {

    /** Standard profile for US-listed domestic companies. */
    public static IssuerProfile domestic() {
        return new IssuerProfile(false, "10-K", "10-Q");
    }

    /** Standard profile for foreign private issuers (20-F filers). */
    public static IssuerProfile foreign() {
        return new IssuerProfile(true, "20-F", "6-K");
    }
}
