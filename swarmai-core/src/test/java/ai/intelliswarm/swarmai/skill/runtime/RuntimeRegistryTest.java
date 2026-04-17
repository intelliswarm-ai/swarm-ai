package ai.intelliswarm.swarmai.skill.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RuntimeRegistry Tests")
class RuntimeRegistryTest {

    @Test
    @DisplayName("register() fails atomically when one language conflicts")
    void register_conflictingLanguage_keepsRegistryUnchanged() {
        RuntimeRegistry registry = new RuntimeRegistry();
        SkillRuntime python = runtime("python-runtime", Set.of("python"));
        SkillRuntime hybrid = runtime("hybrid-runtime", Set.of("groovy", "python"));

        registry.register(python);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> registry.register(hybrid));
        assertTrue(ex.getMessage().contains("Language 'python' already registered"));
        assertFalse(registry.supports("groovy"));
        assertEquals(python, registry.require("python"));
    }

    @Test
    @DisplayName("register() keeps idempotent registration for same runtime")
    void register_sameRuntimeForLanguages_isIdempotent() {
        RuntimeRegistry registry = new RuntimeRegistry();
        SkillRuntime runtime = runtime("groovy-runtime", Set.of("groovy", "java"));

        registry.register(runtime);
        registry.register(runtime);

        assertEquals(runtime, registry.require("groovy"));
        assertEquals(runtime, registry.require("java"));
    }

    private static SkillRuntime runtime(String id, Set<String> languages) {
        return new SkillRuntime() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Set<String> supportedLanguages() {
                return languages;
            }

            @Override
            public SecurityReport securityScan(SkillSource source) {
                return SecurityReport.passed();
            }

            @Override
            public SyntaxReport syntaxCheck(SkillSource source) {
                return SyntaxReport.passed();
            }
        };
    }
}
