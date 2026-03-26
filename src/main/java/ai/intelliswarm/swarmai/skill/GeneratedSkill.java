package ai.intelliswarm.swarmai.skill;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
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
 * A dynamically generated tool backed by Groovy code.
 *
 * Groovy gives full Java interop — generated skills can:
 * - Use Java syntax (Groovy IS Java + extras)
 * - Call existing tools passed via the 'tools' binding (e.g., tools.web_scrape, tools.http_request)
 * - Use Java standard library (String, Math, List, Map, etc.)
 *
 * Sandboxed via SecureASTCustomizer to block dangerous operations.
 */
public class GeneratedSkill implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(GeneratedSkill.class);

    private final String id;
    private final String name;
    private final String description;
    private final String domain;
    private final String code;
    private final Map<String, Object> parameterSchema;
    private final List<String> testCases;
    private final LocalDateTime createdAt;

    // Tools that this skill can compose (populated at registration time)
    private Map<String, BaseTool> availableTools = new HashMap<>();

    private SkillStatus status;
    private int usageCount;
    private int successCount;

    public GeneratedSkill(String name, String description, String domain,
                          String code, Map<String, Object> parameterSchema,
                          List<String> testCases) {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.name = sanitizeName(name);
        this.description = description;
        this.domain = domain;
        this.code = code;
        this.parameterSchema = parameterSchema != null ? parameterSchema : Map.of();
        this.testCases = testCases != null ? testCases : List.of();
        this.createdAt = LocalDateTime.now();
        this.status = SkillStatus.CANDIDATE;
        this.usageCount = 0;
        this.successCount = 0;
    }

    @Override
    public String getFunctionName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        logger.info("GeneratedSkill [{}]: Executing with {} params", name, parameters.size());
        usageCount++;

        try {
            // Create sandboxed Groovy shell
            CompilerConfiguration config = createSandboxedConfig();
            Binding binding = new Binding();

            // Bind parameters
            binding.setVariable("params", parameters);

            // Bind available tools wrapped to handle Groovy GString→String conversion
            binding.setVariable("tools", wrapToolsGStringSafe(availableTools));

            // Bind utility helpers
            binding.setVariable("log", logger);

            GroovyShell shell = new GroovyShell(binding, config);
            Object result = shell.evaluate(code);
            successCount++;

            return result != null ? result.toString() : "(no output)";

        } catch (Exception e) {
            logger.warn("GeneratedSkill [{}] execution failed: {}", name, e.getMessage());
            return "Error: Skill execution failed: " + e.getMessage();
        }
    }

    /**
     * Execute code in a test context (used by SkillValidator).
     * Provides a safe proxy for tools that returns mock data instead of NPE.
     */
    public Object executeTest(String testCode) {
        CompilerConfiguration config = createSandboxedConfig();
        Binding binding = new Binding();
        binding.setVariable("params", new HashMap<>());

        // Provide a proxy tools map that returns a mock tool for any key
        // This prevents NPE when generated code calls tools.xxx.execute(...)
        Map<String, Object> mockTools = new java.util.AbstractMap<String, Object>() {
            @Override
            public Object get(Object key) {
                // Return a mock tool that returns sample data
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

        // Use GString-safe wrapped tools (same as execute()) or mock tools for testing
        if (!availableTools.isEmpty()) {
            binding.setVariable("tools", wrapToolsGStringSafe(availableTools));
        } else {
            binding.setVariable("tools", mockTools);
        }
        binding.setVariable("log", logger);

        GroovyShell shell = new GroovyShell(binding, config);

        // Run the skill code first — capture the result
        Object skillResult = shell.evaluate(code);
        // Make the result available to test code
        binding.setVariable("skillResult", skillResult);
        binding.setVariable("result", skillResult);
        // Run the test code
        return shell.evaluate(testCode);
    }

    /**
     * Create a sandboxed Groovy compiler configuration.
     * Blocks dangerous operations while allowing Java standard library usage.
     */
    private CompilerConfiguration createSandboxedConfig() {
        CompilerConfiguration config = new CompilerConfiguration();

        // Allow common imports
        ImportCustomizer imports = new ImportCustomizer();
        imports.addStarImports("java.util", "java.math", "groovy.json", "groovy.xml");
        config.addCompilationCustomizers(imports);

        // Security: block dangerous constructs
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

    @Override
    public Map<String, Object> getParameterSchema() {
        return parameterSchema;
    }

    @Override
    public boolean isAsync() {
        return false;
    }

    @Override
    public int getMaxResponseLength() {
        return 4000;
    }

    // ==================== Tool Composition ====================

    /**
     * Set the existing tools that this skill can call.
     * Called when the skill is registered in a workflow.
     */
    public void setAvailableTools(Map<String, BaseTool> tools) {
        this.availableTools = tools != null ? tools : new HashMap<>();
    }

    public void addAvailableTool(BaseTool tool) {
        this.availableTools.put(tool.getFunctionName(), tool);
    }

    // ==================== Lifecycle ====================

    public void setStatus(SkillStatus status) {
        this.status = status;
    }

    public boolean meetsPromotionThreshold() {
        return usageCount >= 5 && getEffectiveness() >= 0.70;
    }

    public double getEffectiveness() {
        return usageCount > 0 ? (double) successCount / usageCount : 0.0;
    }

    // ==================== Getters ====================

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDomain() { return domain; }
    public String getCode() { return code; }
    public List<String> getTestCases() { return testCases; }
    public SkillStatus getStatus() { return status; }
    public int getUsageCount() { return usageCount; }
    public int getSuccessCount() { return successCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public Map<String, BaseTool> getAvailableTools() { return availableTools; }

    // ==================== Helpers ====================

    /**
     * Wrap tools with GString→String conversion to prevent ClassCastException
     * when Groovy string interpolation produces GString instead of String.
     */
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
        return String.format("GeneratedSkill{id='%s', name='%s', status=%s, usage=%d/%d (%.0f%%)}",
            id, name, status, successCount, usageCount, getEffectiveness() * 100);
    }
}
