package ai.intelliswarm.swarmai.skill;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.ToolRequirements;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ImportCustomizer;
import org.codehaus.groovy.control.customizers.SecureASTCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.*;

/**
 * A dynamically generated tool backed by a rich SkillDefinition.
 *
 * Supports four execution modes via multi-modal skill architecture:
 *
 * <b>PROMPT</b> — Pure instruction-based. The skill body is injected into the agent's
 * system prompt as expert instructions. The LLM follows the instructions to produce output.
 * No Groovy code runs. Best for: domain expertise, analysis frameworks, output formatting.
 *
 * <b>CODE</b> — Groovy script execution in a sandbox. Can compose existing tools via
 * the 'tools' binding. The original GeneratedSkill behavior. Best for: data transformation,
 * tool composition, computation pipelines.
 *
 * <b>HYBRID</b> — Combines instructions (for the LLM) with code (for execution). The code
 * runs first to gather/transform data, then the result plus the instructions guide the LLM
 * to produce the final output. Best for: complex analysis that needs both data processing
 * and reasoning.
 *
 * <b>COMPOSITE</b> — A router that dispatches to sub-skills based on input parameters.
 * The routing table maps intent patterns to child skills. Best for: multi-capability
 * domains (e.g., "finance" routes to analysis, reporting, alerts).
 *
 * Skills are persisted as directory-based packages:
 * <pre>
 * output/skills/financial-analysis/
 *   SKILL.md          # The full skill definition (frontmatter + body)
 *   _meta.json         # Registry metadata (version history, usage stats)
 *   references/        # Context documents the skill can consult
 *   resources/         # Templates, specs, schemas
 *   sub-skills/        # Nested sub-skill directories (for COMPOSITE)
 * </pre>
 */
