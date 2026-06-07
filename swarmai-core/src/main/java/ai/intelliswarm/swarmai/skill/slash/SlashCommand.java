package ai.intelliswarm.swarmai.skill.slash;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsed slash-command, of the form {@code /name [args...]}. {@link #args} is
 * the everything-after-the-name substring; an empty {@code args} means the user
 * typed only {@code /name} with no trailing input.
 *
 * <p>Use {@link #parse(String)} to detect and extract a slash command from raw
 * user input. Inputs that don't begin with {@code /} return {@link Optional#empty()}
 * — they are passthrough to the LLM.
 */
public record SlashCommand(String name, String args, String raw) {

    /**
     * Pattern: leading slash, name token (lowercase letters / digits /
     * underscore / hyphen), optional whitespace + args. {@code Pattern.DOTALL}
     * so multi-line args survive (e.g. {@code /summarise <pasted multi-line text>}).
     */
    private static final Pattern SLASH_PATTERN = Pattern.compile(
            "^/([a-z0-9_-]+)(?:[ \\t]+(.*))?$", Pattern.DOTALL);

    public SlashCommand {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(raw, "raw");
        if (!SlashSkill.isValidName(name)) {
            throw new IllegalArgumentException(
                    "name must match [a-z0-9_-]+, got: '" + name + "'");
        }
    }

    /**
     * Try to parse {@code input} as a slash command. Returns {@link Optional#empty()}
     * if the input doesn't begin with {@code /} (after trimming) or doesn't
     * match the slash-command grammar.
     */
    public static Optional<SlashCommand> parse(String input) {
        if (input == null) return Optional.empty();
        String stripped = input.stripLeading();
        if (stripped.isEmpty() || stripped.charAt(0) != '/') {
            return Optional.empty();
        }
        Matcher m = SLASH_PATTERN.matcher(stripped);
        if (!m.matches()) {
            return Optional.empty();
        }
        String name = m.group(1);
        String args = m.group(2);
        return Optional.of(new SlashCommand(
                name,
                args == null ? "" : args,
                input));
    }

    /** True if the args portion is empty (user typed only the bare command). */
    public boolean hasArgs() {
        return !args.isEmpty();
    }
}
