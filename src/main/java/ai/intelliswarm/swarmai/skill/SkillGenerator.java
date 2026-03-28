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
import java.util.stream.Collectors;

/**
 * Generates new skills from capability gap descriptions using an LLM.
 *
 * Now generates four types of skills:
 * - PROMPT: Pure instruction-based (domain expertise, analysis frameworks)
 * - CODE: Groovy script (data transformation, tool composition)
 * - HYBRID: Instructions + code (complex analysis)
 * - COMPOSITE: Router with sub-skills (multi-capability domains)
 *
 * The LLM decides the appropriate skill type based on the gap description.
 */
public class SkillGenerator {

    private static final Logger logger = LoggerFactory.getLogger(SkillGenerator.class);

    private final ChatClient chatClient;
    private Map<String, String> toolOutputSamples = new HashMap<>();

    public SkillGenerator(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public void discoverToolFormats(Map<String, BaseTool> tools) {
        for (Map.Entry<String, BaseTool> entry : tools.entrySet()) {
            String toolName = entry.getKey();
            BaseTool tool = entry.getValue();
            if (toolOutputSamples.containsKey(toolName)) continue;
            if (tool instanceof GeneratedSkill) {
                toolOutputSamples.put(toolName, "(String) — generated skill that composes other tools");
            } else {
                toolOutputSamples.put(toolName, describeToolFormat(toolName, tool.getDescription()));
            }
        }
    }

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
     * The LLM chooses the appropriate skill type (PROMPT, CODE, HYBRID, COMPOSITE).
     */
    public GeneratedSkill generate(String gapDescription, List<String> existingToolNames) {
        logger.info("Generating skill for gap: {}", gapDescription);

        String prompt = buildGenerationPrompt(gapDescription, existingToolNames);

        Agent generator = Agent.builder()
            .role("Skill Architect")
            .goal("Design and generate a skill definition that fills the described capability gap. " +
                  "Choose the most appropriate skill type (PROMPT, CODE, HYBRID, or COMPOSITE). " +
                  "Respond ONLY with the structured SKILL.md format requested.")
            .backstory("You are an expert skill designer who creates self-describing, composable skills. " +
                  "You understand that not every capability needs code — sometimes expert instructions " +
                  "for an LLM are more powerful than a Groovy script.")
            .chatClient(chatClient)
            .temperature(0.2)
            .verbose(false)
            .build();

        Task generationTask = Task.builder()
            .description(prompt)
            .expectedOutput("A complete SKILL.md definition with frontmatter, body, and optional code")
            .agent(generator)
            .maxExecutionTime(60000)
            .build();

        try {
            TaskOutput output = generationTask.execute(Collections.emptyList());
            String response = output.getRawOutput();
            return parseSkillDefinition(response, gapDescription);
        } catch (Exception e) {
            logger.error("Failed to generate skill for gap: {}", gapDescription, e);
            return null;
        }
    }

    /**
     * Refine a skill that failed validation.
     */
    public GeneratedSkill refine(GeneratedSkill failed, String validationErrors) {
        logger.info("Refining skill '{}' v{} due to: {}", failed.getName(), failed.getVersion(), validationErrors);

        StringBuilder prompt = new StringBuilder();
        prompt.append("The following skill failed validation:\n\n");
        prompt.append("```\n").append(failed.toSkillMd()).append("\n```\n\n");
        prompt.append("ERRORS: ").append(validationErrors).append("\n\n");
        prompt.append("Fix the skill. Remember:\n");
        prompt.append("- For CODE/HYBRID skills: use ONLY params, tools, references, resources bindings\n");
        prompt.append("- ALL tool.execute() calls return a plain String\n");
        prompt.append("- No file I/O, no network, no Runtime, no ProcessBuilder\n");
        prompt.append("- Code is a FLAT SCRIPT — no class definitions\n\n");
        prompt.append("Respond with the complete fixed SKILL.md (frontmatter + body).\n");

        Agent refiner = Agent.builder()
            .role("Skill Refiner")
            .goal("Fix the skill definition based on validation errors.")
            .backstory("You fix skill definitions efficiently, preserving the skill type and architecture.")
            .chatClient(chatClient)
            .temperature(0.1)
            .verbose(false)
            .build();

        Task refineTask = Task.builder()
            .description(prompt.toString())
            .expectedOutput("Fixed SKILL.md definition")
            .agent(refiner)
            .maxExecutionTime(60000)
            .build();

        try {
            TaskOutput output = refineTask.execute(Collections.emptyList());
            GeneratedSkill refined = parseSkillDefinition(output.getRawOutput(), failed.getDescription());
            if (refined != null) {
                refined.setVersion(SkillVersion.bumpPatch(failed.getVersion()));
            }
            return refined;
        } catch (Exception e) {
            logger.error("Failed to refine skill '{}'", failed.getName(), e);
            return null;
        }
    }

    private String buildGenerationPrompt(String gapDescription, List<String> existingToolNames) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("Generate a skill to fill this capability gap:\n\n");
        prompt.append("GAP: ").append(gapDescription).append("\n\n");

        prompt.append("SKILL TYPE RULES:\n");
        prompt.append("- ALWAYS generate CODE or HYBRID skills. NEVER generate PROMPT-only skills.\n");
        prompt.append("- CODE skills must compose existing tools to create new data-processing capabilities.\n");
        prompt.append("- The LLM already knows methodologies and frameworks — a PROMPT skill that teaches it what it already knows is USELESS.\n\n");

        prompt.append("SKILL TYPES — choose the most appropriate:\n\n");
        prompt.append("1. **CODE** (preferred) — Groovy script execution in a sandbox.\n");
        prompt.append("   Best for: data transformation, tool composition, computation pipelines, parsing output.\n");
        prompt.append("   Example: A skill that calls web_search + calculator to compute P/E ratios.\n\n");
        prompt.append("2. **HYBRID** — Instructions + code. Code gathers data, instructions guide reasoning.\n");
        prompt.append("   Best for: complex analysis needing both data processing and LLM reasoning.\n");
        prompt.append("   Example: Code fetches financial data, instructions tell LLM how to analyze it.\n\n");
        prompt.append("3. **COMPOSITE** — Router that dispatches to sub-skills.\n");
        prompt.append("   Best for: multi-capability domains with distinct sub-tasks.\n");
        prompt.append("   Example: 'finance' skill routes to 'analysis', 'reporting', 'alerts' sub-skills.\n\n");
        prompt.append("4. **PROMPT** (AVOID) — Pure instruction-based. Almost never useful.\n");
        prompt.append("   Only if there is absolutely no code component possible.\n\n");

        prompt.append("GOOD CODE SKILL EXAMPLES:\n\n");
        prompt.append("Example 1 — nmap output parser:\n");
        prompt.append("```groovy\n");
        prompt.append("def scanOutput = params.get(\"scanOutput\")\n");
        prompt.append("if (!scanOutput) { scanOutput = tools.shell_command.execute(Map.of(\"command\", \"nmap -sV --top-ports 100 \" + params.get(\"target\"))) }\n");
        prompt.append("def hosts = []\n");
        prompt.append("def currentHost = null\n");
        prompt.append("scanOutput.split(\"\\n\").each { line ->\n");
        prompt.append("    def hostMatch = (line =~ /Nmap scan report for (\\S+)/)\n");
        prompt.append("    if (hostMatch) { currentHost = [ip: hostMatch[0][1], ports: []] ; hosts << currentHost }\n");
        prompt.append("    def portMatch = (line =~ /(\\d+)\\/tcp\\s+open\\s+(\\S+)\\s*(.*)/)\n");
        prompt.append("    if (portMatch && currentHost) { currentHost.ports << [port: portMatch[0][1], service: portMatch[0][2], version: portMatch[0][3].trim()] }\n");
        prompt.append("}\n");
        prompt.append("return hosts.collect { h -> h.ip + \": \" + h.ports.collect { p -> p.port + \"/\" + p.service + (p.version ? \" (\" + p.version + \")\" : \"\") }.join(\", \") }.join(\"\\n\")\n");
        prompt.append("```\n\n");
        prompt.append("Example 2 — service vulnerability checker:\n");
        prompt.append("```groovy\n");
        prompt.append("def service = params.get(\"service\")\n");
        prompt.append("def version = params.get(\"version\")\n");
        prompt.append("def searchResult = tools.http_request.execute(Map.of(\"url\", \"https://services.nvd.nist.gov/rest/json/cves/2.0?keywordSearch=\" + java.net.URLEncoder.encode(service + \" \" + version, \"UTF-8\")))\n");
        prompt.append("return \"CVE search for \" + service + \" \" + version + \":\\n\" + searchResult\n");
        prompt.append("```\n\n");

        if (existingToolNames != null && !existingToolNames.isEmpty()) {
            prompt.append("EXISTING TOOLS (available via 'tools' map in CODE/HYBRID skills):\n");
            for (String name : existingToolNames) {
                prompt.append("  - tools.").append(name).append(".execute(Map.of(\"param\", value))\n");
            }
            prompt.append("\n");
        }

        if (!toolOutputSamples.isEmpty()) {
            prompt.append("TOOL OUTPUT FORMATS:\n");
            for (Map.Entry<String, String> sample : toolOutputSamples.entrySet()) {
                prompt.append("  tools.").append(sample.getKey()).append(".execute(...): ")
                    .append(sample.getValue()).append("\n");
            }
            prompt.append("\n");
        }

        prompt.append("BINDINGS AVAILABLE IN CODE:\n");
        prompt.append("  - params: Map<String, Object> — input parameters\n");
        prompt.append("  - tools: Map<String, BaseTool> — existing tools\n");
        prompt.append("  - references: Map<String, String> — reference documents\n");
        prompt.append("  - resources: Map<String, String> — templates and specs\n");
        prompt.append("  - log: Logger\n\n");

        prompt.append("TEMPLATE VARIABLES IN PROMPT SKILLS:\n");
        prompt.append("  Use {{paramName}} in the instruction body — will be replaced with parameter values.\n\n");

        prompt.append("CODE RULES (for CODE/HYBRID types):\n");
        prompt.append("- ALL tools return a plain String. Use regex or string operations to parse.\n");
        prompt.append("- You CAN use: groovy.json.JsonSlurper for JSON parsing, java.util.regex for regex, java.net.URLEncoder for URLs.\n");
        prompt.append("- Flat script (no class/function definitions). Last expression is the return value.\n");
        prompt.append("- No file I/O, no Runtime, no ProcessBuilder, no Socket.\n");
        prompt.append("- MUST include at least 2 test cases with real assertions.\n\n");

        prompt.append("Respond with a SKILL.md definition in EXACTLY this format:\n\n");
        prompt.append("```\n");
        prompt.append("---\n");
        prompt.append("name: snake_case_name\n");
        prompt.append("description: Detailed description of what this skill does (50+ chars)\n");
        prompt.append("type: CODE|HYBRID|COMPOSITE\n");
        prompt.append("triggerWhen: When should the LLM select this skill\n");
        prompt.append("avoidWhen: When should the LLM NOT select this skill\n");
        prompt.append("category: one of: data-io, web, computation, analysis, communication, generated\n");
        prompt.append("tags: [tag1, tag2, tag3]\n");
        prompt.append("---\n\n");

        prompt.append("# Skill Name\n\n");
        prompt.append("[For HYBRID: Write detailed LLM instructions here]\n");
        prompt.append("[For COMPOSITE: Include routing table and sub-skill descriptions]\n\n");

        prompt.append("## Code\n");
        prompt.append("```groovy\n");
        prompt.append("[For CODE/HYBRID: Write Groovy code here — REQUIRED]\n");
        prompt.append("```\n\n");

        prompt.append("## References\n");
        prompt.append("[Optional: Reference documents for the skill to consult]\n\n");

        prompt.append("## Resources\n");
        prompt.append("### output-template\n");
        prompt.append("[Optional: Template for structuring the output]\n\n");

        prompt.append("## Examples\n");
        prompt.append("[Optional: Input/output examples for few-shot prompting]\n\n");

        prompt.append("## Test Cases\n");
        prompt.append("```groovy\n");
        prompt.append("// Test 1: Basic execution\n");
        prompt.append("assert result != null && result instanceof String\n");
        prompt.append("assert result.length() > 10  // Must produce meaningful output\n");
        prompt.append("```\n");
        prompt.append("```groovy\n");
        prompt.append("// Test 2: Verify output structure\n");
        prompt.append("assert result.contains(\":\") || result.contains(\"\\n\")  // Must have structured output\n");
        prompt.append("```\n");
        prompt.append("```\n\n");

        prompt.append("IMPORTANT: Generate the SKILL.md content directly. Do NOT wrap it in additional markdown code fences.\n");

        return prompt.toString();
    }

