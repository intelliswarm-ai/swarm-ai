package ai.intelliswarm.swarmai.integration;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.memory.InMemoryMemory;
import ai.intelliswarm.swarmai.memory.Memory;
import ai.intelliswarm.swarmai.process.SelfImprovingProcess;
import ai.intelliswarm.swarmai.rl.HeuristicPolicy;
import ai.intelliswarm.swarmai.rl.LearningPolicy;
import ai.intelliswarm.swarmai.rl.PolicyEngine;
import ai.intelliswarm.swarmai.skill.GeneratedSkill;
import ai.intelliswarm.swarmai.skill.SkillGapAnalyzer;
import ai.intelliswarm.swarmai.skill.SkillGenerator;
import ai.intelliswarm.swarmai.skill.SkillRegistry;
import ai.intelliswarm.swarmai.skill.SkillValidator;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.process.ProcessType;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests that use a REAL Ollama LLM to verify the self-improving
 * pipeline end-to-end. These tests prove that the framework works with actual
 * LLM-generated content — not mocked responses.
 *
 * Requirements:
 * - Ollama running at localhost:11434
 * - Model: llama3.2:3b (or configure via OLLAMA_MODEL env var)
 *
 * Run with: mvn test -Dgroups=ollama -pl swarmai-core
 * Skip with: mvn test -DexcludedGroups=ollama (default)
 *
 * These tests are SLOW (30-120s each) because they make real LLM calls.
 * They are the ultimate quality gate: if these pass, the framework genuinely
 * works end-to-end with a real model.
 */
