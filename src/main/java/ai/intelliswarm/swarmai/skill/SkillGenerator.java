package ai.intelliswarm.swarmai.skill;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import ai.intelliswarm.swarmai.tool.base.BaseTool;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates new tools (skills) from capability gap descriptions using an LLM.
 *
 * Generated code is Groovy (Java-compatible) and can:
 * - Use full Java syntax and standard library
 * - Call existing tools via the 'tools' map (e.g., tools.web_scrape.execute(...))
 * - Access parameters via the 'params' map
 *
 * Uses dynamic tool discovery: probes each available tool with a sample call
 * to capture its actual return format, then includes those samples in the prompt
 * so the LLM generates code that correctly handles tool outputs.
 */
public class SkillGenerator {

    private static final Logger logger = LoggerFactory.getLogger(SkillGenerator.class);

    private final ChatClient chatClient;

    // Cached tool output samples from discovery
    private Map<String, String> toolOutputSamples = new HashMap<>();

    public SkillGenerator(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * Register tool output format descriptions for skill generation prompts.
     * Uses static descriptions derived from tool name and description — no live probing.
     * This is called once per skill generation cycle; results are cached.
     */
    public void discoverToolFormats(Map<String, BaseTool> tools) {
        for (Map.Entry<String, BaseTool> entry : tools.entrySet()) {
            String toolName = entry.getKey();
            BaseTool tool = entry.getValue();

            if (toolOutputSamples.containsKey(toolName)) continue;

            if (tool instanceof GeneratedSkill) {
                toolOutputSamples.put(toolName, "(String) — generated skill that composes other tools");
            } else {
                // Derive format description from tool metadata — no HTTP calls
                toolOutputSamples.put(toolName, describeToolFormat(toolName, tool.getDescription()));
            }
        }
    }

    /**
     * Describe a tool's output format based on its name and description.
     * Replaces live probing with static knowledge of known tool patterns.
     */
    private String describeToolFormat(String toolName, String description) {
        return switch (toolName) {
            case "web_search" -> "(String) — returns search results as text with titles, URLs, and snippets";
            case "web_scrape" -> "(String) — returns scraped HTML page content as plain text";
            case "http_request" -> "(String) — returns HTTP response body as text (may be HTML, JSON, or plain text)";
            case "calculator" -> "(String) — returns calculation result as text, e.g. '42.0'";
            case "shell_command" -> "(String) — returns stdout/stderr and exit code as text";
            case "file_read" -> "(String) — returns file content as text";
            case "file_write" -> "(String) — returns confirmation message";
            case "json_transform" -> "(String) — returns transformed JSON as text";
            case "xml_parse" -> "(String) — returns parsed XML content as text";
            case "csv_analysis" -> "(String) — returns CSV analysis results as text";
            case "sec_filings" -> "(String) — returns SEC filing data as formatted text";
            case "code_execution" -> "(String) — returns code execution output as text";
            case "directory_read" -> "(String) — returns directory listing as text";
            case "pdf_read" -> "(String) — returns PDF text content";
            case "data_analysis" -> "(String) — returns data analysis summary as text";
            default -> "(String) — returns " + (description != null ? description : "text output");
        };
    }

    /**
     * Generate a new skill from a capability gap description.
     */
    public GeneratedSkill generate(String gapDescription, List<String> existingToolNames) {
        logger.info("Generating skill for gap: {}", gapDescription);

        String prompt = buildGenerationPrompt(gapDescription, existingToolNames);

        Agent generator = Agent.builder()
            .role("Skill Generator")
            .goal("Generate a Groovy/Java function that fills the described capability gap. " +
                  "Respond ONLY with the structured format requested. No explanations.")
            .backstory("You are an expert Java/Groovy programmer who writes concise, correct code.")
            .chatClient(chatClient)
            .temperature(0.2)
            .verbose(false)
            .build();

        Task generationTask = Task.builder()
            .description(prompt)
            .expectedOutput("Structured skill definition with NAME, DESCRIPTION, CODE, and TEST_CASES sections")
            .agent(generator)
            .maxExecutionTime(60000)
            .build();

        try {
            TaskOutput output = generationTask.execute(Collections.emptyList());
            String response = output.getRawOutput();
            return parseGeneratedSkill(response, gapDescription);

        } catch (Exception e) {
            logger.error("Failed to generate skill for gap: {}", gapDescription, e);
            return null;
        }
    }

    /**
     * Refine a skill that failed validation.
     */
    public GeneratedSkill refine(GeneratedSkill failed, String validationErrors) {
        logger.info("Refining skill '{}' due to: {}", failed.getName(), validationErrors);

        String prompt = String.format(
            "The following Groovy skill failed validation:\n\n" +
            "NAME: %s\nDESCRIPTION: %s\n" +
            "CODE:\n```groovy\n%s\n```\n\n" +
            "ERRORS: %s\n\n" +
            "Fix the code. Remember:\n" +
            "- Use ONLY params (Map) for input and tools (Map) for existing tools\n" +
            "- ALL tool.execute() calls return a plain String (NOT JSON, NOT a Map)\n" +
            "- Do NOT use JsonSlurper to parse tool results — they return markdown/text\n" +
            "- Use String methods (.contains(), .split(), regex) to extract data from tool results\n" +
            "- No file I/O, no network, no Runtime, no ProcessBuilder\n" +
            "- Code is a FLAT SCRIPT — no function definitions, no class definitions\n" +
            "- The last expression is the return value (a String)\n\n" +
            "TEST_CASES must be simple:\n" +
            "- 'result' variable holds the skill output (a String)\n" +
            "- Use: assert result != null; assert result instanceof String; assert result.length() > 0\n\n" +
            "Respond with:\nNAME: ...\nDESCRIPTION: ...\nCODE:\n```groovy\n...\n```\nTEST_CASES:\n```groovy\n...\n```",
            failed.getName(), failed.getDescription(), failed.getCode(), validationErrors
        );

        Agent refiner = Agent.builder()
            .role("Skill Refiner")
            .goal("Fix the Groovy skill code based on the validation errors.")
            .backstory("You fix Java/Groovy code bugs efficiently.")
            .chatClient(chatClient)
            .temperature(0.1)
            .verbose(false)
            .build();

        Task refineTask = Task.builder()
            .description(prompt)
            .expectedOutput("Fixed skill definition")
            .agent(refiner)
            .maxExecutionTime(60000)
            .build();

        try {
            TaskOutput output = refineTask.execute(Collections.emptyList());
            return parseGeneratedSkill(output.getRawOutput(), failed.getDescription());
        } catch (Exception e) {
            logger.error("Failed to refine skill '{}'", failed.getName(), e);
            return null;
        }
    }

    private String buildGenerationPrompt(String gapDescription, List<String> existingToolNames) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("Generate a Groovy tool to fill this capability gap:\n\n");
        prompt.append("GAP: ").append(gapDescription).append("\n\n");

        if (existingToolNames != null && !existingToolNames.isEmpty()) {
            prompt.append("EXISTING TOOLS (available via 'tools' map — do NOT duplicate):\n");
            for (String name : existingToolNames) {
                prompt.append("  - tools.").append(name).append(".execute(Map.of(\"param\", value))\n");
            }
            prompt.append("\n");
        }

        prompt.append("AVAILABLE BINDINGS:\n");
        prompt.append("  - params: Map<String, Object> — input parameters\n");
        prompt.append("  - tools: Map<String, BaseTool> — existing tools you can call\n");
        prompt.append("  - log: Logger — for logging\n\n");

        prompt.append("CRITICAL — TOOL RETURN TYPES:\n");
        prompt.append("- ALL tools return a plain String (not JSON, not a Map, not an object)\n");
        prompt.append("- Do NOT use JsonSlurper to parse tool results — they are NOT JSON\n");
        prompt.append("- Instead, use String methods: .contains(), .split(), regex, etc.\n\n");

        // Include dynamically discovered tool output samples
        if (!toolOutputSamples.isEmpty()) {
            prompt.append("ACTUAL TOOL OUTPUT SAMPLES (from live probing):\n");
            for (Map.Entry<String, String> sample : toolOutputSamples.entrySet()) {
                prompt.append("  tools.").append(sample.getKey()).append(".execute(...) returns:\n");
                prompt.append("  \"\"\"").append(sample.getValue()).append("\"\"\"\n\n");
            }
        }


        prompt.append("RULES:\n");
        prompt.append("- Write Groovy/Java code. The last expression is the return value.\n");
        prompt.append("- You CAN call existing tools: tools.web_search.execute(Map.of(\"query\", q))\n");
        prompt.append("- You CAN use Java standard library: String, Math, List, Map, etc.\n");
        prompt.append("- You CAN use Groovy utilities: JsonOutput for OUTPUT formatting\n");
        prompt.append("- You CANNOT use: Runtime, ProcessBuilder, File I/O, network classes, reflection\n");
        prompt.append("- Keep it concise — under 30 lines.\n\n");

        prompt.append("IMPORTANT RULES FOR CODE STRUCTURE:\n");
        prompt.append("- Your code is a FLAT SCRIPT (not a class, not a function definition)\n");
        prompt.append("- Input comes from 'params' map: params.get(\"ticker\") or params.ticker\n");
        prompt.append("- Tools come from 'tools' map: tools.web_search.execute(Map.of(\"query\", q))\n");
        prompt.append("- The LAST EXPRESSION is the return value (return a String)\n");
        prompt.append("- Do NOT define a function with the same name as the skill\n");
        prompt.append("- Do NOT try to parse tool output as JSON — it is plain text\n\n");

        prompt.append("Respond in EXACTLY this format:\n\n");
        prompt.append("NAME: snake_case_name\n");
        prompt.append("DESCRIPTION: One line description\n");
        prompt.append("CODE:\n```groovy\n// Flat script — params and tools are pre-bound\ndef ticker = params.get(\"ticker\") ?: \"AAPL\"\ndef searchResult = tools.web_search.execute(Map.of(\"query\", ticker + \" financial data\"))\n// searchResult is a plain String — use string operations\ndef result = \"Analysis for \" + ticker + \":\\n\" + searchResult\nresult\n```\n");
        prompt.append("TEST_CASES:\n```groovy\n// The skill code has ALREADY executed — 'result' holds the return value\n// 'result' is a String — use simple assertions\nassert result != null\nassert result instanceof String\nassert result.length() > 0\n```\n");

        return prompt.toString();
    }