    /**
     * Parse a SKILL.md-format response into a GeneratedSkill backed by a SkillDefinition.
     */
    GeneratedSkill parseSkillDefinition(String response, String fallbackDescription) {
        if (response == null || response.isBlank()) return null;

        // Try to find the SKILL.md content within the response
        String skillMd = extractSkillMd(response);

        // Parse using SkillDefinition's parser
        SkillDefinition def = SkillDefinition.fromSkillMd(skillMd);

        // Also try field-based extraction as fallback for LLMs that follow the old format
        if (def.getName() == null || def.getName().isBlank()) {
            String name = extractField(response, "NAME:");
            if (name != null) def.setName(name);
        }
        if (def.getDescription() == null || def.getDescription().isBlank()) {
            String desc = extractField(response, "DESCRIPTION:");
            if (desc != null) def.setDescription(desc);
            else def.setDescription(fallbackDescription);
        }
        if (def.getCode() == null || def.getCode().isBlank()) {
            String code = extractCodeBlock(response, "CODE:");
            if (code == null) code = extractCodeBlock(response, "## Code");
            if (code != null) def.setCode(code);
        }

        // Extract test cases
        String testCode = extractCodeBlock(response, "TEST_CASES:");
        if (testCode == null) testCode = extractCodeBlock(response, "## Test Cases");
        if (testCode != null && !testCode.isBlank()) {
            def.getTestCases().add(testCode);
        }

        // Extract routing info
        if (def.getTriggerWhen() == null) {
            String trigger = extractField(response, "TRIGGER_WHEN:");
            if (trigger == null) trigger = extractField(response, "triggerWhen:");
            if (trigger != null) def.setTriggerWhen(trigger);
        }
        if (def.getAvoidWhen() == null) {
            String avoid = extractField(response, "AVOID_WHEN:");
            if (avoid == null) avoid = extractField(response, "avoidWhen:");
            if (avoid != null) def.setAvoidWhen(avoid);
        }
        if (def.getCategory() == null || "generated".equals(def.getCategory())) {
            String cat = extractField(response, "CATEGORY:");
            if (cat == null) cat = extractField(response, "category:");
            if (cat != null) def.setCategory(cat.trim().toLowerCase());
        }
        if (def.getTags() == null || def.getTags().isEmpty()) {
            String tagsStr = extractField(response, "TAGS:");
            if (tagsStr == null) tagsStr = extractField(response, "tags:");
            if (tagsStr != null) {
                def.setTags(Arrays.stream(tagsStr.replaceAll("[\\[\\]]", "").split(","))
                    .map(String::trim).filter(t -> !t.isEmpty())
                    .collect(Collectors.toList()));
            }
        }

        // Extract references section
        extractReferences(response, def);

        // Extract resources section
        extractResources(response, def);

        // Extract examples section
        extractExamples(response, def);

        // Fallbacks
        if (def.getName() == null || def.getName().isBlank()) {
            def.setName("generated_skill_" + System.currentTimeMillis());
        }
        if (def.getDescription() == null || def.getDescription().isBlank()) {
            def.setDescription(fallbackDescription);
        }

        // Determine skill type if not explicitly set
        if (def.getType() == null) {
            def.setType(inferSkillType(def));
        }

        // Validate that the skill has the minimum required content for its type
        switch (def.getType()) {
            case CODE -> {
                if (def.getCode() == null || def.getCode().isBlank()) {
                    logger.warn("CODE skill has no code — falling back to PROMPT type");
                    if (def.getInstructionBody() != null && !def.getInstructionBody().isBlank()) {
                        def.setType(SkillType.PROMPT);
                    } else {
                        return null;
                    }
                }
            }
            case PROMPT -> {
                if (def.getInstructionBody() == null || def.getInstructionBody().isBlank()) {
                    // Try to use description as minimal instruction
                    def.setInstructionBody(def.getDescription());
                }
            }
            case HYBRID -> {
                if (def.getCode() == null && def.getInstructionBody() == null) {
                    return null;
                }
            }
            case COMPOSITE -> {
                // Composite skills can work with just routing info in the instruction body
            }
        }

        GeneratedSkill skill = new GeneratedSkill(def);
        logger.info("Generated {} skill: {} (category={}, tags={})",
            def.getType(), def.getName(), def.getCategory(), def.getTags());
        return skill;
    }