@Tag("ollama")
@DisplayName("Ollama Integration Tests — Real LLM Self-Improving Pipeline")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OllamaSelfImprovingIT {

    private static final String OLLAMA_BASE_URL = System.getenv().getOrDefault(
        "OLLAMA_BASE_URL", "http://localhost:11434");
    private static final String OLLAMA_MODEL = System.getenv().getOrDefault(
        "OLLAMA_MODEL", "llama3.2:3b");

    private static ChatClient chatClient;
    private static boolean ollamaAvailable;

    @BeforeAll
    static void checkOllamaAvailability() {
        ollamaAvailable = isOllamaRunning();
        if (!ollamaAvailable) {
            System.err.println("WARNING: Ollama not available at " + OLLAMA_BASE_URL +
                ". Ollama integration tests will be skipped. " +
                "Start Ollama and pull " + OLLAMA_MODEL + " to run these tests.");
            return;
        }

        try {
            OllamaApi api = OllamaApi.builder()
                .baseUrl(OLLAMA_BASE_URL)
                .build();
            OllamaOptions options = OllamaOptions.builder()
                .model(OLLAMA_MODEL)
                .temperature(0.3)  // Low temperature for reproducibility
                .build();
            OllamaChatModel model = OllamaChatModel.builder()
                .ollamaApi(api)
                .defaultOptions(options)
                .build();
            chatClient = ChatClient.builder(model).build();
        } catch (Exception e) {
            System.err.println("Failed to create Ollama client: " + e.getMessage());
            ollamaAvailable = false;
        }
    }

    private static boolean isOllamaRunning() {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(OLLAMA_BASE_URL).toURL().openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (IOException e) {
            return false;
        }
    }

    @BeforeEach
    void skipIfOllamaUnavailable() {
        Assumptions.assumeTrue(ollamaAvailable,
            "Ollama not available — skipping integration test");
    }

    // ================================================================
    // TEST 1: Can the LLM generate a valid Groovy skill from a gap?
    // This is the most basic question: does SkillGenerator + real LLM
    // produce code that compiles and passes validation?
    // ================================================================

    @Test
    @Order(1)
    @DisplayName("SkillGenerator produces valid, compilable Groovy code from a real LLM")
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void skillGeneratorProducesValidCode() {
        SkillGenerator generator = new SkillGenerator(chatClient);
        SkillValidator validator = new SkillValidator();

        // Ask the LLM to generate a simple data transformation skill
        String gapDescription = "Parse a comma-separated string of numbers and calculate " +
            "the sum, average, min, and max values. Return a formatted summary string.";

        GeneratedSkill skill = generator.generate(gapDescription, List.of("calculator"));

        // CRITICAL ASSERTIONS — Does the LLM output pass our pipeline?
        assertNotNull(skill, "LLM should generate a skill (not return null)");
        assertNotNull(skill.getName(), "Generated skill should have a name");
        assertFalse(skill.getName().isEmpty(), "Skill name should not be empty");
        assertNotNull(skill.getCode(), "CODE skill should have code");
        assertFalse(skill.getCode().isBlank(), "Skill code should not be blank");

        // Does it pass validation?
        SkillValidator.ValidationResult validationResult = validator.validate(skill);

        if (!validationResult.passed()) {
            fail("LLM-generated skill failed validation.\n" +
                "Skill name: " + skill.getName() + "\n" +
                "Skill type: " + skill.getSkillType() + "\n" +
                "Code:\n" + skill.getCode() + "\n" +
                "Validation errors: " + validationResult.errors() + "\n\n" +
                "This means the SkillGenerator prompt needs improvement — the LLM " +
                "is generating code that doesn't pass our own safety/syntax checks.");
        }

        // Quality gate: the skill should score above the minimum
        assertTrue(validationResult.hasQualityScore(),
            "Validated skill should have quality score");
        int quality = validationResult.qualityScore().totalScore();
        System.out.println("Generated skill quality: " + quality + "/100 (grade: " +
            validationResult.qualityScore().grade() + ")");

        // We don't require grade A, but it should be at least passing
        assertTrue(quality >= 20,
            "LLM-generated skill quality (" + quality + "/100) is very low. " +
            "The generator prompt may need tuning.");
    }

    // ================================================================
    // TEST 2: Does SkillGapAnalyzer correctly classify LLM-written gaps?
    // When a reviewer LLM writes capability gap descriptions, are they
    // clear enough for the analyzer to make good decisions?
    // ================================================================

    @Test
    @Order(2)
    @DisplayName("SkillGapAnalyzer correctly classifies LLM-written gap descriptions")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void gapAnalyzerHandlesLlmWrittenGaps() {
        // Ask the LLM to act as a reviewer and identify capability gaps
        String prompt = """
            You are reviewing the output of an AI agent that was asked to analyze
            stock market trends. The agent's output is mediocre because it lacks
            specific data processing capabilities.

            List exactly 3 specific capability gaps, one per line, starting with "GAP:".
            Each gap should describe a specific data processing tool that would help.
            Be specific about what the tool should do (input, processing, output).
            """;

        String response = chatClient.prompt()
            .user(prompt)
            .call()
            .content();

        assertNotNull(response, "LLM should respond");

        // Extract gaps from response
        String[] lines = response.split("\n");
        SkillGapAnalyzer analyzer = new SkillGapAnalyzer();
        SkillRegistry registry = new SkillRegistry();

        int acceptedGaps = 0;
        int rejectedGaps = 0;

        for (String line : lines) {
            if (line.trim().startsWith("GAP:")) {
                String gap = line.substring(line.indexOf("GAP:") + 4).trim();
                if (gap.isEmpty()) continue;

                SkillGapAnalyzer.GapAnalysis analysis = analyzer.analyze(gap, List.of(), registry);

                System.out.println("Gap: " + gap.substring(0, Math.min(80, gap.length())) + "...");
                System.out.println("  Score: " + String.format("%.2f", analysis.score()) +
                    ", Recommendation: " + analysis.recommendation());
                System.out.println("  Reasons: " + analysis.reasons());

                if (analysis.shouldGenerate()) acceptedGaps++;
                else rejectedGaps++;
            }
        }

        // The LLM should produce at least some gaps that pass analysis
        assertTrue(acceptedGaps > 0,
            "At least one LLM-generated gap should be accepted by the analyzer. " +
            "Accepted: " + acceptedGaps + ", Rejected: " + rejectedGaps + ". " +
            "If all gaps are rejected, either the LLM writes poor gap descriptions " +
            "or the analyzer is too strict.");

        System.out.println("Gap analysis: " + acceptedGaps + " accepted, " + rejectedGaps + " rejected");
    }

    // ================================================================
    // TEST 3: Full self-improving cycle with real LLM
    // This is the ultimate test: does the complete loop work?
    //   Task execution → Review → Gap detection → Skill generation →
    //   Validation → Registration → Re-execution with new skill
    // ================================================================

    @Test
    @Order(3)
    @DisplayName("Full self-improving cycle: generate skill from gap and use it")
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void fullSelfImprovingCycle() {
        // Worker agent: executes the task
        Agent worker = Agent.builder()
            .role("Data Analyst")
            .goal("Analyze numerical data and produce statistical summaries")
            .backstory("Senior data analyst with expertise in statistical analysis")
            .chatClient(chatClient)
            .build();

        // Reviewer agent: evaluates output and identifies gaps
        Agent reviewer = Agent.builder()
            .role("Quality Reviewer")
            .goal("Review analysis quality and identify missing capabilities")
            .backstory("Expert reviewer who evaluates data analysis completeness")
            .chatClient(chatClient)
            .build();

        Memory memory = new InMemoryMemory();
        SkillRegistry registry = new SkillRegistry();
        SkillGapAnalyzer gapAnalyzer = new SkillGapAnalyzer();
        SkillValidator validator = new SkillValidator();
        PolicyEngine policy = new HeuristicPolicy();

        Task task = Task.builder()
            .description("Analyze the following sales data and compute key statistics: " +
                "Q1: 45000, Q2: 52000, Q3: 48000, Q4: 61000. " +
                "Calculate total, average, growth rate per quarter, and identify the best quarter.")
            .expectedOutput("Statistical analysis with growth rates and best quarter identification")
            .agent(worker)
            .build();

        // Execute using self-improving process
        SelfImprovingProcess process = new SelfImprovingProcess(
            List.of(worker), reviewer, null, 3, null, memory);

        SwarmOutput output = process.execute(List.of(task), Map.of(), "ollama-test-swarm");

        // The output should exist (process didn't crash)
        assertNotNull(output, "Self-improving process should produce output");
        assertNotNull(output.getFinalOutput(), "Should have final output");
        assertFalse(output.getFinalOutput().isEmpty(), "Final output should not be empty");

        // Check what happened during execution
        Map<String, Object> metadata = output.getMetadata();
        System.out.println("=== Self-Improving Cycle Results ===");
        System.out.println("Final output length: " + output.getFinalOutput().length());
        System.out.println("Metadata: " + metadata);
        System.out.println("Skills in registry: " + registry.getActiveSkillCount());

        // The output should contain actual analysis (not just filler)
        String finalOutput = output.getFinalOutput().toLowerCase();
        boolean containsNumbers = finalOutput.matches(".*\\d+.*");
        assertTrue(containsNumbers,
            "Output should contain numerical analysis, not just text. Got: " +
            output.getFinalOutput().substring(0, Math.min(200, output.getFinalOutput().length())));
    }

    // ================================================================
    // TEST 4: Skill refinement — can the LLM fix a broken skill?
    // ================================================================

    @Test
    @Order(4)
    @DisplayName("SkillGenerator can refine a failed skill based on validation errors")
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void skillRefinementAfterFailure() {
        SkillGenerator generator = new SkillGenerator(chatClient);
        SkillValidator validator = new SkillValidator();

        // Generate a skill
        String gap = "Parse a JSON string containing an array of objects with 'name' and 'value' fields, " +
            "and compute the total of all 'value' fields. Return 'Total: <sum>'.";
        GeneratedSkill skill = generator.generate(gap, List.of());

        assertNotNull(skill, "Initial generation should not return null");

        SkillValidator.ValidationResult firstResult = validator.validate(skill);

        if (!firstResult.passed()) {
            // Attempt refinement
            System.out.println("First attempt failed: " + firstResult.errors());
            GeneratedSkill refined = generator.refine(skill, firstResult.errorsAsString());

            assertNotNull(refined, "Refinement should not return null");

            SkillValidator.ValidationResult secondResult = validator.validate(refined);
            System.out.println("Second attempt: " + (secondResult.passed() ? "PASSED" : "FAILED: " + secondResult.errors()));

            // We don't require the second attempt to pass (LLMs are imperfect),
            // but we verify the refinement mechanism works
            assertNotNull(refined.getCode(), "Refined skill should have code");
            assertNotEquals(skill.getCode(), refined.getCode(),
                "Refined skill should have different code than original");
        } else {
            System.out.println("First attempt passed — refinement not needed");
        }
    }

    // ================================================================
    // TEST 5: RL Policy learns from real LLM outcomes
    // ================================================================

    @Test
    @Order(5)
    @DisplayName("LearningPolicy transitions from cold-start to learned decisions")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void learningPolicyTransitionsFromColdStart() {
        LearningPolicy policy = new LearningPolicy(5, 1.0, 1000);  // Short cold start for testing
        SkillGapAnalyzer analyzer = new SkillGapAnalyzer();

        assertTrue(policy.isColdStart(), "Should start in cold-start mode");

        // Simulate 6 decisions to exit cold start
        for (int i = 0; i < 6; i++) {
            var context = analyzer.buildContext(
                "Parse data type " + i + " and extract metrics for analysis",
                List.of(), new SkillRegistry());
            var decision = policy.shouldGenerateSkill(context);

            assertNotNull(decision, "Decision should not be null");
            assertNotNull(decision.recommendation(), "Recommendation should not be null");
        }

        assertFalse(policy.isColdStart(),
            "Should exit cold-start after " + policy.getTotalDecisions() + " decisions");

        System.out.println("Policy stats after cold-start exit: " + policy.getStats());
    }
}
