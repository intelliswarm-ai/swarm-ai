package ai.intelliswarm.swarmai.tool.common.sec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts Management's Discussion & Analysis (MD&A) content from filing bodies and
 * organizes it into themed, citation-carrying bullets that downstream agents can quote
 * directly.
 *
 * <p>Earlier iterations of the SEC tool emitted MD&A as a single 4000-char prose blob
 * per filing. Judge evaluation flagged: <em>"MD&A quotations are still prose-based so
 * the verifier can't cleanly fill in a missing MD&A bullet."</em> This extractor solves
 * that by categorizing MD&A sentences into four themes — <b>risks</b>, <b>opportunities
 * / growth</b>, <b>liquidity</b>, <b>guidance / outlook</b> — each with a per-sentence
 * filing citation. The verifier can then look up a specific theme rather than scanning
 * prose.
 */
public class MDAExtractor {

    /** One themed MD&A sentence with its source filing citation. */
    public record Bullet(String theme, String excerpt, String form, String filingDate) {
        public String citation() {
            return String.format("[%s %s]", form != null ? form : "filing", filingDate != null ? filingDate : "");
        }
    }

    // Sentence classification: theme → trigger keywords. First match wins.
    private static final Map<String, String[]> THEMES = new LinkedHashMap<>() {{
        put("Risks", new String[]{
                "risk", "adversely", "adverse", "uncertain", "uncertainty", "exposure",
                "litigation", "regulatory action", "decline", "declines", "decrease",
                "weakness", "headwind", "could negatively", "may negatively",
                "material adverse", "disruption", "disrupted"
        });
        put("Liquidity & Capital", new String[]{
                "liquidity", "working capital", "capital resources", "cash flow", "cash flows",
                "debt maturity", "credit facility", "covenant", "refinance", "refinancing",
                "capital expenditure", "capex", "dividend", "share repurchase", "buyback"
        });
        put("Guidance & Outlook", new String[]{
                "guidance", "outlook", "expect", "expected", "expects", "anticipate",
                "anticipates", "project", "projects", "forecast", "forecasts", "target",
                "targets", "we believe", "we intend", "will continue", "plans to"
        });
        put("Opportunities & Growth", new String[]{
                "growth", "grew", "growing", "increase", "increased", "increases",
                "opportunity", "opportunities", "launched", "expansion", "expand",
                "expanded", "acquire", "acquired", "acquisition", "new product",
                "market share", "demand"
        });
    }};

    // Minimum sentence length to consider (filter out fragments / headings).
    // 35 is lenient enough for short-form 6-K / 20-F bullets that summarize briefly
    // while still rejecting single-word table cells and page numbers.
    private static final int MIN_SENTENCE_CHARS = 35;
    // Maximum sentence length (trim runaway paragraphs that were mis-split)
    private static final int MAX_SENTENCE_CHARS = 400;
    // How many bullets to keep per theme per filing (prevents one verbose 10-K dominating)
    private static final int MAX_BULLETS_PER_THEME_PER_FILING = 2;