    /**
     * Parse the LLM's response into a GeneratedSkill.
     */
    GeneratedSkill parseGeneratedSkill(String response, String fallbackDescription) {
        String name = extractField(response, "NAME:");
        String description = extractField(response, "DESCRIPTION:");
        String code = extractCodeBlock(response, "CODE:");
        List<String> testCases = new ArrayList<>();
        String testCode = extractCodeBlock(response, "TEST_CASES:");
        if (testCode != null && !testCode.isEmpty()) {
            testCases.add(testCode);
        }

        // Fallbacks
        if (name == null || name.isEmpty()) name = "generated_skill_" + System.currentTimeMillis();
        if (description == null || description.isEmpty()) description = fallbackDescription;
        if (code == null || code.isEmpty()) {
            logger.warn("No code block found in LLM response for skill generation");
            return null;
        }

        Map<String, Object> schema = buildSchemaFromCode(code);

        return new GeneratedSkill(name, description, "generated", code, schema, testCases);
    }

    private String extractField(String text, String fieldName) {
        int idx = text.indexOf(fieldName);
        if (idx == -1) return null;

        int start = idx + fieldName.length();
        int end = text.indexOf('\n', start);
        if (end == -1) end = text.length();

        return text.substring(start, end).trim();
    }

    private String extractCodeBlock(String text, String sectionName) {
        int sectionIdx = text.indexOf(sectionName);
        if (sectionIdx == -1) return null;

        String after = text.substring(sectionIdx);

        // Find ```groovy ... ``` or ```java ... ``` or just ```
        int codeStart = after.indexOf("```groovy");
        if (codeStart == -1) codeStart = after.indexOf("```java");
        if (codeStart == -1) codeStart = after.indexOf("```");
        if (codeStart == -1) return null;

        int contentStart = after.indexOf('\n', codeStart);
        if (contentStart == -1) return null;

        int codeEnd = after.indexOf("```", contentStart + 1);
        if (codeEnd == -1) codeEnd = after.length();

        return after.substring(contentStart + 1, codeEnd).trim();
    }

    private Map<String, Object> buildSchemaFromCode(String code) {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        // Heuristic: look for params.get("xxx") or params.xxx references
        Map<String, Object> properties = new HashMap<>();
        Pattern paramPattern = Pattern.compile("params\\.(?:get\\([\"']|)(\\w+)");
        Matcher matcher = paramPattern.matcher(code);
        Set<String> seen = new HashSet<>();

        while (matcher.find()) {
            String paramName = matcher.group(1);
            if (seen.add(paramName) && !paramName.equals("get")) {
                properties.put(paramName, Map.of(
                    "type", "string",
                    "description", "Parameter: " + paramName
                ));
            }
        }

        schema.put("properties", properties);
        schema.put("required", seen.stream().filter(s -> !s.equals("get")).toArray(String[]::new));
        return schema;
    }
}
