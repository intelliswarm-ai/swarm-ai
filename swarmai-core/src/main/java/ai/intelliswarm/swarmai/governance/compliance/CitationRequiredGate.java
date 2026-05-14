package ai.intelliswarm.swarmai.governance.compliance;

import ai.intelliswarm.swarmai.agent.llm.InlineCitation;
import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compliance gate that refuses to ship outputs containing quantitative claims
 * lacking source citations. The central building block for the financial-vertical
 * compliance story: every percentage, dollar amount, multiple, or hard number
 * must be anchored to a {@link InlineCitation}.
 *
 * <p>Heuristic: scans the output for sentences containing numeric tokens
 * (percentages, currency amounts, multipliers like {@code 12.5x}, large
 * integers). For each such sentence, requires at least one citation. If
 * citations exist on the output as a whole but a specific quantitative
 * sentence isn't anchored, that's still a blocker — the citation must
 * reasonably correspond.
 *
 * <p>This is intentionally conservative: false positives (flagging sentences
 * that don't strictly need citations) cost less than false negatives (shipping
 * a fabricated number to a regulator). Tune via
 * {@link Builder#requireCitationPerNumericSentence(boolean)} if needed.
 */
@PublicApi(since = "1.0.24")
public final class CitationRequiredGate implements ComplianceGate {

    private static final Pattern NUMERIC_SENTENCE = Pattern.compile(
            "(?<=^|[.!?]\\s|\\n)" +
                    "[^.!?\\n]*?" +
                    "(?:\\$\\s*[\\d,]+(?:\\.\\d+)?(?:[bBmMkK])?" +
                    "|\\d+(?:\\.\\d+)?\\s*%" +
                    "|\\d+(?:\\.\\d+)?\\s*x" +
                    "|\\b\\d{4,}\\b)" +
                    "[^.!?\\n]*?(?:[.!?]|$)");

    private final boolean requireCitationPerNumericSentence;
    private final int minimumCitationsOverall;

    private CitationRequiredGate(Builder b) {
        this.requireCitationPerNumericSentence = b.requireCitationPerNumericSentence;
        this.minimumCitationsOverall = b.minimumCitationsOverall;
    }

    public static CitationRequiredGate strict() {
        return builder()
                .requireCitationPerNumericSentence(true)
                .minimumCitationsOverall(1)
                .build();
    }

    public static CitationRequiredGate lenient() {
        return builder()
                .requireCitationPerNumericSentence(false)
                .minimumCitationsOverall(1)
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String name() {
        return "citation_required";
    }

    @Override
    public List<ComplianceFinding> evaluate(Output output) {
        List<ComplianceFinding> findings = new ArrayList<>();
        List<InlineCitation> citations = output.citations();
        String text = output.text();

        // Rule 1: numeric content must come with at least one citation overall
        Matcher sentenceMatcher = NUMERIC_SENTENCE.matcher(text);
        boolean hasAnyNumber = sentenceMatcher.find();
        if (hasAnyNumber && citations.size() < minimumCitationsOverall) {
            findings.add(ComplianceFinding.blocker(
                    "MISSING_CITATIONS_NUMERIC_OUTPUT",
                    "Output contains numeric claims but has " + citations.size()
                            + " citations (minimum required: " + minimumCitationsOverall + ")"));
        }

        // Rule 2: per-sentence anchoring (heuristic) — each numeric sentence must
        // have at least one citation whose cited text overlaps the sentence's
        // numeric span. This isn't perfect (the model could cite a different
        // adjacent fact) but it surfaces obvious gaps.
        if (requireCitationPerNumericSentence) {
            sentenceMatcher.reset();
            int sentenceIndex = 0;
            while (sentenceMatcher.find()) {
                sentenceIndex++;
                String sentence = sentenceMatcher.group().trim();
                if (sentence.isEmpty()) continue;
                boolean anchored = citations.stream().anyMatch(c ->
                        sentenceShareNumbers(sentence, c.citedText()));
                if (!anchored) {
                    findings.add(ComplianceFinding.blocker(
                            "UNANCHORED_NUMERIC_CLAIM",
                            "Numeric claim has no matching citation: \""
                                    + truncate(sentence, 120) + "\"",
                            Map.of("sentenceIndex", sentenceIndex,
                                    "sentence", truncate(sentence, 240))));
                }
            }
        }

        return findings;
    }

    private static boolean sentenceShareNumbers(String sentence, String citedText) {
        // Pull tokens that look numeric out of both strings; require at least one shared.
        List<String> sentenceNums = numericTokens(sentence);
        if (sentenceNums.isEmpty()) return true;
        List<String> citedNums = numericTokens(citedText);
        for (String n : sentenceNums) {
            if (citedNums.contains(n)) return true;
        }
        return false;
    }

    private static List<String> numericTokens(String text) {
        // Capture each number with its semantic suffix (%, x, $-prefix) so we can
        // distinguish "30%" (a rate) from "FY25" (a year). Without the suffix
        // a bare "25" would match the "25" inside FY25 even when the citation
        // talks about a different "25%" — false-negative anchoring.
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\$?\\s*\\d+(?:\\.\\d+)?(?:\\s*[%x])?").matcher(text);
        while (m.find()) {
            // Normalise whitespace so "30 %" matches "30%"
            String token = m.group().replaceAll("\\s+", "");
            out.add(token);
        }
        return out;
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n - 1) + "…" : s;
    }

    @PublicApi(since = "1.0.24")
    public static final class Builder {
        private boolean requireCitationPerNumericSentence = true;
        private int minimumCitationsOverall = 1;

        public Builder requireCitationPerNumericSentence(boolean v) {
            this.requireCitationPerNumericSentence = v;
            return this;
        }

        public Builder minimumCitationsOverall(int n) {
            this.minimumCitationsOverall = n;
            return this;
        }

        public CitationRequiredGate build() {
            return new CitationRequiredGate(this);
        }
    }
}
