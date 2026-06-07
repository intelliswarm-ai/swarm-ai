package ai.intelliswarm.swarmai.skill.slash;

import java.util.Objects;
import java.util.Optional;

/**
 * Front door for user input: parses a slash command and dispatches it to the
 * matching {@link SlashSkill}, or returns a {@link RouterResult.PassThrough}
 * when the input is plain text.
 *
 * <p>Typical use, before sending user input to the LLM:
 * <pre>{@code
 * RouterResult r = router.route(rawUserInput);
 * String output = switch (r) {
 *     case RouterResult.SkillExecuted e -> e.output();
 *     case RouterResult.SkillNotFound n -> "Unknown command: /" + n.name();
 *     case RouterResult.PassThrough p   -> chatClient.prompt().user(p.input()).call().content();
 * };
 * }</pre>
 *
 * <p>Errors thrown by skill actions are <em>not</em> swallowed — they propagate
 * to the caller. The harness decides whether to surface a stack trace, retry,
 * or display a friendly message.
 */
public final class SlashCommandRouter {

    private final SlashSkillRegistry registry;

    public SlashCommandRouter(SlashSkillRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** Parse {@code input}, dispatch if it's a slash command, else pass through. */
    public RouterResult route(String input) {
        Optional<SlashCommand> maybe = SlashCommand.parse(input);
        if (maybe.isEmpty()) {
            return new RouterResult.PassThrough(input == null ? "" : input);
        }
        SlashCommand cmd = maybe.get();
        Optional<SlashSkill> skill = registry.lookup(cmd.name());
        if (skill.isEmpty()) {
            return new RouterResult.SkillNotFound(cmd.name());
        }
        String output = skill.get().execute(cmd.args());
        return new RouterResult.SkillExecuted(cmd.name(), output);
    }

    /** The registry this router is backed by. */
    public SlashSkillRegistry registry() {
        return registry;
    }
}