public class GeneratedSkill implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(GeneratedSkill.class);
    private static final int MAX_RECURSION_DEPTH = 3;

    private static final ThreadLocal<Integer> recursionDepth = ThreadLocal.withInitial(() -> 0);

    // ==================== Core ====================
    private final String id;
    private final String name;
    private final LocalDateTime createdAt;

    /** The rich skill definition — contains all metadata, instructions, code, resources. */
    private SkillDefinition definition;

    // Runtime state
    private Map<String, BaseTool> availableTools = new HashMap<>();
    private SkillStatus status;
    private int usageCount;
    private int successCount;

    // Versioning
    private String version;
    private final List<SkillVersion> versionHistory = new ArrayList<>();

    // Sub-skills (for COMPOSITE type)
    private String parentSkillId;
    private final List<GeneratedSkill> subSkills = new ArrayList<>();

    // Quality
    private SkillQualityScore qualityScore;

    // ==================== Constructors ====================

    /**
     * Create from a full SkillDefinition (the new, rich way).
     */
    public GeneratedSkill(SkillDefinition definition) {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.definition = definition;
        this.name = sanitizeName(definition.getName());
        this.createdAt = LocalDateTime.now();
        this.status = SkillStatus.CANDIDATE;
        this.version = "1.0.0";
        this.usageCount = 0;
        this.successCount = 0;
    }

    /**
     * Backward-compatible constructor — wraps a code string into a CODE-type SkillDefinition.
     */
    public GeneratedSkill(String name, String description, String domain,
                          String code, Map<String, Object> parameterSchema,
                          List<String> testCases) {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.name = sanitizeName(name);
        this.createdAt = LocalDateTime.now();
        this.status = SkillStatus.CANDIDATE;
        this.version = "1.0.0";
        this.usageCount = 0;
        this.successCount = 0;

        // Build a SkillDefinition from the legacy constructor args
        this.definition = new SkillDefinition();
        this.definition.setName(name);
        this.definition.setDescription(description);
        this.definition.setType(SkillType.CODE);
        this.definition.setCode(code);
        this.definition.setCategory(domain != null ? domain : "generated");
        if (testCases != null) {
            this.definition.setTestCases(new ArrayList<>(testCases));
        }
    }

    // ==================== BaseTool Implementation ====================

    @Override
    public String getFunctionName() {
        return name;
    }

    @Override
    public String getDescription() {
        return definition.getDescription();
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        int depth = recursionDepth.get();
        if (depth >= MAX_RECURSION_DEPTH) {
            logger.warn("GeneratedSkill [{}]: Max recursion depth ({}) reached", name, MAX_RECURSION_DEPTH);
            return "Error: Max skill recursion depth reached";
        }

        logger.info("GeneratedSkill [{}] v{} ({}): Executing with {} params (depth {})",
            name, version, definition.getType(), parameters.size(), depth);
        usageCount++;
        recursionDepth.set(depth + 1);

        try {
            Object result = switch (definition.getType()) {
                case PROMPT -> executePrompt(parameters);
                case CODE -> executeCode(parameters);
                case HYBRID -> executeHybrid(parameters);
                case COMPOSITE -> executeComposite(parameters);
            };
            successCount++;
            return result != null ? result.toString() : "(no output)";
        } catch (Exception e) {
            logger.warn("GeneratedSkill [{}] execution failed: {}", name, e.getMessage());
            return "Error: Skill execution failed: " + e.getMessage();
        } finally {
            recursionDepth.set(depth);
        }
    }

    /**
     * PROMPT execution — returns the instruction body as context for the LLM.
     * The agent will inject this into its prompt and the LLM follows the instructions.
     * Includes constraints, references, examples, and output templates.
     */
    private Object executePrompt(Map<String, Object> parameters) {
        StringBuilder prompt = new StringBuilder();

        // Inject the skill's instruction body with parameter interpolation
        if (definition.getInstructionBody() != null) {
            String instructions = definition.getInstructionBody();
            for (Map.Entry<String, Object> param : parameters.entrySet()) {
                instructions = instructions.replace("{{" + param.getKey() + "}}",
                    param.getValue() != null ? param.getValue().toString() : "");
            }
            prompt.append(instructions);
        }

        // Inject hard constraints / red lines
        List<String> constraints = definition.getConstraints();
        if (constraints != null && !constraints.isEmpty()) {
            prompt.append("\n\n## CONSTRAINTS (Red Lines — NEVER violate these)\n");
            for (String constraint : constraints) {
                prompt.append("- ").append(constraint).append("\n");
            }
        }

        // Inject references as additional context
        if (!definition.getReferences().isEmpty()) {
            prompt.append("\n\n## Reference Documents\n");
            definition.getReferences().forEach((refName, content) -> {
                prompt.append("\n### ").append(refName).append("\n").append(content).append("\n");
            });
        }

        // Inject examples for few-shot prompting
        if (!definition.getExamples().isEmpty()) {
            prompt.append("\n\n## Examples\n");
            for (SkillDefinition.SkillExample example : definition.getExamples()) {
                prompt.append("\n**Input:** ").append(example.input());
                prompt.append("\n**Output:** ").append(example.expectedOutput()).append("\n");
            }
        }

        // Inject output template if available
        String outputTemplate = definition.getResources().get("output-template");
        if (outputTemplate != null) {
            prompt.append("\n\n## Output Template (follow this structure)\n").append(outputTemplate);
        }

        // Inject fallback behavior
        if (definition.getFallbackBehavior() != null) {
            prompt.append("\n\n## Fallback\n").append(definition.getFallbackBehavior());
        }

        return prompt.toString();
    }

    /**
     * CODE execution — runs Groovy code in a sandbox with tool bindings.
     */
    private Object executeCode(Map<String, Object> parameters) {
        CompilerConfiguration config = createSandboxedConfig();
        Binding binding = new Binding();

        binding.setVariable("params", parameters);
        binding.setVariable("tools", wrapToolsGStringSafe(availableTools));
        binding.setVariable("references", definition.getReferences());
        binding.setVariable("resources", definition.getResources());
        binding.setVariable("log", logger);

        GroovyShell shell = new GroovyShell(binding, config);
        return shell.evaluate(definition.getCode());
    }

    /**
     * HYBRID execution — runs code first, then returns code output + instructions
     * so the agent's LLM can reason over both.
     */
    private Object executeHybrid(Map<String, Object> parameters) {
        StringBuilder output = new StringBuilder();

        // Phase 1: Run code to gather/transform data
        if (definition.getCode() != null && !definition.getCode().isBlank()) {
            Object codeResult = executeCode(parameters);
            output.append("## Data (from code execution)\n");
            output.append(codeResult != null ? codeResult.toString() : "(no data)");
            output.append("\n\n");
        }

        // Phase 2: Append instructions for the LLM to reason over the data
        if (definition.getInstructionBody() != null) {
            output.append("## Analysis Instructions\n");
            String instructions = definition.getInstructionBody();
            for (Map.Entry<String, Object> param : parameters.entrySet()) {
                instructions = instructions.replace("{{" + param.getKey() + "}}",
                    param.getValue() != null ? param.getValue().toString() : "");
            }
            output.append(instructions);
        }

        // Phase 3: Append output template
        String outputTemplate = definition.getResources().get("output-template");
        if (outputTemplate != null) {
            output.append("\n\n## Output Template\n").append(outputTemplate);
        }

        return output.toString();
    }

    /**
     * COMPOSITE execution — routes to the matching sub-skill based on parameters.
     */
    private Object executeComposite(Map<String, Object> parameters) {
        String intent = parameters.getOrDefault("intent", "").toString().toLowerCase();
        String input = parameters.getOrDefault("input", "").toString().toLowerCase();
        String query = intent + " " + input;

        // Try routing table first
        for (Map.Entry<String, String> route : definition.getRoutingTable().entrySet()) {
            if (query.contains(route.getKey().toLowerCase())) {
                // Find the sub-skill
                for (GeneratedSkill sub : subSkills) {
                    if (sub.getName().equals(route.getValue()) ||
                        sub.getName().equals(sanitizeName(route.getValue()))) {
                        logger.info("COMPOSITE [{}] routing to sub-skill: {}", name, sub.getName());
                        sub.setAvailableTools(availableTools);
                        return sub.execute(parameters);
                    }
                }
            }
        }

        // Fallback: try matching sub-skill by name/description similarity
        for (GeneratedSkill sub : subSkills) {
            String subDesc = (sub.getDescription() + " " + sub.getName()).toLowerCase();
            if (subDesc.contains(intent) || query.contains(sub.getName().toLowerCase())) {
                logger.info("COMPOSITE [{}] fuzzy-routing to sub-skill: {}", name, sub.getName());
                sub.setAvailableTools(availableTools);
                return sub.execute(parameters);
            }
        }

        // No match — return the parent's instruction body as guidance
        if (definition.getInstructionBody() != null) {
            return executePrompt(parameters);
        }

        return "Error: No matching sub-skill found for intent: " + intent +
            ". Available sub-skills: " + subSkills.stream()
                .map(GeneratedSkill::getName)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    // ==================== Integration Test Execution ====================

    /**
     * Result of a single integration test execution.
     */
    public record IntegrationTestResult(
        String testName,
        boolean passed,
        String message,
        long durationMs,
        List<String> toolsCalled,
        List<String> missingToolCalls
    ) {
        public String summary() {
            return String.format("[%s] %s — %s (%dms, tools: %s%s)",
                passed ? "PASS" : "FAIL", testName, message, durationMs,
                toolsCalled.isEmpty() ? "none" : String.join(", ", toolsCalled),
                missingToolCalls.isEmpty() ? "" : ", MISSING: " + String.join(", ", missingToolCalls));
        }
    }

    /**
     * Aggregated results of all integration tests for this skill.
     */
    public record IntegrationTestResults(
        List<IntegrationTestResult> results,
        int passed,
        int failed,
        int total,
        long totalDurationMs
    ) {
        public boolean allPassed() { return failed == 0 && total > 0; }

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Integration Tests: %d/%d passed (%dms)\n", passed, total, totalDurationMs));
            for (IntegrationTestResult r : results) {
                sb.append("  ").append(r.summary()).append("\n");
            }
            return sb.toString();
        }

        public List<String> failureMessages() {
            return results.stream()
                .filter(r -> !r.passed())
                .map(IntegrationTestResult::summary)
                .collect(java.util.stream.Collectors.toList());
        }
    }

    /**
     * Run all integration tests defined in this skill's definition.
     *
     * Unlike unit-level testCases (which execute Groovy in isolation with mock tools),
     * integration tests exercise the full skill.execute() pipeline:
     *
     * 1. Wraps available tools with call-tracking proxies
     * 2. For each integration test:
     *    a. Prepares input params from the test definition
     *    b. Calls this.execute(params) — the real pipeline with real tools
     *    c. Runs Groovy assertion code against the actual output
     *    d. Verifies expected tool calls were made
     * 3. Returns structured results with pass/fail, timing, and tool call traces
     *
     * This makes each skill a self-verifiable package — anyone can call
     * skill.runIntegrationTests() to confirm it actually works.
     *
     * @return IntegrationTestResults with per-test results and aggregates
     */
    public IntegrationTestResults runIntegrationTests() {
        List<SkillDefinition.IntegrationTest> tests = definition.getIntegrationTests();
        if (tests == null || tests.isEmpty()) {
            return new IntegrationTestResults(List.of(), 0, 0, 0, 0);
        }

        List<IntegrationTestResult> results = new ArrayList<>();
        int passed = 0, failed = 0;
        long totalDuration = 0;

        for (SkillDefinition.IntegrationTest test : tests) {
            long start = System.currentTimeMillis();
            List<String> toolsCalled = Collections.synchronizedList(new ArrayList<>());

            try {
                // 1. Install tracking proxies around available tools
                Map<String, BaseTool> trackedTools = wrapToolsWithTracking(availableTools, toolsCalled);
                Map<String, BaseTool> originalTools = this.availableTools;
                this.availableTools = trackedTools;

                try {
                    // 2. Prepare input params
                    Map<String, Object> params = new HashMap<>();
                    if (test.inputParams() != null) {
                        params.putAll(test.inputParams());
                    }

                    // 3. Execute skill through the full pipeline
                    Object output = this.execute(params);
                    // Undo the usage/success count bump from the test execution
                    this.usageCount--;
                    this.successCount--;

                    String outputStr = output != null ? output.toString() : "";

                    // 4. Run assertion code
                    String assertionError = runAssertions(test.assertionCode(), outputStr);

                    // 5. Verify expected tool calls
                    List<String> missingToolCalls = new ArrayList<>();
                    if (test.expectedToolCalls() != null) {
                        for (String expected : test.expectedToolCalls()) {
                            if (!toolsCalled.contains(expected)) {
                                missingToolCalls.add(expected);
                            }
                        }
                    }

                    long duration = System.currentTimeMillis() - start;
                    totalDuration += duration;

                    if (assertionError != null) {
                        results.add(new IntegrationTestResult(test.name(), false,
                            "Assertion failed: " + assertionError, duration, new ArrayList<>(toolsCalled), missingToolCalls));
                        failed++;
                    } else if (!missingToolCalls.isEmpty()) {
                        results.add(new IntegrationTestResult(test.name(), false,
                            "Missing expected tool calls", duration, new ArrayList<>(toolsCalled), missingToolCalls));
                        failed++;
                    } else {
                        results.add(new IntegrationTestResult(test.name(), true,
                            "OK", duration, new ArrayList<>(toolsCalled), List.of()));
                        passed++;
                    }
                } finally {
                    // Restore original tools
                    this.availableTools = originalTools;
                }

            } catch (Exception e) {
                long duration = System.currentTimeMillis() - start;
                totalDuration += duration;
                results.add(new IntegrationTestResult(test.name(), false,
                    "Exception: " + e.getMessage(), duration, new ArrayList<>(toolsCalled), List.of()));
                failed++;
            }
        }

        IntegrationTestResults testResults = new IntegrationTestResults(
            results, passed, failed, passed + failed, totalDuration);

        logger.info("Integration tests for skill '{}': {}/{} passed ({}ms)",
            name, passed, passed + failed, totalDuration);

        return testResults;
    }

    /**
     * Wrap tools with tracking proxies that record which tools get called.
     */
    private Map<String, BaseTool> wrapToolsWithTracking(Map<String, BaseTool> tools, List<String> callLog) {
        Map<String, BaseTool> tracked = new HashMap<>();
        for (Map.Entry<String, BaseTool> entry : tools.entrySet()) {
            BaseTool original = entry.getValue();
            String toolName = entry.getKey();
            tracked.put(toolName, new BaseTool() {
                public String getFunctionName() { return original.getFunctionName(); }
                public String getDescription() { return original.getDescription(); }
                public Object execute(Map<String, Object> p) {
                    callLog.add(toolName);
                    Map<String, Object> safeParams = new HashMap<>();
                    p.forEach((k, v) -> safeParams.put(k, v != null ? v.toString() : null));
                    return original.execute(safeParams);
                }
                public Map<String, Object> getParameterSchema() { return original.getParameterSchema(); }
                public boolean isAsync() { return original.isAsync(); }
            });
        }
        return tracked;
    }

    /**
     * Run Groovy assertion code against an output string.
     * @return null if assertions pass, error message if they fail.
     */
    private String runAssertions(String assertionCode, String output) {
        if (assertionCode == null || assertionCode.isBlank()) return null;
        try {
            CompilerConfiguration config = createSandboxedConfig();
            Binding binding = new Binding();
            binding.setVariable("output", output);
            binding.setVariable("log", logger);
            GroovyShell shell = new GroovyShell(binding, config);
            shell.evaluate(assertionCode);
            return null; // All assertions passed
        } catch (AssertionError e) {
            return e.getMessage() != null ? e.getMessage() : "Assertion failed";
        } catch (Exception e) {
            return "Assertion error: " + e.getMessage();
        }
    }

    // ==================== Unit Test Execution ====================

    public Object executeTest(String testCode) {
        CompilerConfiguration config = createSandboxedConfig();
        Binding binding = new Binding();
        binding.setVariable("params", new HashMap<>());

        Map<String, Object> mockTools = new java.util.AbstractMap<String, Object>() {
            @Override
            public Object get(Object key) {
                return new BaseTool() {
                    public String getFunctionName() { return key.toString(); }
                    public String getDescription() { return "Mock tool for testing"; }
                    public Object execute(Map<String, Object> p) { return "Mock result for " + key + " with params: " + p; }
                    public Map<String, Object> getParameterSchema() { return Map.of(); }
                    public boolean isAsync() { return false; }
                };
            }
            @Override
            public java.util.Set<java.util.Map.Entry<String, Object>> entrySet() { return Set.of(); }
        };

        if (!availableTools.isEmpty()) {
            binding.setVariable("tools", wrapToolsGStringSafe(availableTools));
        } else {
            binding.setVariable("tools", mockTools);
        }
        binding.setVariable("references", definition.getReferences());
        binding.setVariable("resources", definition.getResources());
        binding.setVariable("log", logger);

        GroovyShell shell = new GroovyShell(binding, config);
        Object skillResult = shell.evaluate(definition.getCode());
        binding.setVariable("skillResult", skillResult);
        binding.setVariable("result", skillResult);
        return shell.evaluate(testCode);
    }

    // ==================== Sandbox ====================

    private CompilerConfiguration createSandboxedConfig() {
        CompilerConfiguration config = new CompilerConfiguration();
        ImportCustomizer imports = new ImportCustomizer();
        imports.addStarImports("java.util", "java.math", "groovy.json", "groovy.xml",
            "java.util.regex", "java.time");
        imports.addImports("java.net.URLEncoder", "java.net.URLDecoder");
        config.addCompilationCustomizers(imports);

        SecureASTCustomizer secure = new SecureASTCustomizer();
        secure.setDisallowedImports(List.of(
            "java.lang.Runtime", "java.lang.ProcessBuilder", "java.lang.Process",
            "java.io.File", "java.io.FileWriter", "java.io.FileReader",
            "java.io.FileInputStream", "java.io.FileOutputStream",
            "java.net.URL", "java.net.HttpURLConnection", "java.net.Socket",
            "java.lang.reflect.Method", "java.lang.reflect.Field",
            "groovy.lang.GroovyShell"
        ));
        secure.setDisallowedStaticImports(List.of(
            "java.lang.System.exit", "java.lang.Runtime.getRuntime"
        ));
        config.addCompilationCustomizers(secure);
        return config;
    }

    // ==================== BaseTool Enhanced Methods ====================

    @Override
    public Map<String, Object> getParameterSchema() {
        // Build from definition or infer from code
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();

        if (definition.getCode() != null) {
            // Infer from code: params.get("xxx")
            java.util.regex.Pattern paramPattern = java.util.regex.Pattern.compile("params\\.(?:get\\([\"']|)(\\w+)");
            java.util.regex.Matcher matcher = paramPattern.matcher(definition.getCode());
            Set<String> seen = new HashSet<>();
            while (matcher.find()) {
                String paramName = matcher.group(1);
                if (seen.add(paramName) && !paramName.equals("get")) {
                    properties.put(paramName, Map.of("type", "string", "description", "Parameter: " + paramName));
                }
            }
        }

        if (definition.getInstructionBody() != null) {
            // Infer from template: {{paramName}}
            java.util.regex.Pattern templatePattern = java.util.regex.Pattern.compile("\\{\\{(\\w+)\\}\\}");
            java.util.regex.Matcher matcher = templatePattern.matcher(definition.getInstructionBody());
            while (matcher.find()) {
                String paramName = matcher.group(1);
                properties.putIfAbsent(paramName, Map.of("type", "string", "description", "Template parameter: " + paramName));
            }
        }

        // COMPOSITE skills always need an "intent" parameter
        if (definition.getType() == SkillType.COMPOSITE) {
            properties.put("intent", Map.of("type", "string", "description", "Intent to route to a sub-skill"));
            properties.put("input", Map.of("type", "string", "description", "Input data for the sub-skill"));
        }

        schema.put("properties", properties);
        schema.put("required", properties.keySet().toArray(new String[0]));
        return schema;
    }

    @Override
    public boolean isAsync() { return false; }

    @Override
    public int getMaxResponseLength() { return 4000; }

    @Override
    public String getTriggerWhen() { return definition.getTriggerWhen(); }

    @Override
    public String getAvoidWhen() { return definition.getAvoidWhen(); }

    @Override
    public ToolRequirements getRequirements() {
        Map<String, List<String>> reqs = definition.getRequires();
        if (reqs == null || reqs.isEmpty()) return ToolRequirements.NONE;
        return new ToolRequirements(
            reqs.getOrDefault("env", List.of()),
            reqs.getOrDefault("bins", List.of()),
            reqs.getOrDefault("services", List.of()),
            reqs.getOrDefault("os", List.of())
        );
    }

    @Override
    public String getCategory() { return definition.getCategory(); }

    @Override
    public List<String> getTags() {
        return definition.getTags() != null ? definition.getTags() : List.of();
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        if (definition.getOutputFormat() != null) {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", definition.getOutputFormat().type());
            schema.put("description", definition.getOutputFormat().description());
            if (definition.getOutputFormat().sections() != null) {
                schema.put("sections", definition.getOutputFormat().sections());
            }
            return schema;
        }
        return Map.of("type", "string", "description", "Generated skill output");
    }

    // ==================== Tool Composition ====================

    public void setAvailableTools(Map<String, BaseTool> tools) {
        this.availableTools = tools != null ? tools : new HashMap<>();
    }

    public void addAvailableTool(BaseTool tool) {
        this.availableTools.put(tool.getFunctionName(), tool);
    }

    // ==================== Versioning ====================

    public String createNewVersion(String changeReason) {
        SkillVersion snapshot = new SkillVersion(
            this.version, getCode(), getDescription(),
            LocalDateTime.now(), changeReason, this.usageCount, this.successCount
        );
        versionHistory.add(0, snapshot);
        this.version = SkillVersion.bumpPatch(this.version);
        logger.info("Skill '{}' versioned: {} -> {} (reason: {})",
            name, snapshot.version(), this.version, changeReason);
        return this.version;
    }

    public Optional<SkillVersion> getVersion(String versionStr) {
        return versionHistory.stream().filter(v -> v.version().equals(versionStr)).findFirst();
    }

    // ==================== Sub-Skills ====================

    public void addSubSkill(GeneratedSkill subSkill) {
        subSkill.parentSkillId = this.id;
        subSkills.add(subSkill);
    }

    public boolean isParentSkill() { return !subSkills.isEmpty(); }
    public boolean isSubSkill() { return parentSkillId != null; }

    // ==================== Lifecycle ====================

    public void setStatus(SkillStatus status) { this.status = status; }

    public boolean meetsPromotionThreshold() {
        return usageCount >= 5 && getEffectiveness() >= 0.70;
    }

    public boolean meetsEnhancedPromotionThreshold() {
        if (!meetsPromotionThreshold()) return false;
        if (qualityScore == null) return true;
        return qualityScore.totalScore() >= 60;
    }

    public double getEffectiveness() {
        return usageCount > 0 ? (double) successCount / usageCount : 0.0;
    }

    // ==================== Setters ====================

    public void setTriggerWhen(String t) { definition.setTriggerWhen(t); }
    public void setAvoidWhen(String a) { definition.setAvoidWhen(a); }
    public void setCategory(String c) { definition.setCategory(c != null ? c : "generated"); }
    public void setTags(List<String> tags) { definition.setTags(tags != null ? tags : List.of()); }
    public void setReferences(Map<String, String> refs) { definition.setReferences(refs != null ? refs : Map.of()); }
    public void setResources(Map<String, String> res) { definition.setResources(res != null ? res : Map.of()); }
    public void setOutputSchema(Map<String, Object> schema) {
        if (schema != null) {
            definition.setOutputFormat(new SkillDefinition.OutputFormat(
                (String) schema.getOrDefault("type", "string"),
                (String) schema.getOrDefault("description", ""),
                schema.containsKey("sections") ? (List<String>) schema.get("sections") : List.of(),
                null
            ));
        }
    }
    public void setQualityScore(SkillQualityScore qs) { this.qualityScore = qs; }
    public void setVersion(String v) { this.version = v; }
    public void setParentSkillId(String pid) { this.parentSkillId = pid; }
    public void setRequirements(ToolRequirements reqs) {
        if (reqs != null && !reqs.isEmpty()) {
            Map<String, List<String>> reqMap = new LinkedHashMap<>();
            if (!reqs.env().isEmpty()) reqMap.put("env", reqs.env());
            if (!reqs.bins().isEmpty()) reqMap.put("bins", reqs.bins());
            if (!reqs.services().isEmpty()) reqMap.put("services", reqs.services());
            if (!reqs.os().isEmpty()) reqMap.put("os", reqs.os());
            definition.setRequires(reqMap);
        }
    }

    // ==================== Getters ====================

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDomain() { return definition.getCategory(); }
    public String getCode() { return definition.getCode(); }
    public List<String> getTestCases() { return definition.getTestCases(); }
    public SkillStatus getStatus() { return status; }
    public int getUsageCount() { return usageCount; }
    public int getSuccessCount() { return successCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public Map<String, BaseTool> getAvailableTools() { return availableTools; }
    public String getVersion() { return version; }
    public List<SkillVersion> getVersionHistory() { return Collections.unmodifiableList(versionHistory); }
    public Map<String, String> getReferences() { return definition.getReferences(); }
    public Map<String, String> getResources() { return definition.getResources(); }
    public String getParentSkillId() { return parentSkillId; }
    public List<GeneratedSkill> getSubSkills() { return Collections.unmodifiableList(subSkills); }
    public SkillQualityScore getQualityScore() { return qualityScore; }
    public SkillDefinition getDefinition() { return definition; }
    public SkillType getSkillType() { return definition.getType(); }
    public String getInstructionBody() { return definition.getInstructionBody(); }
    public List<SkillDefinition.IntegrationTest> getIntegrationTests() { return definition.getIntegrationTests(); }

    /**
     * Get the full SKILL.md representation of this skill.
     */
    public String toSkillMd() { return definition.toSkillMd(); }

    // ==================== Helpers ====================

    private Map<String, BaseTool> wrapToolsGStringSafe(Map<String, BaseTool> tools) {
        Map<String, BaseTool> safeTools = new HashMap<>();
        for (Map.Entry<String, BaseTool> entry : tools.entrySet()) {
            BaseTool original = entry.getValue();
            safeTools.put(entry.getKey(), new BaseTool() {
                public String getFunctionName() { return original.getFunctionName(); }
                public String getDescription() { return original.getDescription(); }
                public Object execute(Map<String, Object> p) {
                    Map<String, Object> safeParams = new HashMap<>();
                    p.forEach((k, v) -> safeParams.put(k, v != null ? v.toString() : null));
                    return original.execute(safeParams);
                }
                public Map<String, Object> getParameterSchema() { return original.getParameterSchema(); }
                public boolean isAsync() { return original.isAsync(); }
            });
        }
        return safeTools;
    }

    private String sanitizeName(String raw) {
        if (raw == null) return "unnamed_skill";
        return raw.toLowerCase()
            .replaceAll("[^a-z0-9_]", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_|_$", "");
    }

    @Override
    public String toString() {
        return String.format("GeneratedSkill{id='%s', name='%s', v%s, type=%s, status=%s, category='%s', " +
            "subSkills=%d, usage=%d/%d (%.0f%%), quality=%s}",
            id, name, version, definition.getType(), status, definition.getCategory(),
            subSkills.size(), successCount, usageCount, getEffectiveness() * 100,
            qualityScore != null ? qualityScore.grade() : "N/A");
    }
}
