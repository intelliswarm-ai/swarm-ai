package ai.intelliswarm.swarmai.skill.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("RuntimeRegistry Tests")
class RuntimeRegistryTest {

    @Test
    @DisplayName("register() is atomic when any language conflicts")
    void register_conflictingLanguage_doesNotPartiallyRegister() {
        RuntimeRegistry registry = new RuntimeRegistry();

        SkillRuntime existingRuntime = new TestRuntime("existing", Set.of(SkillSource.GROOVY));
        registry.register(existingRuntime);

        SkillRuntime conflictingRuntime = new TestRuntime("multi-lang", Set.of(SkillSource.KOTLIN_SCRIPT, SkillSource.GROOVY));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> registry.register(conflictingRuntime));

        assertEquals("Language 'groovy' already registered by runtime 'existing'", ex.getMessage());
        assertFalse(registry.supports(SkillSource.KOTLIN_SCRIPT));
        assertSame(existingRuntime, registry.require(SkillSource.GROOVY));
    }

    private record TestRuntime(String id, Set<String> supportedLanguages) implements SkillRuntime {

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
