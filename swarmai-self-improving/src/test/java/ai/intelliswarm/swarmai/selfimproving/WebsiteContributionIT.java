package ai.intelliswarm.swarmai.selfimproving;

import ai.intelliswarm.swarmai.selfimproving.aggregator.ImprovementAggregator;
import ai.intelliswarm.swarmai.selfimproving.classifier.ImprovementClassifier;
import ai.intelliswarm.swarmai.selfimproving.collector.ImprovementCollector;
import ai.intelliswarm.swarmai.selfimproving.config.SelfImprovementConfig;
import ai.intelliswarm.swarmai.selfimproving.extractor.PatternExtractor;
import ai.intelliswarm.swarmai.selfimproving.model.*;
import ai.intelliswarm.swarmai.selfimproving.model.ExecutionTrace.*;
import ai.intelliswarm.swarmai.selfimproving.model.SpecificObservation.ObservationType;
import ai.intelliswarm.swarmai.selfimproving.reporter.TelemetryReporter;
import ai.intelliswarm.swarmai.selfimproving.phase.ImprovementPhase.ImprovementResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the self-improvement submission pipeline to intelliswarm.ai.
 *
 * Tests the FULL path:
 *   ExecutionTrace → ImprovementCollector → PatternExtractor → ImprovementClassifier
 *   → TelemetryReporter → POST /api/contribute on intelliswarm.ai
 *
 * Tagged @website — only runs when the website backend is available.
 * Run with: mvn test -Dgroups=website -pl swarmai-self-improving
 *
 * This test verifies:
 * 1. The self-improvement pipeline produces valid improvement data
 * 2. The data format matches what the website backend expects
 * 3. The submission is accepted by the live endpoint
 * 4. The contribution gets a tracking ID back
 */
