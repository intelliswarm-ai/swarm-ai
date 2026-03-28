package ai.intelliswarm.swarmai.skill;

import java.util.*;

/**
 * A rich, self-describing skill definition using the SKILL.md format.
 *
 * A SKILL.md IS the skill — it contains both metadata (YAML frontmatter)
 * and behavioral instructions (markdown body). This class captures that pattern in Java:
 *
 * <pre>
 * ---
 * name: financial-analysis
 * description: Analyzes financial data and generates investment recommendations
 * type: HYBRID
 * triggerWhen: User asks about stock analysis, financial metrics, or investment decisions
 * avoidWhen: User asks about non-financial topics
 * category: analysis
 * tags: [finance, stocks, investment, analysis]
 * requires:
 *   env: [ALPHA_VANTAGE_API_KEY]
 * outputFormat:
 *   type: markdown
 *   sections: [Summary, Metrics, Recommendation]
 * ---
 *
 * # Financial Analysis Skill
 *
 * You are a senior financial analyst. When given a stock ticker or company name:
 *
 * ## Workflow
 * 1. Use `web_search` to find recent news and financial data
 * 2. Use `calculator` to compute key metrics (P/E ratio, growth rate, etc.)
 * 3. Synthesize findings into a structured analysis
 *
 * ## Output Format
 * Always structure your response with:
 * - **Summary**: 2-3 sentence overview
 * - **Metrics**: Key financial metrics in a table
 * - **Recommendation**: Buy/Hold/Sell with confidence level
 *
 * ## Rules
 * - Never fabricate financial data
 * - Mark estimates as [ESTIMATE]
 * - Include data sources
 * </pre>
 *
 * The body section above serves as the LLM's instructions for PROMPT and HYBRID types,
 * while CODE types have their executable code in the `code` field instead.
 */
public class SkillDefinition {

    // ==================== Frontmatter (Metadata) ====================
    private String name;
    private String description;
    private SkillType type;
    private String triggerWhen;
    private String avoidWhen;
    private String category;
    private List<String> tags;
    private Map<String, List<String>> requires; // env, bins, services, os
    private OutputFormat outputFormat;

    // ==================== Body (Behavioral Definition) ====================

    /**
     * The skill's instructions/prompt body (for PROMPT and HYBRID types).
     * This is the markdown content that gets injected into the agent's system prompt
     * when the skill is active. The SKILL.md body after the frontmatter.
     */
    private String instructionBody;

    /**
     * The skill's executable code (for CODE and HYBRID types).
     * Groovy script that runs in a sandboxed environment.
     */
    private String code;

    // ==================== Supporting Artifacts ====================

    /**
     * Reference documents the skill can consult during execution.
     * Corresponds to the references/ directory.
     * Key: document name, Value: document content.
     * Available in code as `references.get("name")` and in prompts as injected context.
     */
    private Map<String, String> references;

    /**
     * Resource files: templates, specs, schemas the skill uses for output.
     * Corresponds to the resources/ directory.
     * Key: resource name, Value: resource content.
     * Example: {"output-template": "# Report for {{ticker}}\n## Summary\n..."}
     */
    private Map<String, String> resources;

    /**
     * Example inputs and expected outputs for the skill.
     * Corresponds to the examples/ directory. Used for few-shot prompting.
     */
    private List<SkillExample> examples;

    /**
     * Self-check items — quality checklist auto-evaluated after generation.
     * Corresponds to SELF_CHECK.md.
     */
    private List<String> selfCheckItems;

    /**
     * Test cases for CODE and HYBRID skills.
     */
    private List<String> testCases;

    /**
     * Routing table for COMPOSITE skills.
     * Maps intent patterns to sub-skill names.
     * Example: {"stock analysis" -> "stock-analyzer", "portfolio review" -> "portfolio-reviewer"}
     */
    private Map<String, String> routingTable;

    // ==================== Advanced Patterns ====================

    /**
     * Hard constraints / "red lines" the skill must never violate.
     * Inspired by ClawCV's sub-skill pattern where each skill has explicit do-not rules.
     * Example: ["Never fabricate financial data", "Never execute trades without confirmation"]
     */
    private List<String> constraints;

    /**
     * Capability sandbox — what the skill is allowed and denied to do.
     * Inspired by Capability Evolver's allow/deny lists.
     * Keys: "allowExecute", "denyExecute", "allowNetwork", "denyNetwork",
     *        "allowFileRead", "allowFileWrite"
     */
    private Map<String, List<String>> capabilities;

