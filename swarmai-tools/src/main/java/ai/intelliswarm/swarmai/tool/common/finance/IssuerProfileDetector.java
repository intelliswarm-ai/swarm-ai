package ai.intelliswarm.swarmai.tool.common.finance;

/**
 * Detects whether a ticker is a domestic (10-K/10-Q) or foreign (20-F/6-K) SEC filer
 * by inspecting pre-fetched tool evidence for the presence of each form type.
 *
 * <p>Heuristic is deliberately simple — the signal lives in the SEC filings inventory
 * section of the tool evidence, not in an API call. That keeps this stateless and
 * avoids an extra network round-trip.
 */
public class IssuerProfileDetector {

    /**
     * Detect based on the text of pre-fetched tool evidence. Looks inside the
     * "=== SEC FILINGS DATA ===" section for "20-F" vs "10-K" markers.
     *
     * @param toolEvidence raw tool-evidence text (must contain the SEC filings section
     *                     for meaningful detection; if missing, defaults to domestic)
     * @return detected {@link IssuerProfile}
     */
    public IssuerProfile detect(String toolEvidence) {
        if (toolEvidence == null || toolEvidence.isEmpty()) {
            return IssuerProfile.domestic();
        }
        // Restrict detection to the SEC section to avoid matching stray "20-F" strings
        // in web-search results about other companies.
        int secStart = toolEvidence.indexOf("=== SEC FILINGS DATA");
        String secSection = secStart >= 0 ? toolEvidence.substring(secStart) : toolEvidence;

        boolean hasTenK = secSection.contains("10-K") || secSection.contains("10‑K");
        boolean hasTwentyF = secSection.contains("20-F") || secSection.contains("20‑F");

        if (hasTwentyF && !hasTenK) {
            return IssuerProfile.foreign();
        }
        return IssuerProfile.domestic();
    }
}
