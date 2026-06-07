package ai.intelliswarm.swarmai.skill;

/**
 * Execution mode for a generated skill supporting multi-modal skill architecture.
 *
 * Skills range from pure prompt-based (SKILL.md is just LLM instructions) to
 * code-backed (scripts/) to hybrid (prompt + scripts). This enum captures that spectrum.
 */
public enum SkillType {
    /**
     * Pure prompt skill — the skill body is an LLM instruction/prompt that gets injected
     * into the agent's system prompt. No code execution; the LLM follows the instructions
     * to produce the output. Like a SKILL.md with no scripts/ directory.
     *
     * Best for: domain expertise, analysis frameworks, output formatting, reasoning patterns.
     */
    PROMPT,

    /**
     * Code skill — the skill is a Groovy script that executes in a sandboxed environment.
     * Can call existing tools via the 'tools' binding. This is the original GeneratedSkill behavior.
     *
     * Best for: data transformation, tool composition, computation pipelines.
     */
    CODE,

    /**
     * Hybrid skill — combines a prompt (instructions for the LLM) with code (execution logic).
     * The prompt guides the LLM on how to use the skill and interpret results, while
     * the code handles the actual data processing.
     *
     * Best for: complex workflows that need both LLM reasoning and data processing.
     */
    HYBRID,

    /**
     * Composite skill — a router that dispatches to sub-skills based on intent.
     * A parent skill with nested sub-skill directories.
     * The body contains routing rules (intent → sub-skill mapping).
     *
     * Best for: multi-capability domains (e.g., "finance" routes to analysis, reporting, alerts).
     */
    COMPOSITE,

    /**
     * Container skill — the skill body is a Docker image (pre-built or built
     * from an inline Dockerfile by the framework). The framework starts a
     * one-shot container, passes skill parameters as environment variables,
     * captures stdout, and returns it as the skill's result.
     *
     * <p>Lifts skill execution past the Groovy sandbox. The container is
     * whatever the agent decides to make it: a Kali image with the full
     * pentest toolkit, a Python image with pandas and scikit-learn, a
     * dedicated build of nmap / hydra / sqlmap. Anything Linux-runnable
     * is now a candidate skill body.
     *
     * <p>Configuration lives in {@link ContainerSkillSpec}. Image and
     * container lifecycle (build, pull, start, stop, prune) is managed by
     * a {@code ContainerSkillRuntime} so skills don't leak resources.
     *
     * <p>Best for: capability gaps that require real binaries, native deps,
     * elevated I/O, or domain-specific toolchains the in-process runtime
     * cannot reach. Pentest is the prototype use case.
     */
    CONTAINER
}