    /**
     * Agent personas for COMPOSITE multi-agent skills.
     * Inspired by Council of High Intelligence pattern where each agent is defined
     * with identity, analytical method, blind spots, and grounding protocol.
     * Key: agent name, Value: persona definition (markdown).
     */
    private Map<String, AgentPersona> agentPersonas;

    /**
     * Scripts that the skill can execute.
     * Corresponds to the scripts/ directory pattern.
     * Key: script filename, Value: script content.
     * Referenced in instructions via {baseDir}/scripts/filename.
     */
    private Map<String, String> scripts;

    /**
     * Multi-round deliberation protocol for COMPOSITE multi-agent skills.
     * Inspired by Council's 3-round pattern: parallel analysis, cross-examination, synthesis.
     */
    private List<DeliberationRound> deliberationProtocol;

    /**
     * Fallback behavior when the skill's preferred execution mode is unavailable.
     * Inspired by Account Handoff Builder's graceful degradation pattern.
     * Example: "If scripts cannot execute, produce text-only output using the output template."
     */
    private String fallbackBehavior;

    // ==================== Constructors ====================

    public SkillDefinition() {
        this.type = SkillType.CODE;
        this.tags = new ArrayList<>();
        this.requires = new LinkedHashMap<>();
        this.references = new LinkedHashMap<>();
        this.resources = new LinkedHashMap<>();
        this.examples = new ArrayList<>();
        this.selfCheckItems = new ArrayList<>();
        this.testCases = new ArrayList<>();
        this.routingTable = new LinkedHashMap<>();
        this.category = "generated";
        this.constraints = new ArrayList<>();
        this.capabilities = new LinkedHashMap<>();
        this.agentPersonas = new LinkedHashMap<>();
        this.scripts = new LinkedHashMap<>();
        this.deliberationProtocol = new ArrayList<>();
    }

