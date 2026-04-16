package ai.intelliswarm.swarmai.skill.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class RuntimeRegistry {

    private final Map<String, SkillRuntime> byLanguage = new HashMap<>();

    public void register(SkillRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        for (String language : runtime.supportedLanguages()) {
            SkillRuntime existing = byLanguage.putIfAbsent(language, runtime);
            if (existing != null && existing != runtime) {
                throw new IllegalStateException(
                    "Language '" + language + "' already registered by runtime '" + existing.id() + "'");
            }
        }
    }

    public Optional<SkillRuntime> pick(String language) {
        return Optional.ofNullable(byLanguage.get(language));
    }

    public SkillRuntime require(String language) {
        return pick(language).orElseThrow(() -> new IllegalStateException(
            "No SkillRuntime registered for language '" + language + "'"));
    }

    public boolean supports(String language) {
        return byLanguage.containsKey(language);
    }
}
