package ai.intelliswarm.swarmai.skill;

import java.util.List;

/**
 * Strategy for producing and repairing {@link GeneratedSkill}s.
 *
 * <p>Two implementations live in this package:
 * <ul>
 *   <li>{@link SkillGenerator} — drives a {@code ChatClient} directly. Best
 *       when you want a single round-trip: prompt in, SKILL.md text out.
 *       Cheap, deterministic, no filesystem.</li>
 *   <li>{@code CodingAgentSkillGenerator} — delegates to an external coding
 *       agent (Codex, Claude Code) running in an
 *       {@link ai.intelliswarm.swarmai.agent.subagent.IsolatedWorkspace}. The
 *       agent can iterate, run tests against a real validator, and produce
 *       multi-file outputs. More powerful, more expensive.</li>
 * </ul>
 *
 * <p>Both implementations return {@code null} on irrecoverable failure rather
 * than throwing, so callers can treat the engine as "produce a skill or give
 * up gracefully" — the bandit / curator decides what to do next.
 */
public interface SkillEngine {

    /**
     * Produce a skill that fills the described capability gap.
     *
     * @param gapDescription      one-paragraph description of what's missing
     * @param existingToolNames   names of tools already in the registry that
     *                            the new skill may compose
     * @return the generated skill, or {@code null} if the engine could not
     *         produce a parsable skill (timeout, malformed output, etc.)
     */
    GeneratedSkill generate(String gapDescription, List<String> existingToolNames);

    /**
     * Repair a skill that failed validation, using the validator's error
     * messages as guidance. Implementations should bump the patch version of
     * the returned skill (so the registry can distinguish refinement attempts
     * from the original).
     *
     * @param failed             the skill that failed validation
     * @param validationErrors   error messages from {@code SkillValidator}
     * @return the refined skill (with bumped patch version), or {@code null}
     *         if the engine could not repair it
     */
    GeneratedSkill refine(GeneratedSkill failed, String validationErrors);
}