    /**
     * Render the full skill definition as a SKILL.md-compatible string.
     * This is the canonical serialization format — human-readable and LLM-parseable.
     */
    public String toSkillMd() {
        StringBuilder sb = new StringBuilder();

        // Frontmatter
        sb.append("---\n");
        sb.append("name: ").append(name).append("\n");
        sb.append("description: ").append(description).append("\n");
        sb.append("type: ").append(type.name()).append("\n");
        if (triggerWhen != null) sb.append("triggerWhen: ").append(triggerWhen).append("\n");
        if (avoidWhen != null) sb.append("avoidWhen: ").append(avoidWhen).append("\n");
        sb.append("category: ").append(category).append("\n");
        if (!tags.isEmpty()) sb.append("tags: [").append(String.join(", ", tags)).append("]\n");
        if (!requires.isEmpty()) {
            sb.append("requires:\n");
            requires.forEach((k, v) -> sb.append("  ").append(k).append(": [")
                .append(String.join(", ", v)).append("]\n"));
        }
        if (outputFormat != null) {
            sb.append("outputFormat:\n");
            sb.append("  type: ").append(outputFormat.type()).append("\n");
            sb.append("  description: ").append(outputFormat.description()).append("\n");
            if (outputFormat.sections() != null && !outputFormat.sections().isEmpty()) {
                sb.append("  sections: [").append(String.join(", ", outputFormat.sections())).append("]\n");
            }
        }
        sb.append("---\n\n");

        // Body
        if (instructionBody != null && !instructionBody.isBlank()) {
            sb.append(instructionBody).append("\n\n");
        }

        // Code block (for CODE and HYBRID)
        if (code != null && !code.isBlank()) {
            sb.append("## Code\n```groovy\n").append(code).append("\n```\n\n");
        }

        // References
        if (!references.isEmpty()) {
            sb.append("## References\n");
            references.forEach((name, content) -> {
                sb.append("### ").append(name).append("\n");
                sb.append(content).append("\n\n");
            });
        }

        // Resources
        if (!resources.isEmpty()) {
            sb.append("## Resources\n");
            resources.forEach((name, content) -> {
                sb.append("### ").append(name).append("\n");
                sb.append("```\n").append(content).append("\n```\n\n");
            });
        }

        // Examples
        if (!examples.isEmpty()) {
            sb.append("## Examples\n");
            for (SkillExample ex : examples) {
                sb.append("### ").append(ex.name()).append("\n");
                sb.append("**Input:** ").append(ex.input()).append("\n");
                sb.append("**Expected Output:** ").append(ex.expectedOutput()).append("\n\n");
            }
        }

        // Constraints / Red Lines
        if (constraints != null && !constraints.isEmpty()) {
            sb.append("## Constraints (Red Lines)\n");
            for (String constraint : constraints) {
                sb.append("- ").append(constraint).append("\n");
            }
            sb.append("\n");
        }

        // Routing table (for COMPOSITE)
        if (!routingTable.isEmpty()) {
            sb.append("## Routing\n");
            sb.append("| Intent | Sub-Skill |\n|--------|----------|\n");
            routingTable.forEach((intent, subSkill) ->
                sb.append("| ").append(intent).append(" | ").append(subSkill).append(" |\n"));
            sb.append("\n");
        }

        // Agent Personas (for multi-agent COMPOSITE)
        if (agentPersonas != null && !agentPersonas.isEmpty()) {
            sb.append("## Agent Personas\n");
            for (Map.Entry<String, AgentPersona> entry : agentPersonas.entrySet()) {
                AgentPersona p = entry.getValue();
                sb.append("### ").append(p.name()).append(" (").append(p.role()).append(")\n");
                sb.append("**Identity:** ").append(p.identity()).append("\n");
                sb.append("**Method:** ").append(p.analyticalMethod()).append("\n");
                if (p.uniqueStrength() != null) sb.append("**Strength:** ").append(p.uniqueStrength()).append("\n");
                if (p.blindSpots() != null) sb.append("**Blind spots:** ").append(p.blindSpots()).append("\n");
                if (p.groundingProtocol() != null) sb.append("**Grounding:** ").append(p.groundingProtocol()).append("\n");
                if (p.tools() != null && !p.tools().isEmpty()) sb.append("**Tools:** ").append(String.join(", ", p.tools())).append("\n");
                if (p.modelTier() != null) sb.append("**Model:** ").append(p.modelTier()).append("\n");
                sb.append("\n");
            }
        }

        // Deliberation Protocol
        if (deliberationProtocol != null && !deliberationProtocol.isEmpty()) {
            sb.append("## Deliberation Protocol\n");
            int round = 1;
            for (DeliberationRound dr : deliberationProtocol) {
                sb.append("### Round ").append(round++).append(": ").append(dr.name());
                sb.append(" (").append(dr.executionMode()).append(")\n");
                sb.append(dr.promptTemplate()).append("\n\n");
            }
        }

        // Scripts
        if (scripts != null && !scripts.isEmpty()) {
            sb.append("## Scripts\n");
            for (Map.Entry<String, String> script : scripts.entrySet()) {
                String ext = script.getKey().endsWith(".py") ? "python" :
                             script.getKey().endsWith(".sh") ? "bash" : "";
                sb.append("### ").append(script.getKey()).append("\n");
                sb.append("```").append(ext).append("\n").append(script.getValue()).append("\n```\n\n");
            }
        }

        // Fallback behavior
        if (fallbackBehavior != null && !fallbackBehavior.isBlank()) {
            sb.append("## Fallback\n").append(fallbackBehavior).append("\n\n");
        }

        return sb.toString();
    }

    /**
     * Parse a SKILL.md-format string into a SkillDefinition.
     */
    public static SkillDefinition fromSkillMd(String skillMd) {
        SkillDefinition def = new SkillDefinition();
        if (skillMd == null || skillMd.isBlank()) return def;

        // Split frontmatter and body
        String content = skillMd.trim();
        if (content.startsWith("---")) {
            int endFrontmatter = content.indexOf("---", 3);
            if (endFrontmatter > 0) {
                String frontmatter = content.substring(3, endFrontmatter).trim();
                String body = content.substring(endFrontmatter + 3).trim();

                parseFrontmatter(def, frontmatter);
                parseBody(def, body);
            }
        } else {
            // No frontmatter — treat entire content as instruction body
            def.instructionBody = content;
        }

        return def;
    }

    private static void parseFrontmatter(SkillDefinition def, String frontmatter) {
        for (String line : frontmatter.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            int colonIdx = line.indexOf(':');
            if (colonIdx <= 0) continue;
            String key = line.substring(0, colonIdx).trim();
            String value = line.substring(colonIdx + 1).trim();

            switch (key) {
                case "name" -> def.name = value;
                case "description" -> def.description = value;
                case "type" -> {
                    try { def.type = SkillType.valueOf(value.toUpperCase()); }
                    catch (IllegalArgumentException e) { def.type = SkillType.CODE; }
                }
                case "triggerWhen" -> def.triggerWhen = value;
                case "avoidWhen" -> def.avoidWhen = value;
                case "category" -> def.category = value;
                case "tags" -> def.tags = parseList(value);
            }
        }
    }