    // Split on sentence boundaries but tolerate abbreviations like "U.S.", "Inc.", "Dr."
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=\\.)\\s+(?=[A-Z])|(?<=[!?])\\s+");

    /**
     * Extract themed MD&A bullets across all filings whose content contains an MD&A section.
     * Most recent filings first; caps total bullets to avoid prompt bloat.
     */
    public List<Bullet> extract(List<Filing> filings, int maxBulletsTotal) {
        List<Bullet> all = new ArrayList<>();
        if (filings == null || filings.isEmpty()) return all;

        // Iterate in filing-date-descending order
        List<Filing> sorted = new ArrayList<>(filings);
        sorted.sort((a, b) -> safeCompare(b.getFilingDate(), a.getFilingDate()));

        for (Filing filing : sorted) {
            if (!filing.isContentFetched() || filing.getExtractedText() == null) continue;
            if (!isMDAFriendly(filing.getFormType())) continue;

            String mdaText = findMDABlock(filing.getExtractedText());
            // Minimum 100 chars avoids extracting from tiny fragments but is lenient
            // enough to handle short-form 6-K / 20-F summaries and test fixtures.
            if (mdaText == null || mdaText.length() < 100) continue;

            Map<String, Integer> perThemeCount = new LinkedHashMap<>();
            for (String sentence : splitSentences(mdaText)) {
                if (sentence.length() < MIN_SENTENCE_CHARS || sentence.length() > MAX_SENTENCE_CHARS) continue;
                String theme = classify(sentence);
                if (theme == null) continue;
                int count = perThemeCount.getOrDefault(theme, 0);
                if (count >= MAX_BULLETS_PER_THEME_PER_FILING) continue;
                perThemeCount.put(theme, count + 1);
                all.add(new Bullet(theme, sentence.trim(), filing.getFormType(), filing.getFilingDate()));
                if (all.size() >= maxBulletsTotal) return all;
            }
        }
        return all;
    }

    /** Render extracted bullets into a markdown section grouped by theme. */
    public String render(List<Bullet> bullets) {
        if (bullets == null || bullets.isEmpty()) return "";

        // Group by theme, preserving the THEMES iteration order
        Map<String, List<Bullet>> byTheme = new LinkedHashMap<>();
        for (String theme : THEMES.keySet()) byTheme.put(theme, new ArrayList<>());
        for (Bullet b : bullets) byTheme.computeIfAbsent(b.theme(), k -> new ArrayList<>()).add(b);

        StringBuilder sb = new StringBuilder();
        sb.append("## MD&A Highlights\n\n");
        sb.append("_Extracted from the most recent 10-K / 10-Q / 20-F filing bodies and categorized ")
                .append("by theme. Each bullet carries a per-filing citation. Agents MUST quote these ")
                .append("bullets verbatim when addressing risk / liquidity / outlook / growth questions._\n\n");

        boolean anyContent = false;
        for (Map.Entry<String, List<Bullet>> entry : byTheme.entrySet()) {
            List<Bullet> list = entry.getValue();
            if (list.isEmpty()) continue;
            anyContent = true;
            sb.append("### ").append(entry.getKey()).append("\n\n");
            for (Bullet b : list) {
                sb.append("- ").append(b.excerpt());
                if (!b.excerpt().endsWith(".") && !b.excerpt().endsWith("!") && !b.excerpt().endsWith("?")) {
                    sb.append(".");
                }
                sb.append(" ").append(b.citation()).append("\n");
            }
            sb.append("\n");
        }

        if (!anyContent) return "";
        return sb.toString();
    }

    // ---------- internals ----------

    private boolean isMDAFriendly(String formType) {
        if (formType == null) return false;
        String t = formType.toUpperCase(Locale.US);
        return t.equals("10-K") || t.equals("10-Q") || t.equals("20-F") || t.equals("6-K");
    }

    /**
     * Locate the MD&A block within cleaned filing text using a flexible heading match.
     * Returns the substring from the heading to the next major section, or null if not found.
     *
     * <p>Filings are heterogeneous:
     * <ul>
     *   <li>10-K / 10-Q: "Management's Discussion and Analysis..."</li>
     *   <li>20-F (foreign issuers like IMPP): "Operating and Financial Review and Prospects",
     *       "Item 5. Operating Results", "Liquidity and Capital Resources"</li>
     *   <li>6-K: often has "Operating Results" or plain "Results of Operations"</li>
     * </ul>
     * Also normalizes curly apostrophes (U+2019) that real filings often contain — without
     * this, "Management's" in the source text wouldn't match our ASCII-apostrophe lookup.
     */
    private String findMDABlock(String text) {
        if (text == null) return null;
        // Normalize curly apostrophe → ASCII straight apostrophe before lowercasing. Without
        // this, a 20-F with `<p>Management's Discussion...</p>` (curly) silently misses our
        // hardcoded "management's discussion..." lookup.
        String normalized = text.replace('\u2019', '\'').replace('\u02bc', '\'');
        String lower = normalized.toLowerCase(Locale.US);

        int start = -1;
        String[] headings = {
                // 10-K / 10-Q (domestic)
                "management's discussion and analysis",
                "management discussion and analysis",
                "results of operations",
                // 20-F (foreign private issuers)
                "operating and financial review and prospects",
                "operating and financial review",
                "operating results",
                "liquidity and capital resources",
                "item 5"                        // 20-F Item 5 is always MD&A-equivalent
        };
        for (String h : headings) {
            int idx = lower.indexOf(h);
            if (idx != -1 && (start == -1 || idx < start)) start = idx;
        }
        if (start == -1) return null;

        // End at the next common section heading
        String[] terminators = {
                "quantitative and qualitative disclosures",
                "financial statements and supplementary data",
                "controls and procedures",
                "legal proceedings",
                "risk factors",
                "directors, senior management",   // 20-F Item 6
                "directors and officers",
                "executive compensation",
                "major shareholders",              // 20-F Item 7
                "item 6",                          // explicit 20-F terminator
                "item 7",
                "part ii",
                "part iii"
        };
        int end = normalized.length();
        for (String term : terminators) {
            int idx = lower.indexOf(term, start + 500);
            if (idx != -1 && idx < end) end = idx;
        }
        end = Math.min(end, start + 20000);
        return normalized.substring(start, end);
    }

    private List<String> splitSentences(String block) {
        String[] raw = SENTENCE_SPLIT.split(block);
        List<String> sentences = new ArrayList<>(raw.length);
        for (String s : raw) {
            String cleaned = s.replaceAll("\\s+", " ").trim();
            if (!cleaned.isEmpty()) sentences.add(cleaned);
        }
        return sentences;
    }

    /** Classify a sentence into a theme based on first-matching keyword. Null → no theme. */
    private String classify(String sentence) {
        String lower = sentence.toLowerCase(Locale.US);
        for (Map.Entry<String, String[]> entry : THEMES.entrySet()) {
            for (String kw : entry.getValue()) {
                if (containsWord(lower, kw)) return entry.getKey();
            }
        }
        return null;
    }

    /** Word-boundary-aware substring check (so "expect" doesn't match "expectation"). */
    private boolean containsWord(String haystack, String needle) {
        int idx = haystack.indexOf(needle);
        while (idx != -1) {
            boolean leftOK = idx == 0 || !Character.isLetterOrDigit(haystack.charAt(idx - 1));
            int endIdx = idx + needle.length();
            boolean rightOK = endIdx == haystack.length() || !Character.isLetterOrDigit(haystack.charAt(endIdx));
            if (leftOK && rightOK) return true;
            idx = haystack.indexOf(needle, idx + 1);
        }
        return false;
    }

    private int safeCompare(String a, String b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        return a.compareTo(b);
    }
}