@Tag("website")
@DisplayName("Website Contribution Integration — Self-Improving → intelliswarm.ai/contribute")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WebsiteContributionIT {

    private static final String WEBSITE_BASE_URL = System.getenv().getOrDefault(
        "INTELLISWARM_API_URL", "https://intelliswarm.ai");
    private static final String CONTRIBUTE_ENDPOINT = WEBSITE_BASE_URL + "/api/contribute";

    private static boolean websiteAvailable;
    private static final ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @BeforeAll
    static void checkWebsiteAvailability() {
        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(WEBSITE_BASE_URL + "/api/health"))
                .timeout(Duration.ofSeconds(5))
                .GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            websiteAvailable = resp.statusCode() == 200;
            if (websiteAvailable) {
                System.out.println("Website backend available at " + WEBSITE_BASE_URL);
            }
        } catch (Exception e) {
            websiteAvailable = false;
            System.err.println("Website backend not available at " + WEBSITE_BASE_URL +
                ": " + e.getMessage() + ". Website integration tests will be skipped.");
        }
    }

    @BeforeEach
    void skipIfUnavailable() {
        Assumptions.assumeTrue(websiteAvailable,
            "Website backend not available — skipping integration test");
    }

    // ================================================================
    // TEST 1: Full pipeline produces valid improvement data
    // (No network — just validates the data format)
    // ================================================================

    @Test
    @Order(1)
    @DisplayName("Pipeline produces improvements from a realistic execution trace")
    void pipelineProducesImprovements() {
        // Build a realistic execution trace
        ExecutionTrace trace = buildRealisticTrace();
        ImprovementCollector collector = new ImprovementCollector();
        SelfImprovementConfig config = new SelfImprovementConfig();
        config.setMinObservations(1); // low threshold for test
        config.setMinCrossWorkflowEvidence(1);
        PatternExtractor extractor = new PatternExtractor(config);

        // Collect observations
        List<SpecificObservation> observations = collector.collect(trace);
        assertFalse(observations.isEmpty(),
            "Realistic trace should produce observations");

        // Seed historical data for cross-validation
        extractor.recordObservations(observations);
        // Add more historical data to enable cross-validation
        for (int i = 0; i < 5; i++) {
            extractor.recordObservations(collector.collect(buildRealisticTrace()));
        }

        // Extract rules from observations grouped by type
        Map<ObservationType, List<SpecificObservation>> byType = new HashMap<>();
        for (SpecificObservation obs : observations) {
            byType.computeIfAbsent(obs.type(), k -> new ArrayList<>()).add(obs);
        }

        List<GenericRule> rules = new ArrayList<>();
        for (var entry : byType.entrySet()) {
            if (entry.getValue().size() >= config.getMinObservations()) {
                GenericRule rule = extractor.extract(entry.getValue());
                if (rule != null) rules.add(rule);
            }
        }

        // Classify into tiers
        ImprovementClassifier classifier = new ImprovementClassifier(config);
        List<ImprovementProposal> proposals = rules.stream()
            .map(classifier::classify)
            .toList();

        assertFalse(proposals.isEmpty(),
            "Pipeline should produce at least one proposal from a realistic trace");

        // Verify proposals have required fields
        for (ImprovementProposal p : proposals) {
            assertNotNull(p.tier(), "Proposal must have a tier");
            assertNotNull(p.rule(), "Proposal must have a rule");
            assertNotNull(p.rule().category(), "Rule must have a category");
            assertNotNull(p.rule().condition(), "Rule must have conditions");
            assertFalse(p.rule().condition().isEmpty(),
                "Rule conditions should not be empty");
        }
    }

    // ================================================================
    // TEST 2: Format matches what the website backend expects
    // ================================================================

    @Test
    @Order(2)
    @DisplayName("Contribution format matches website API contract")
    void formatMatchesWebsiteContract() throws Exception {
        // Build the contribution payload that the website expects
        Map<String, Object> contribution = buildContributionPayload();

        String json = objectMapper.writeValueAsString(contribution);
        assertNotNull(json);

        // Verify required fields
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
        assertTrue(parsed.containsKey("improvementData"),
            "Payload must contain 'improvementData' — required by website backend");

        @SuppressWarnings("unchecked")
        Map<String, Object> improvementData = (Map<String, Object>) parsed.get("improvementData");
        assertEquals("swarmai-improvements", improvementData.get("exportFormat"),
            "exportFormat must be 'swarmai-improvements' — required by website validation");
        assertTrue(improvementData.containsKey("improvements"),
            "improvementData must contain 'improvements' array");
        assertTrue(improvementData.containsKey("frameworkVersion"),
            "improvementData must contain 'frameworkVersion'");
    }

    // ================================================================
    // TEST 3: Submit to live website backend
    // ================================================================

    @Test
    @Order(3)
    @DisplayName("Submit contribution to intelliswarm.ai/api/contribute and get tracking ID")
    void submitToWebsite() throws Exception {
        Map<String, Object> contribution = buildContributionPayload();
        String json = objectMapper.writeValueAsString(contribution);

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(CONTRIBUTE_ENDPOINT))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(),
            "Website should accept contribution. Response: " + response.body());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = objectMapper.readValue(response.body(), Map.class);
        assertTrue((Boolean) body.get("success"),
            "Response should indicate success");
        assertNotNull(body.get("trackingId"),
            "Response should include a tracking ID for the contribution");

        System.out.println("Contribution accepted. Tracking ID: " + body.get("trackingId"));
        System.out.println("Improvements accepted: " + body.get("improvementsAccepted"));
    }

    // ================================================================
    // TEST 4: Website rejects invalid format
    // ================================================================

    @Test
    @Order(4)
    @DisplayName("Website rejects payload with wrong exportFormat")
    void rejectsInvalidFormat() throws Exception {
        Map<String, Object> invalid = Map.of(
            "improvementData", Map.of(
                "exportFormat", "wrong-format",
                "improvements", List.of()
            )
        );

        String json = objectMapper.writeValueAsString(invalid);

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(CONTRIBUTE_ENDPOINT))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode(),
            "Website should reject invalid exportFormat with 400");
    }

    // ================================================================
    // TEST 5: Website rejects empty payload
    // ================================================================

    @Test
    @Order(5)
    @DisplayName("Website rejects payload without improvementData")
    void rejectsMissingImprovementData() throws Exception {
        String json = objectMapper.writeValueAsString(Map.of("notes", "no data"));

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(CONTRIBUTE_ENDPOINT))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode(),
            "Website should reject payload without improvementData");
    }

    // ================================================================
    // TEST 6: TelemetryReporter format compatibility
    // (Verifies the framework's TelemetryReporter produces data
    //  that can be converted to the website's expected format)
    // ================================================================

    @Test
    @Order(6)
    @DisplayName("TelemetryReporter output is convertible to website contribution format")
    void telemetryReporterFormatConvertible() {
        TelemetryReporter reporter = new TelemetryReporter(WEBSITE_BASE_URL, false);

        // Build a synthetic ImprovementResult
        List<ImprovementProposal> proposals = List.of(
            buildProposal(ImprovementTier.TIER_1_AUTOMATIC, GenericRule.RuleCategory.CONVERGENCE_DEFAULT, 0.90),
            buildProposal(ImprovementTier.TIER_2_REVIEW, GenericRule.RuleCategory.ANTI_PATTERN, 0.75)
        );

        ImprovementResult result = new ImprovementResult(
            "test-swarm", 5, 2, 1, 1, 0, 500, proposals
        );

        // Build telemetry report
        TelemetryReporter.TelemetryReport report = reporter.buildReport(result);

        assertNotNull(report);
        assertNotNull(report.installationId());
        assertEquals(2, report.proposals().size());

        // Verify the telemetry can be converted to the website format
        Map<String, Object> websitePayload = convertTelemetryToWebsiteFormat(report);

        @SuppressWarnings("unchecked")
        Map<String, Object> improvementData = (Map<String, Object>) websitePayload.get("improvementData");
        assertEquals("swarmai-improvements", improvementData.get("exportFormat"));

        @SuppressWarnings("unchecked")
        List<?> improvements = (List<?>) improvementData.get("improvements");
        assertEquals(2, improvements.size(),
            "All telemetry proposals should be converted to website improvements");
    }

    // ================================================================
    // Helpers
    // ================================================================

    private ExecutionTrace buildRealisticTrace() {
        WorkflowShape shape = new WorkflowShape(
            3, 2, true, false, true,
            Set.of("WEB", "DATA"), "SELF_IMPROVING", 2, 3.0, true, false
        );

        return ExecutionTrace.builder()
            .swarmId("integration-test-swarm-" + UUID.randomUUID().toString().substring(0, 8))
            .workflowShape(shape)
            .modelName("test-model")
            .totalPromptTokens(5000).totalCompletionTokens(2500)
            .totalDuration(Duration.ofSeconds(30))
            .iterationCount(3).convergedAtIteration(2)
            .addTaskTrace(new TaskTrace("task-1", "Analyst", true, 3000, 1500, 3,
                List.of("web_search"), null, Duration.ofSeconds(10)))
            .addTaskTrace(new TaskTrace("task-2", "Writer", true, 2000, 1000, 2,
                List.of("file_write"), null, Duration.ofSeconds(8)))
            .addToolCall(new ToolCallTrace("web_search", "task-1", true, 500, 200))
            .addToolCall(new ToolCallTrace("web_search", "task-1", false, 600, 0))
            .addToolCall(new ToolCallTrace("web_search", "task-1", true, 400, 150))
            .addSkillTrace(new SkillTrace("s1", "data_parser", "Parse financial data", true, 3, 0.85))
            .build();
    }

    private Map<String, Object> buildContributionPayload() {
        List<Map<String, Object>> improvements = List.of(
            Map.of(
                "category", "CONVERGENCE_DEFAULT",
                "tier", "TIER_1_AUTO",
                "condition", Map.of("task_count", "<=3", "process_type", "SELF_IMPROVING"),
                "confidence", 0.90,
                "recommendation", "Set maxIterations=3 for shallow self-improving workflows",
                "crossValidated", true,
                "supportingObservations", 5,
                "estimatedTokenSavings", 1500
            ),
            Map.of(
                "category", "ANTI_PATTERN",
                "tier", "TIER_2_REVIEWED",
                "condition", Map.of("has_skill_gen", true, "max_depth", "<=2"),
                "confidence", 0.78,
                "recommendation", "Warn when agents spin >5 turns without tool calls in self-improving mode",
                "crossValidated", true,
                "supportingObservations", 3,
                "estimatedTokenSavings", 800
            )
        );

        return Map.of(
            "improvementData", Map.of(
                "exportFormat", "swarmai-improvements",
                "frameworkVersion", "1.0.0-SNAPSHOT",
                "improvements", improvements
            ),
            "organizationName", "SwarmAI Integration Test",
            "contactEmail", "",
            "notes", "Automated integration test submission from test suite"
        );
    }

    private ImprovementProposal buildProposal(ImprovementTier tier, GenericRule.RuleCategory category, double confidence) {
        WorkflowShape shape = new WorkflowShape(3, 2, false, false, false,
            Set.of("WEB"), "SEQUENTIAL", 2, 1.0, true, false);
        SpecificObservation obs = new SpecificObservation(
            UUID.randomUUID().toString(), ObservationType.CONVERGENCE_PATTERN,
            shape, "Test observation", Map.of("converged_at", 2), 0.7, Instant.now());
        GenericRule.ValidationResult validation = GenericRule.ValidationResult.passed(5, 4, List.of("Matched 4/5"));
        GenericRule rule = new GenericRule(
            UUID.randomUUID().toString(), category,
            Map.of("task_count", "<=3"), "Test recommendation", 3,
            confidence, List.of(obs, obs, obs), validation, Instant.now(), Instant.now());
        return ImprovementProposal.builder()
            .rule(rule).tier(tier)
            .improvement(new ImprovementProposal.Improvement(
                "intelligence/convergence-defaults.json", "maxIterations", 5, 3,
                "Reduce iterations for shallow workflows", Map.of()))
            .origin(new ImprovementProposal.Origin(
                "SELF_IMPROVING", "convergence", 1500, 0.15, 5, List.of("test-swarm")))
            .status(ImprovementProposal.ProposalStatus.VALIDATED)
            .build();
    }

    /**
     * Converts a TelemetryReporter.TelemetryReport to the website's expected format.
     * This is the bridge between the framework's telemetry format and the website API.
     */
    private Map<String, Object> convertTelemetryToWebsiteFormat(TelemetryReporter.TelemetryReport report) {
        List<Map<String, Object>> improvements = report.proposals().stream()
            .map(p -> Map.<String, Object>of(
                "category", p.category(),
                "tier", p.tier(),
                "condition", p.condition(),
                "confidence", p.confidence(),
                "recommendation", p.recommendation(),
                "crossValidated", p.crossValidated(),
                "supportingObservations", p.supportingObservations(),
                "estimatedTokenSavings", p.estimatedTokenSavings()
            ))
            .toList();

        return Map.of(
            "improvementData", Map.of(
                "exportFormat", "swarmai-improvements",
                "frameworkVersion", report.frameworkVersion(),
                "installationId", report.installationId(),
                "improvements", improvements
            ),
            "organizationName", "Automated Telemetry (installation: " + report.installationId() + ")",
            "notes", "Submitted via TelemetryReporter"
        );
    }
}