    private static void parseBody(SkillDefinition def, String body) {
        // Extract code block if present
        int codeStart = body.indexOf("```groovy");
        if (codeStart == -1) codeStart = body.indexOf("```java");
        if (codeStart >= 0) {
            int contentStart = body.indexOf('\n', codeStart);
            int codeEnd = body.indexOf("```", contentStart + 1);
            if (contentStart >= 0 && codeEnd > contentStart) {
                def.code = body.substring(contentStart + 1, codeEnd).trim();
            }
        }

        // Everything before the code block (or the whole body) is instruction
        String instructionPart = codeStart >= 0 ? body.substring(0, codeStart).trim() : body.trim();
        if (!instructionPart.isBlank()) {
            // Strip the "## Code" header if present
            instructionPart = instructionPart.replaceAll("##\\s*Code\\s*$", "").trim();
            if (!instructionPart.isBlank()) {
                def.instructionBody = instructionPart;
            }
        }
    }

    private static List<String> parseList(String value) {
        // Parse [a, b, c] or a, b, c
        value = value.replaceAll("[\\[\\]]", "").trim();
        return Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(java.util.stream.Collectors.toList());
    }

    // ==================== Records ====================

    public record OutputFormat(String type, String description, List<String> sections, String example) {
        public OutputFormat(String type, String description) {
            this(type, description, List.of(), null);
        }
    }

    public record SkillExample(String name, String input, String expectedOutput) {}

    /**
     * Agent persona definition for multi-agent COMPOSITE skills.
     * Inspired by Council of High Intelligence's agent definition files.
     */
    public record AgentPersona(
        String name,
        String role,
        String identity,           // "You are X, an expert in Y..."
        String analyticalMethod,   // Step-by-step reasoning approach
        String uniqueStrength,     // "What you see that others miss"
        String blindSpots,         // Acknowledged weaknesses
        String groundingProtocol,  // Self-limiting rules
        List<String> tools,        // Tools this agent can use
        String modelTier           // "opus", "sonnet", "haiku"
    ) {}

    /**
     * A round in a multi-agent deliberation protocol.
     * Inspired by Council's 3-round pattern.
     */
    public record DeliberationRound(
        String name,               // "Independent Analysis", "Cross-Examination", "Synthesis"
        String executionMode,      // "PARALLEL" or "SEQUENTIAL"
        String promptTemplate,     // Prompt template with {{agent}}, {{input}}, {{previousRound}} placeholders
        int maxTokensPerAgent      // Token budget per agent per round
    ) {}

    // ==================== Builder ====================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final SkillDefinition def = new SkillDefinition();

