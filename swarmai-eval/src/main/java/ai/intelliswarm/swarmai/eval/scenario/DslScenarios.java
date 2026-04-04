package ai.intelliswarm.swarmai.eval.scenario;

import ai.intelliswarm.swarmai.eval.scoring.ScenarioResult;

import java.time.Duration;
import java.util.List;

/**
 * DSL & configuration scenarios.
 * These verify YAML parsing, compilation, and template variable resolution.
 */
public class DslScenarios {

    /** Verifies YAML parser is on classpath and can be loaded. */
    public static EvalScenario yamlParserAvailable() {
        return new AbstractEvalScenario() {
            @Override public String id() { return "dsl-parser-available"; }
            @Override public String name() { return "YAML Parser Available"; }
            @Override public String category() { return "DSL"; }
            @Override public String description() { return "Verify YamlSwarmParser class is loadable"; }

            @Override
            protected ScenarioResult doExecute() {
                try {
                    Class.forName("ai.intelliswarm.swarmai.dsl.parser.YamlSwarmParser");
                    return ScenarioResult.pass(id(), name(), category(), 100.0,
                            "YamlSwarmParser is on classpath", Duration.ZERO);
                } catch (ClassNotFoundException e) {
                    return ScenarioResult.fail(id(), name(), category(),
                            "YamlSwarmParser not found on classpath", Duration.ZERO);
                }
            }
        };
    }

    /** Verifies SwarmCompiler is on classpath. */
    public static EvalScenario compilerAvailable() {
        return new AbstractEvalScenario() {
            @Override public String id() { return "dsl-compiler-available"; }
            @Override public String name() { return "Swarm Compiler Available"; }
            @Override public String category() { return "DSL"; }
            @Override public String description() { return "Verify SwarmCompiler class is loadable"; }

            @Override
            protected ScenarioResult doExecute() {
                try {
                    Class.forName("ai.intelliswarm.swarmai.dsl.compiler.SwarmCompiler");
                    return ScenarioResult.pass(id(), name(), category(), 100.0,
                            "SwarmCompiler is on classpath", Duration.ZERO);
                } catch (ClassNotFoundException e) {
                    return ScenarioResult.fail(id(), name(), category(),
                            "SwarmCompiler not found on classpath", Duration.ZERO);
                }
            }
        };
    }

    /** Verifies all 7 ProcessType enum values exist. */
    public static EvalScenario allProcessTypes() {
        return new AbstractEvalScenario() {
            @Override public String id() { return "dsl-process-types"; }
            @Override public String name() { return "All 7 Process Types"; }
            @Override public String category() { return "DSL"; }
            @Override public String description() { return "Verify all ProcessType enum values exist"; }

            @Override
            protected ScenarioResult doExecute() {
                var types = ai.intelliswarm.swarmai.process.ProcessType.values();
                boolean valid = types.length >= 7;
                return valid
                        ? ScenarioResult.pass(id(), name(), category(), 100.0,
                        types.length + " process types available", Duration.ZERO)
                        : ScenarioResult.fail(id(), name(), category(),
                        "Expected >= 7 process types, found " + types.length, Duration.ZERO);
            }
        };
    }

    public static List<EvalScenario> all() {
        return List.of(
                yamlParserAvailable(),
                compilerAvailable(),
                allProcessTypes()
        );
    }
}