    private SkillType inferSkillType(SkillDefinition def) {
        boolean hasCode = def.getCode() != null && !def.getCode().isBlank();
        boolean hasInstructions = def.getInstructionBody() != null && !def.getInstructionBody().isBlank();
        boolean hasRouting = !def.getRoutingTable().isEmpty();

        if (hasRouting) return SkillType.COMPOSITE;
        if (hasCode && hasInstructions) return SkillType.HYBRID;
        if (hasCode) return SkillType.CODE;
        return SkillType.PROMPT;
    }

    private String extractSkillMd(String response) {
        // Try to find content between --- markers
        int firstDash = response.indexOf("---");
        if (firstDash >= 0) {
            // Check if it's inside a code fence
            int fenceBeforeDash = response.lastIndexOf("```", firstDash);
            if (fenceBeforeDash >= 0 && !response.substring(fenceBeforeDash, firstDash).contains("\n```")) {
                // The --- is inside a code fence, extract the content
                int fenceEnd = response.indexOf("```", firstDash);
                if (fenceEnd > firstDash) {
                    return response.substring(firstDash, fenceEnd).trim();
                }
            }
            return response.substring(firstDash).trim();
        }
        return response;
    }

    private void extractReferences(String response, SkillDefinition def) {
        int refStart = response.indexOf("## References");
        if (refStart < 0) return;

        int nextSection = findNextSection(response, refStart + 14);
        String refSection = response.substring(refStart + 14, nextSection).trim();

        // Parse ### name\ncontent blocks
        String[] parts = refSection.split("### ");
        for (String part : parts) {
            if (part.isBlank()) continue;
            int nl = part.indexOf('\n');
            if (nl > 0) {
                String refName = part.substring(0, nl).trim();
                String content = part.substring(nl + 1).trim();
                if (!refName.isBlank() && !content.isBlank()) {
                    def.getReferences().put(refName, content);
                }
            }
        }
    }

