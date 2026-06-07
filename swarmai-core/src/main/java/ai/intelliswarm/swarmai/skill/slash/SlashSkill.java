package ai.intelliswarm.swarmai.skill.slash;

import java.util.Objects;
import java.util.function.Function;

/**
 * A user-invokable, named action that runs in response to a slash command
 * (e.g. {@code /help}, {@code /summarise some text}). Slash skills are how the
 * harness exposes pre-packaged behaviours to the user mid-conversation, the
 * common UX pattern for slash commands like {@code /help}, {@code /review}, etc.
 *
 * <p>Distinct from the framework's pre-existing {@code skill} package
 * (curator/generator/validator system for self-improvement). This package
 * scopes user-facing slash commands.
 *
 * @param name        slash-command name, validated (lowercase letters, digits,
 *                    underscore, hyphen). Never blank or null.
 * @param description short one-line description shown by {@code /help} listings
 * @param action      function from raw args (everything after the command name,
 *                    or empty string if none) to the skill's output
 */
public record SlashSkill(
        String name,
        String description,
        Function<String, String> action) {

    public SlashSkill {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(action, "action");
        if (!isValidName(name)) {
            throw new IllegalArgumentException(
                    "skill name must match [a-z0-9_-]+, got: '" + name + "'");
        }
        if (description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
    }

    /** Run the skill against the given raw argument string (post-name). */
    public String execute(String args) {
        return action.apply(args == null ? "" : args);
    }

    /** True if {@code name} is non-empty and matches {@code [a-z0-9_-]+}. */
    public static boolean isValidName(String name) {
        if (name == null || name.isEmpty()) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '_' || c == '-';
            if (!ok) return false;
        }
        return true;
    }
}
