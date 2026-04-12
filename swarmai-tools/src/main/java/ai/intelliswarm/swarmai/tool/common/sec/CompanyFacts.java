package ai.intelliswarm.swarmai.tool.common.sec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Structured financial facts for a company, sourced from the SEC's XBRL
 * companyfacts endpoint (https://data.sec.gov/api/xbrl/companyfacts/CIK##########.json).
 *
 * <p>Unlike scraping inline-XBRL from filing HTML, this API returns clean JSON with
 * all reported us-gaap concepts across every period the company has filed. It's the
 * authoritative source for revenue/net income/margin extraction and enables YoY
 * growth computation without parsing tables.
 */
public class CompanyFacts {

    private String entityName;
    private String cik;

    /** Concept → ordered list of observations (most-recent first). */
    private final Map<String, List<Fact>> concepts = new LinkedHashMap<>();

    /** A single reported XBRL fact for one concept in one reporting period. */
    public record Fact(
            double value,
            String unit,           // "USD", "USD/shares", "shares", etc.
            String period,         // "FY2024", "Q1-2025", etc.
            String endDate,        // ISO-8601
            String form,           // "10-K", "10-Q"
            String fiscalYear,     // e.g. "2024"
            String fiscalPeriod    // e.g. "FY", "Q1", "Q2", "Q3"
    ) {
        public String label() {
            if (fiscalPeriod != null && fiscalYear != null) {
                return fiscalPeriod + " " + fiscalYear;
            }
            return period != null ? period : endDate;
        }
    }

    public String getEntityName() { return entityName; }
    public void setEntityName(String entityName) { this.entityName = entityName; }

    public String getCik() { return cik; }
    public void setCik(String cik) { this.cik = cik; }

    public void addFact(String concept, Fact fact) {
        concepts.computeIfAbsent(concept, k -> new ArrayList<>()).add(fact);
    }

    public List<Fact> getConceptFacts(String concept) {
        return concepts.getOrDefault(concept, List.of());
    }

    /** Returns the N most-recent annual (FY) facts for a concept. */
    public List<Fact> getRecentAnnual(String concept, int limit) {
        return concepts.getOrDefault(concept, List.of()).stream()
                .filter(f -> "FY".equals(f.fiscalPeriod()))
                .limit(limit)
                .toList();
    }

    /** Returns the N most-recent quarterly facts for a concept. */
    public List<Fact> getRecentQuarterly(String concept, int limit) {
        return concepts.getOrDefault(concept, List.of()).stream()
                .filter(f -> f.fiscalPeriod() != null && f.fiscalPeriod().startsWith("Q"))
                .limit(limit)
                .toList();
    }

    public boolean hasConcept(String concept) {
        return concepts.containsKey(concept) && !concepts.get(concept).isEmpty();
    }

    public Map<String, List<Fact>> getAllConcepts() {
        return concepts;
    }
}
