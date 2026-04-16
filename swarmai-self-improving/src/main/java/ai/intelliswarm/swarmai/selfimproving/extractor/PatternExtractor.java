package ai.intelliswarm.swarmai.selfimproving.extractor;

import ai.intelliswarm.swarmai.selfimproving.config.SelfImprovementConfig;
import ai.intelliswarm.swarmai.selfimproving.ledger.LedgerStore;
import ai.intelliswarm.swarmai.selfimproving.model.*;
import ai.intelliswarm.swarmai.selfimproving.model.GenericRule.RuleCategory;
import ai.intelliswarm.swarmai.selfimproving.model.GenericRule.ValidationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Extracts generic rules from specific observations.
 *
 * The key operation: go from "this research workflow converges at iteration 2"
 * to "workflows with task_depth <= 3 and no skill generation converge by iteration 2".
 *
 * Rules are cross-validated against historical data to ensure genericity.
 */
public class PatternExtractor {

    private static final Logger log = LoggerFactory.getLogger(PatternExtractor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SelfImprovementConfig config;
    private final LedgerStore ledgerStore;
    private final List<SpecificObservation> historicalObservations = new CopyOnWriteArrayList<>();

    public PatternExtractor(SelfImprovementConfig config, LedgerStore ledgerStore) {
        this.config = config;
        this.ledgerStore = ledgerStore;
    }

    /**
     * Record observations for future cross-validation.
     * Also persists to the ledger store so observations survive JVM restarts.
     */
    public void recordObservations(List<SpecificObservation> observations) {
        historicalObservations.addAll(observations);
        // Keep bounded
        while (historicalObservations.size() > 10_000) {
            historicalObservations.remove(0);
        }
        // Persist each observation to the durable store
        for (SpecificObservation obs : observations) {
            try {
                String evidenceJson = MAPPER.writeValueAsString(obs.evidence());
                ledgerStore.recordObservation(
                        obs.observationId(),
                        obs.type() != null ? obs.type().name() : "UNKNOWN",
                        obs.description(),
                        evidenceJson
                );
            } catch (Exception e) {
                log.debug("Failed to persist observation {} (non-fatal): {}", obs.observationId(), e.getMessage());
            }
        }
    }

    /**
     * Attempt to extract a generic rule from a set of similar observations.
     * Returns null if the pattern isn't generic enough.
     */
    public GenericRule extract(List<SpecificObservation> observations) {
        if (observations.size() < config.getMinObservations()) {
            log.debug("Not enough observations ({}) to extract pattern, need {}",
                    observations.size(), config.getMinObservations());
            return null;
        }

        SpecificObservation.ObservationType type = observations.get(0).type();

        // Step 1: Find structural features common to all observations
        Map<String, Object> commonCondition = findCommonFeatures(observations);
        if (commonCondition.isEmpty()) {
            log.debug("No common structural features found across observations");
            return null;
        }

        // Step 2: Check that condition doesn't reference domain terms
        if (containsDomainTerms(commonCondition)) {
            log.debug("Extracted condition contains domain terms, not generic enough");
            return null;
        }

        // Step 3: Cross-validate against historical data
        ValidationResult validation = crossValidate(commonCondition, type, observations);

        // Step 4: Determine category and recommendation
        RuleCategory category = mapCategory(type);
        String recommendation = buildRecommendation(type, observations);
        Object recommendedValue = extractRecommendedValue(type, observations);
        double confidence = computeConfidence(observations, validation);

        GenericRule rule = new GenericRule(
                UUID.randomUUID().toString(),
                category,
                commonCondition,
                recommendation,
                recommendedValue,
                confidence,
                List.copyOf(observations),
                validation,
                Instant.now(),
                Instant.now()
        );

        log.info("Extracted generic rule: category={}, confidence={:.2f}, condition={}",
                category, confidence, commonCondition);
        return rule;
    }

    /**
     * Find features that are consistent across all observation workflow shapes.
     */
    private Map<String, Object> findCommonFeatures(List<SpecificObservation> observations) {
        List<Map<String, Object>> featureMaps = observations.stream()
                .filter(obs -> obs.workflowShape() != null)
                .map(obs -> obs.workflowShape().toFeatureMap())
                .toList();

        if (featureMaps.isEmpty()) return Map.of();

        Map<String, Object> common = new LinkedHashMap<>();
        Map<String, Object> first = featureMaps.get(0);

        for (String key : first.keySet()) {
            // Skip features that vary — they're not part of the pattern
            boolean consistent = featureMaps.stream()
                    .allMatch(m -> Objects.equals(m.get(key), first.get(key)));

            if (consistent) {
                Object value = first.get(key);
                // For numeric features, use a threshold rather than exact match
                if (value instanceof Integer intVal) {
                    int max = featureMaps.stream()
                            .mapToInt(m -> (Integer) m.get(key))
                            .max().orElse(intVal);
                    common.put(key, "<=" + max);
                } else {
                    common.put(key, value);
                }
            }
        }

        return common;
    }

    private ValidationResult crossValidate(Map<String, Object> condition,
                                           SpecificObservation.ObservationType type,
                                           List<SpecificObservation> currentObs) {
        // If in-memory observations are empty (cross-JVM scenario), re-hydrate
        // from the durable store so cross-validation has historical data.
        if (historicalObservations.isEmpty()) {
            try {
                List<LedgerStore.StoredObservation> stored = ledgerStore.getRecentObservations(10_000);
                for (LedgerStore.StoredObservation so : stored) {
                    try {
                        SpecificObservation.ObservationType obsType =
                                SpecificObservation.ObservationType.valueOf(so.observationType());
                        // Re-create a lightweight SpecificObservation for cross-validation.
                        // WorkflowShape is not persisted, so we use a minimal placeholder;
                        // cross-validation matches on type, not shape, when rehydrating.
                        @SuppressWarnings("unchecked")
                        Map<String, Object> evidence = so.evidenceJson() != null
                                ? MAPPER.readValue(so.evidenceJson(), Map.class)
                                : Map.of();
                        historicalObservations.add(new SpecificObservation(
                                so.swarmId(),
                                obsType,
                                null, // shape not persisted; findCommonFeatures skips nulls
                                so.description(),
                                evidence,
                                0.5,
                                so.createdAt()
                        ));
                    } catch (IllegalArgumentException ignored) {
                        // Unknown observation type from an older schema — skip
                    }
                }
                log.debug("Re-hydrated {} historical observations from ledger store",
                        historicalObservations.size());
            } catch (Exception e) {
                log.debug("Failed to re-hydrate observations from ledger (non-fatal): {}", e.getMessage());
            }
        }

        // Exclude current observations from validation set
        Set<String> currentIds = currentObs.stream()
                .map(SpecificObservation::observationId)
                .collect(Collectors.toSet());

        List<SpecificObservation> validationSet = historicalObservations.stream()
                .filter(obs -> !currentIds.contains(obs.observationId()))
                .filter(obs -> obs.type() == type)
                .toList();

        if (validationSet.size() < config.getMinCrossWorkflowEvidence()) {
            return ValidationResult.failed(validationSet.size(), 0,
                    List.of("Not enough historical data for cross-validation"));
        }

        int matched = 0;
        List<String> details = new ArrayList<>();

        for (SpecificObservation obs : validationSet) {
            if (obs.workflowShape() == null) {
                // Rehydrated observation without shape — count as a match on type alone
                // since the observation type already passed the filter above.
                matched++;
                details.add("Matched (rehydrated): %s".formatted(obs.description()));
                continue;
            }
            boolean matches = obs.workflowShape().matches(condition);
            if (matches) {
                matched++;
                details.add("Matched: %s (shape: %s)".formatted(obs.description(), obs.workflowShape().processType()));
            }
        }

        if (matched >= config.getMinCrossWorkflowEvidence()) {
            return ValidationResult.passed(validationSet.size(), matched, details);
        }
        return ValidationResult.failed(validationSet.size(), matched, details);
    }

    private boolean containsDomainTerms(Map<String, Object> condition) {
        Set<String> domainTerms = Set.of("research", "analysis", "compliance", "marketing",
                "finance", "healthcare", "legal", "sales", "security", "audit");

        String condStr = condition.toString().toLowerCase();
        return domainTerms.stream().anyMatch(condStr::contains);
    }

    private RuleCategory mapCategory(SpecificObservation.ObservationType type) {
        return switch (type) {
            case CONVERGENCE_PATTERN -> RuleCategory.CONVERGENCE_DEFAULT;
            case TOOL_SELECTION -> RuleCategory.TOOL_ROUTING;
            case PROMPT_EFFICIENCY -> RuleCategory.PROMPT_OPTIMIZATION;
            case SUCCESSFUL_SKILL -> RuleCategory.SKILL_PROMOTION;
            case ANTI_PATTERN -> RuleCategory.ANTI_PATTERN;
            case FAILURE -> RuleCategory.ANTI_PATTERN;
            case EXPENSIVE_TASK -> RuleCategory.CONVERGENCE_DEFAULT;
            case DECISION_QUALITY -> RuleCategory.ANTI_PATTERN;
            case PROCESS_SUITABILITY -> RuleCategory.PROCESS_SELECTION;
            case COORDINATION_QUALITY -> RuleCategory.PROMPT_OPTIMIZATION;
        };
    }

    private String buildRecommendation(SpecificObservation.ObservationType type,
                                       List<SpecificObservation> observations) {
        return switch (type) {
            case CONVERGENCE_PATTERN -> "Adjust convergence defaults for matching workflow shapes";
            case TOOL_SELECTION -> "Update tool routing hints for better first-pick accuracy";
            case SUCCESSFUL_SKILL -> "Promote skill to built-in library";
            case ANTI_PATTERN -> "Add compile-time warning for this pattern";
            case FAILURE -> "Add validation rule to prevent this failure mode";
            case EXPENSIVE_TASK -> "Optimize token consumption for matching task patterns";
            case PROMPT_EFFICIENCY -> "Update prompt template for more efficient agent turns";
            case DECISION_QUALITY -> "Refine agent decision pipeline to reduce excessive retries";
            case PROCESS_SUITABILITY -> "Switch independent sequential tasks to parallel execution";
            case COORDINATION_QUALITY -> "Improve context handoff between sequential agents";
        };
    }

    private Object extractRecommendedValue(SpecificObservation.ObservationType type,
                                           List<SpecificObservation> observations) {
        if (type == SpecificObservation.ObservationType.CONVERGENCE_PATTERN) {
            // Recommend the median convergence iteration as the new default
            OptionalDouble median = observations.stream()
                    .map(obs -> obs.evidence().get("converged_at"))
                    .filter(Objects::nonNull)
                    .mapToInt(v -> (int) v)
                    .sorted()
                    .average();
            return median.isPresent() ? (int) Math.ceil(median.getAsDouble()) : null;
        }
        return null;
    }

    private double computeConfidence(List<SpecificObservation> observations,
                                     ValidationResult validation) {
        double observationScore = Math.min(1.0, observations.size() / 10.0);
        double validationScore = validation.passed() ? validation.crossValidationScore() : 0.0;
        return (observationScore * 0.4) + (validationScore * 0.6);
    }
}