        public Builder name(String name) { def.name = name; return this; }
        public Builder description(String desc) { def.description = desc; return this; }
        public Builder type(SkillType type) { def.type = type; return this; }
        public Builder triggerWhen(String t) { def.triggerWhen = t; return this; }
        public Builder avoidWhen(String a) { def.avoidWhen = a; return this; }
        public Builder category(String cat) { def.category = cat; return this; }
        public Builder tags(String... tags) { def.tags = new ArrayList<>(List.of(tags)); return this; }
        public Builder tags(List<String> tags) { def.tags = new ArrayList<>(tags); return this; }
        public Builder requireEnv(String... envVars) { def.requires.put("env", List.of(envVars)); return this; }
        public Builder requireBins(String... bins) { def.requires.put("bins", List.of(bins)); return this; }
        public Builder requireServices(String... svcs) { def.requires.put("services", List.of(svcs)); return this; }
        public Builder outputFormat(OutputFormat fmt) { def.outputFormat = fmt; return this; }
        public Builder outputFormat(String type, String desc) { def.outputFormat = new OutputFormat(type, desc); return this; }
        public Builder outputFormat(String type, String desc, List<String> sections) { def.outputFormat = new OutputFormat(type, desc, sections, null); return this; }
        public Builder instructionBody(String body) { def.instructionBody = body; return this; }
        public Builder code(String code) { def.code = code; return this; }
        public Builder reference(String name, String content) { def.references.put(name, content); return this; }
        public Builder resource(String name, String content) { def.resources.put(name, content); return this; }
        public Builder example(String name, String input, String output) { def.examples.add(new SkillExample(name, input, output)); return this; }
        public Builder selfCheck(String... items) { def.selfCheckItems.addAll(List.of(items)); return this; }
        public Builder testCase(String test) { def.testCases.add(test); return this; }
        public Builder route(String intent, String subSkillName) { def.routingTable.put(intent, subSkillName); return this; }
        public Builder constraint(String... constraints) { def.constraints.addAll(List.of(constraints)); return this; }
        public Builder allowExecute(String... cmds) { def.capabilities.computeIfAbsent("allowExecute", k -> new ArrayList<>()).addAll(List.of(cmds)); return this; }
        public Builder denyExecute(String... cmds) { def.capabilities.computeIfAbsent("denyExecute", k -> new ArrayList<>()).addAll(List.of(cmds)); return this; }
        public Builder allowNetwork(String... hosts) { def.capabilities.computeIfAbsent("allowNetwork", k -> new ArrayList<>()).addAll(List.of(hosts)); return this; }
        public Builder agentPersona(AgentPersona persona) { def.agentPersonas.put(persona.name(), persona); return this; }
        public Builder script(String filename, String content) { def.scripts.put(filename, content); return this; }
        public Builder deliberationRound(DeliberationRound round) { def.deliberationProtocol.add(round); return this; }
        public Builder fallbackBehavior(String fallback) { def.fallbackBehavior = fallback; return this; }

        public SkillDefinition build() {
            Objects.requireNonNull(def.name, "Skill name is required");
            Objects.requireNonNull(def.description, "Skill description is required");
            return def;
        }
    }

    // ==================== Getters & Setters ====================

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public SkillType getType() { return type; }
    public void setType(SkillType type) { this.type = type; }
    public String getTriggerWhen() { return triggerWhen; }
    public void setTriggerWhen(String triggerWhen) { this.triggerWhen = triggerWhen; }
    public String getAvoidWhen() { return avoidWhen; }
    public void setAvoidWhen(String avoidWhen) { this.avoidWhen = avoidWhen; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
    public Map<String, List<String>> getRequires() { return requires; }
    public void setRequires(Map<String, List<String>> requires) { this.requires = requires; }
    public OutputFormat getOutputFormat() { return outputFormat; }
    public void setOutputFormat(OutputFormat outputFormat) { this.outputFormat = outputFormat; }
    public String getInstructionBody() { return instructionBody; }
    public void setInstructionBody(String instructionBody) { this.instructionBody = instructionBody; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public Map<String, String> getReferences() { return references; }
    public void setReferences(Map<String, String> references) { this.references = references; }
    public Map<String, String> getResources() { return resources; }
    public void setResources(Map<String, String> resources) { this.resources = resources; }
    public List<SkillExample> getExamples() { return examples; }
    public void setExamples(List<SkillExample> examples) { this.examples = examples; }
    public List<String> getSelfCheckItems() { return selfCheckItems; }
    public void setSelfCheckItems(List<String> items) { this.selfCheckItems = items; }
    public List<String> getTestCases() { return testCases; }
    public void setTestCases(List<String> testCases) { this.testCases = testCases; }
    public Map<String, String> getRoutingTable() { return routingTable; }
    public void setRoutingTable(Map<String, String> routingTable) { this.routingTable = routingTable; }
    public List<String> getConstraints() { return constraints; }
    public void setConstraints(List<String> constraints) { this.constraints = constraints; }
    public Map<String, List<String>> getCapabilities() { return capabilities; }
    public void setCapabilities(Map<String, List<String>> capabilities) { this.capabilities = capabilities; }
    public Map<String, AgentPersona> getAgentPersonas() { return agentPersonas; }
    public void setAgentPersonas(Map<String, AgentPersona> agentPersonas) { this.agentPersonas = agentPersonas; }
    public Map<String, String> getScripts() { return scripts; }
    public void setScripts(Map<String, String> scripts) { this.scripts = scripts; }
    public List<DeliberationRound> getDeliberationProtocol() { return deliberationProtocol; }
    public void setDeliberationProtocol(List<DeliberationRound> protocol) { this.deliberationProtocol = protocol; }
    public String getFallbackBehavior() { return fallbackBehavior; }
    public void setFallbackBehavior(String fallbackBehavior) { this.fallbackBehavior = fallbackBehavior; }
}
