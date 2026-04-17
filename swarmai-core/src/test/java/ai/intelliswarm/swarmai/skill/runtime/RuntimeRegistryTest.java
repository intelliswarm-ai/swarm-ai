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
    @DisplayName("register() rejects conflicting runtime without partial language registration")
    void register_conflictingLanguage_rollsBackAllLanguages() {
        RuntimeRegistry registry = new RuntimeRegistry();

        SkillRuntime existing = new StubRuntime("existing", Set.of("kotlin-script"));
        SkillRuntime candidate = new StubRuntime("candidate", Set.of("groovy", "kotlin-script"));

        registry.register(existing);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> registry.register(candidate));
        assertTrue(ex.getMessage().contains("kotlin-script"));

        assertFalse(registry.supports("groovy"));
        assertEquals(existing, registry.require("kotlin-script"));
    }

    @Test
    @DisplayName("register() installs all non-conflicting languages")
    void register_noConflicts_registersAllLanguages() {
        RuntimeRegistry registry = new RuntimeRegistry();
        SkillRuntime runtime = new StubRuntime("polyglot", Set.of("groovy", "kotlin-script"));

        registry.register(runtime);

        assertEquals(runtime, registry.require("groovy"));
        assertEquals(runtime, registry.require("kotlin-script"));
    }

    private record StubRuntime(String id, Set<String> supportedLanguages) implements SkillRuntime {
        @Override
        public SecurityReport securityScan(SkillSource source) {
            return SecurityReport.passed();
        }

        @Override
        public SyntaxReport syntaxCheck(SkillSource source) {
            return SyntaxReport.passed();
        }
    }
}
