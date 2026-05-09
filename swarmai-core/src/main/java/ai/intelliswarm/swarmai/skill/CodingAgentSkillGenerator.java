package ai.intelliswarm.swarmai.skill;

import ai.intelliswarm.swarmai.agent.subagent.IsolatedWorkspace;
import ai.intelliswarm.swarmai.agent.subagent.Subagent;
import ai.intelliswarm.swarmai.agent.subagent.SubagentResult;
import ai.intelliswarm.swarmai.agent.subagent.SubagentSpawner;
import ai.intelliswarm.swarmai.agent.subagent.SubagentSpec;
import ai.intelliswarm.swarmai.skill.runtime.SkillSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@link SkillEngine} that delegates skill creation to an external coding
 * agent (Codex, Claude Code, etc.) running inside an
 * {@link IsolatedWorkspace}.
 *
 * <h2>Why this exists</h2>
 * The default {@link SkillGenerator} drives a {@code ChatClient} directly:
 * one prompt in, one SKILL.md text blob out. That works, but it bottoms out
 * at "what an LLM can produce in one shot." A coding agent can iterate —
 * read existing skills, run the validator, fix mistakes — producing
 * multi-file outputs that have actually been tried. This generator is the
 * adapter that lets you swap one for the other.
 *
 * <h2>The contract with the agent</h2>
 * The agent receives a prompt explaining the gap, the available tools, and
 * the SKILL.md format. It is told to write the final SKILL.md to
 * {@code <workspace-root>/SKILL.md} and exit zero. After the subagent
 * completes, this class reads that file and parses it via
 * {@link SkillGenerator#parseSkillDefinition} — the same parser used for
 * raw-LLM output, so loose formatting is tolerated.
 *
 * <h2>Workspace lifecycle (subtle)</h2>
 * {@link ai.intelliswarm.swarmai.agent.subagent.coding.ProcessSubagent}
 * closes the workspace <em>before</em> completing the result future (race
 * fix — callers that immediately query workspace state see it already gone).
 * That's the wrong contract for us: we need to read SKILL.md <em>after</em>
 * the agent terminates. We solve this by handing the spawner a
 * {@link NonClosingWorkspace} wrapper that no-ops {@code close()}, then
 * closing the inner workspace ourselves once we've read the file.
 */
public final class CodingAgentSkillGenerator implements SkillEngine {

    private static final Logger logger = LoggerFactory.getLogger(CodingAgentSkillGenerator.class);

    /** Path within the workspace where the agent must write the final SKILL.md. */
    public static final String SKILL_FILE_NAME = "SKILL.md";

    private final SubagentSpawner spawner;
    private final Supplier<IsolatedWorkspace> workspaceFactory;
    private final Duration timeout;
    private final String targetLanguage;
    private final SkillGenerator parser; // used only for parseSkillDefinition

    /**
     * @param spawner            spawner that runs the coding agent (e.g.
     *                           {@code CodexSubagentSpawner.create(...)})
     * @param workspaceFactory   produces a fresh {@link IsolatedWorkspace}
     *                           per skill-generation attempt
     * @param timeout            wall-clock cap on a single generate/refine call
     * @param targetLanguage     skill source language (see {@link SkillSource})
     */
    public CodingAgentSkillGenerator(SubagentSpawner spawner,
                                      Supplier<IsolatedWorkspace> workspaceFactory,
                                      Duration timeout,
                                      String targetLanguage) {
        this.spawner = Objects.requireNonNull(spawner, "spawner");
        this.workspaceFactory = Objects.requireNonNull(workspaceFactory, "workspaceFactory");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.targetLanguage = (targetLanguage == null || targetLanguage.isBlank())
                ? SkillSource.GROOVY : targetLanguage;
        // Parser-only SkillGenerator: the ChatClient is never used because
        // we never call generate()/refine() on it — only parseSkillDefinition().
        this.parser = new SkillGenerator(null, this.targetLanguage);
    }

    /** Convenience: defaults to Groovy. */
    public CodingAgentSkillGenerator(SubagentSpawner spawner,
                                      Supplier<IsolatedWorkspace> workspaceFactory,
                                      Duration timeout) {
        this(spawner, workspaceFactory, timeout, SkillSource.GROOVY);
    }

    @Override
    public GeneratedSkill generate(String gapDescription, List<String> existingToolNames) {
        Objects.requireNonNull(gapDescription, "gapDescription");
        logger.info("Coding-agent skill generation for gap: {}", gapDescription);

        String prompt = buildGeneratePrompt(gapDescription, existingToolNames);
        return runAgentAndParse("gen-" + shortId(), prompt, gapDescription, false, null);
    }

    @Override
    public GeneratedSkill refine(GeneratedSkill failed, String validationErrors) {
        Objects.requireNonNull(failed, "failed");
        Objects.requireNonNull(validationErrors, "validationErrors");
        logger.info("Coding-agent skill refinement for '{}' v{}", failed.getName(), failed.getVersion());

        String prompt = buildRefinePrompt(failed, validationErrors);
        GeneratedSkill refined = runAgentAndParse(
                "refine-" + shortId(),
                prompt,
                failed.getDescription(),
                true,
                failed);
        if (refined != null) {
            refined.setVersion(SkillVersion.bumpPatch(failed.getVersion()));
        }
        return refined;
    }

    /** Spawn the agent, wait for terminal state, read SKILL.md, parse. */
    private GeneratedSkill runAgentAndParse(String specId,
                                             String prompt,
                                             String fallbackDescription,
                                             boolean isRefine,
                                             GeneratedSkill original) {
        IsolatedWorkspace inner;
        try {
            inner = workspaceFactory.get();
        } catch (RuntimeException e) {
            logger.error("workspace factory threw: {}", e.getMessage(), e);
            return null;
        }

        NonClosingWorkspace handed = new NonClosingWorkspace(inner);
        SubagentSpec spec = SubagentSpec.builder()
                .id(specId)
                .type(isRefine ? "skill-refiner" : "skill-generator")
                .userPrompt(prompt)
                .timeout(timeout)
                .workspace(() -> handed)
                .build();

        try {
            Subagent agent;
            try {
                agent = spawner.spawn(spec);
            } catch (RuntimeException e) {
                logger.error("spawner.spawn threw for spec {}: {}", specId, e.getMessage(), e);
                return null;
            }

            SubagentResult result = agent.awaitResult(timeout);
            if (!result.succeeded()) {
                logger.warn("coding agent did not complete for spec {} (status={}, output={})",
                        specId, result.status(), abbreviate(result.output(), 200));
                return null;
            }

            Path skillFile = inner.root().resolve(SKILL_FILE_NAME);
            if (!Files.exists(skillFile)) {
                logger.warn("coding agent succeeded but did not write {} (workspace root={})",
                        SKILL_FILE_NAME, inner.root());
                return null;
            }

            String content;
            try {
                content = Files.readString(skillFile);
            } catch (java.io.IOException e) {
                logger.error("failed to read {} from workspace: {}", SKILL_FILE_NAME, e.getMessage());
                return null;
            }
            if (content == null || content.isBlank()) {
                logger.warn("coding agent wrote an empty {}", SKILL_FILE_NAME);
                return null;
            }

            GeneratedSkill skill = parser.parseSkillDefinition(content, fallbackDescription);
            if (skill == null) {
                logger.warn("could not parse SKILL.md produced by coding agent (spec {})", specId);
            }
            return skill;
        } finally {
            try {
                inner.close();
            } catch (RuntimeException e) {
                logger.debug("inner workspace close threw: {}", e.getMessage());
            }
        }
    }

    private String buildGeneratePrompt(String gapDescription, List<String> existingToolNames) {
        StringBuilder p = new StringBuilder();
        p.append("You are designing a new SwarmAI skill to fill a capability gap.\n\n");
        p.append("GAP: ").append(gapDescription).append("\n\n");

        if (!SkillSource.GROOVY.equals(targetLanguage)) {
            p.append("TARGET LANGUAGE: ").append(targetLanguage).append("\n");
            p.append("The CODE block must be valid ").append(targetLanguage).append(" source.\n\n");
        }

        if (existingToolNames != null && !existingToolNames.isEmpty()) {
            p.append("EXISTING TOOLS — call via tools.<name>.execute(Map.of(...)) inside CODE/HYBRID skills:\n");
            for (String name : existingToolNames) {
                p.append("  - ").append(name).append('\n');
            }
            p.append('\n');
        }

        p.append("OUTPUT INSTRUCTIONS\n");
        p.append("Write the complete skill definition to a file named ")
         .append(SKILL_FILE_NAME).append(" in your working directory. ");
        p.append("Do not write to stdout, do not produce any other files. ");
        p.append("After writing the file, exit with status 0.\n\n");

        p.append("SKILL.md FORMAT (frontmatter + body):\n");
        p.append("```\n");
        p.append("---\n");
        p.append("name: snake_case_name\n");
        p.append("description: 50+ char description of what this skill does\n");
        p.append("type: CODE | HYBRID | COMPOSITE\n");
        p.append("triggerWhen: when the LLM should select this skill\n");
        p.append("avoidWhen: when the LLM should NOT select this skill\n");
        p.append("category: data-io | web | computation | analysis | communication | generated\n");
        p.append("tags: [tag1, tag2]\n");
        p.append("---\n\n");
        p.append("# Skill Name\n\n");
        p.append("[For HYBRID: detailed LLM instructions]\n");
        p.append("[For COMPOSITE: routing table + sub-skill descriptions]\n\n");
        p.append("CODE RULES — apply when writing the script (do NOT echo back as comments):\n");
        p.append("- Flat script, no class definitions; last expression is the returned String.\n");
        p.append("- Use only the bindings: params, tools, references, resources, log.\n");
        p.append("- The validator rejects scripts that contain the literal tokens: file I/O, ");
        p.append("Runtime, ProcessBuilder, or Socket — keep these tokens out of code AND comments.\n\n");
        p.append("## Code\n");
        p.append("```").append(fenceFor(targetLanguage)).append("\n");
        p.append("// last expression is returned as a String\n");
        p.append("```\n\n");
        p.append("## Test Cases\n");
        p.append("```").append(fenceFor(targetLanguage)).append("\n");
        p.append("// at least 2 test cases with real assertions\n");
        p.append("```\n\n");
        p.append("## Integration Tests\n\n");
        p.append("### test_basic_execution\n");
        p.append("Verify the skill produces meaningful output\n\n");
        p.append("**Input:**\n```yaml\nkey: value\n```\n\n");
        p.append("**Assertions:**\n```").append(fenceFor(targetLanguage)).append("\n");
        p.append("assert output != null\n");
        p.append("assert output.length() > 20\n");
        p.append("```\n\n");
        p.append("**Expected tool calls:** [tool1]\n");
        p.append("```\n");

        return p.toString();
    }

    private String buildRefinePrompt(GeneratedSkill failed, String validationErrors) {
        StringBuilder p = new StringBuilder();
        p.append("Repair the following SwarmAI skill, which failed validation.\n\n");
        p.append("CURRENT SKILL.md:\n```\n").append(failed.toSkillMd()).append("\n```\n\n");
        p.append("VALIDATION ERRORS:\n").append(validationErrors).append("\n\n");
        p.append("FIX RULES:\n");
        p.append("- Preserve the skill's name and type unless the errors require changing them.\n");
        p.append("- For CODE/HYBRID: use ONLY params, tools, references, resources, log bindings.\n");
        p.append("- All tool.execute() calls return a String. No file I/O, Runtime, or ProcessBuilder.\n");
        p.append("- Code is a flat script — no class definitions.\n");
        p.append("- Fix the integration tests too if they are wrong.\n\n");
        p.append("OUTPUT INSTRUCTIONS\n");
        p.append("Write the complete fixed SKILL.md to a file named ").append(SKILL_FILE_NAME)
         .append(" in your working directory. Do not write to stdout. ");
        p.append("After writing the file, exit with status 0.\n");
        return p.toString();
    }

    private static String fenceFor(String language) {
        return SkillSource.KOTLIN_SCRIPT.equals(language) ? "kotlin" : "groovy";
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /**
     * Wraps an {@link IsolatedWorkspace} with a no-op {@code close()} so the
     * spawner's race-fix close (which fires before the result future
     * completes) doesn't reap files we still need to read.
     */
    private static final class NonClosingWorkspace implements IsolatedWorkspace {
        private final IsolatedWorkspace inner;
        NonClosingWorkspace(IsolatedWorkspace inner) { this.inner = inner; }
        @Override public Path root() { return inner.root(); }
        @Override public boolean isOpen() { return inner.isOpen(); }
        @Override public void close() { /* deferred — owner closes the inner workspace */ }
    }
}
