package ai.intelliswarm.swarmai.skill.slash;

import java.util.Objects;

/**
 * Built-in skill that lists every other skill registered with a given
 * {@link SlashSkillRegistry}, in registration order. Convention:
 * register it as {@code /help} so users always have an entry point to discover
 * what's available.
 *
 * <p>Output format:
 * <pre>
 * Available commands:
 *   /help            — list available commands
 *   /summarise       — summarise a passage of text
 *   /translate       — translate a passage to another language
 * </pre>
 */
public final class HelpSkill {

    private HelpSkill() {}

    /** Build the standard {@code /help} skill against the given registry. */
    public static SlashSkill of(SlashSkillRegistry registry) {
        return of(registry, "help");
    }

    /** Build a help skill with a custom name (e.g. for nested namespaces). */
    public static SlashSkill of(SlashSkillRegistry registry, String name) {
        Objects.requireNonNull(registry, "registry");
        return new SlashSkill(
                name,
                "list available commands",
                args -> render(registry));
    }

    static String render(SlashSkillRegistry registry) {
        if (registry.isEmpty()) {
            return "No commands are registered.";
        }
        int nameColumn = 0;
        for (SlashSkill s : registry.all()) {
            int width = s.name().length() + 1; // +1 for the leading slash
            if (width > nameColumn) nameColumn = width;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Available commands:\n");
        for (SlashSkill s : registry.all()) {
            sb.append("  /").append(s.name());
            int padding = nameColumn - (s.name().length() + 1);
            for (int i = 0; i < padding + 2; i++) sb.append(' ');
            sb.append("— ").append(s.description()).append('\n');
        }
        if (sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }
}
