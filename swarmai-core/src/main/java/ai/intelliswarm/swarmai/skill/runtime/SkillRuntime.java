package ai.intelliswarm.swarmai.skill.runtime;

import java.util.Set;

public interface SkillRuntime {

    String id();

    Set<String> supportedLanguages();

    SecurityReport securityScan(SkillSource source);

    SyntaxReport syntaxCheck(SkillSource source);
}