    private void extractResources(String response, SkillDefinition def) {
        int resStart = response.indexOf("## Resources");
        if (resStart < 0) return;

        int nextSection = findNextSection(response, resStart + 13);
        String resSection = response.substring(resStart + 13, nextSection).trim();

        String[] parts = resSection.split("### ");
        for (String part : parts) {
            if (part.isBlank()) continue;
            int nl = part.indexOf('\n');
            if (nl > 0) {
                String resName = part.substring(0, nl).trim();
                String content = part.substring(nl + 1).trim();
                // Strip code fences from resource content
                content = content.replaceAll("^```\\w*\\n?", "").replaceAll("\\n?```$", "").trim();
                if (!resName.isBlank() && !content.isBlank()) {
                    def.getResources().put(resName, content);
                }
            }
        }
    }

    private void extractExamples(String response, SkillDefinition def) {
        int exStart = response.indexOf("## Examples");
        if (exStart < 0) return;

        int nextSection = findNextSection(response, exStart + 11);
        String exSection = response.substring(exStart + 11, nextSection).trim();

        // Look for **Input:** / **Expected Output:** pairs
        Pattern inputPattern = Pattern.compile("\\*\\*Input:\\*\\*\\s*(.+?)(?=\\*\\*|$)", Pattern.DOTALL);
        Pattern outputPattern = Pattern.compile("\\*\\*(?:Expected )?Output:\\*\\*\\s*(.+?)(?=\\*\\*|###|$)", Pattern.DOTALL);

        Matcher inputMatcher = inputPattern.matcher(exSection);
        Matcher outputMatcher = outputPattern.matcher(exSection);

        int idx = 1;
        while (inputMatcher.find() && outputMatcher.find()) {
            def.getExamples().add(new SkillDefinition.SkillExample(
                "Example " + idx++,
                inputMatcher.group(1).trim(),
                outputMatcher.group(1).trim()
            ));
        }
    }

    private int findNextSection(String text, int fromIndex) {
        int next = text.indexOf("\n## ", fromIndex);
        return next >= 0 ? next : text.length();
    }

    // ==================== Legacy field extraction (backward compat) ====================

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
}
