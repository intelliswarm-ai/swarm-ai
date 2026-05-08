package ai.intelliswarm.swarmai.skill.slash;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Lookup table of {@link SlashSkill}s registered with the harness.
 * Insertion-ordered so {@link #all()} returns skills in the order they were
 * registered — useful for {@code /help} listings.
 *
 * <p>Distinct from the framework's pre-existing {@code skill.SkillRegistry}
 * (the self-improvement curator). This registry only holds slash commands.
 */
public final class SlashSkillRegistry {

    private final Map<String, SlashSkill> skills = new LinkedHashMap<>();

    /** Register a skill, replacing any prior skill with the same name. */
    public void register(SlashSkill skill) {
        Objects.requireNonNull(skill, "skill");
        skills.put(skill.name(), skill);
    }

    /** Register a skill, throwing if a skill with the same name is already present. */
    public void registerStrict(SlashSkill skill) {
        Objects.requireNonNull(skill, "skill");
        if (skills.containsKey(skill.name())) {
            throw new IllegalArgumentException(
                    "skill already registered: '" + skill.name() + "'");
        }
        skills.put(skill.name(), skill);
    }

    /** Look up a skill by name. Returns empty if not registered. */
    public Optional<SlashSkill> lookup(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(skills.get(name));
    }

    /** True if a skill with this name is registered. */
    public boolean contains(String name) {
        return name != null && skills.containsKey(name);
    }

    /** Remove a skill by name. Returns the removed skill, if any. */
    public Optional<SlashSkill> unregister(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(skills.remove(name));
    }

    /** All registered skills, in registration order. Snapshot — safe to iterate. */
    public Collection<SlashSkill> all() {
        return Collections.unmodifiableCollection(new LinkedHashMap<>(skills).values());
    }

    /** Number of registered skills. */
    public int size() {
        return skills.size();
    }

    /** True if no skills are registered. */
    public boolean isEmpty() {
        return skills.isEmpty();
    }
}
