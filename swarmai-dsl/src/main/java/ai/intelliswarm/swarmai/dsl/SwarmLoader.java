package ai.intelliswarm.swarmai.dsl;

import ai.intelliswarm.swarmai.dsl.compiler.SwarmCompiler;
import ai.intelliswarm.swarmai.dsl.model.SwarmDefinition;
import ai.intelliswarm.swarmai.dsl.parser.YamlSwarmParser;
import ai.intelliswarm.swarmai.swarm.Swarm;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * One-liner entry point for loading YAML workflows.
 *
 * <pre>{@code
 * @Autowired SwarmLoader swarmLoader;
 *
 * Swarm swarm = swarmLoader.load("workflows/research.yaml");
 * SwarmOutput output = swarm.kickoff(Map.of("topic", "AI Safety"));
 * }</pre>
 */
public class SwarmLoader {

    private final YamlSwarmParser parser;
    private final SwarmCompiler compiler;

    public SwarmLoader(YamlSwarmParser parser, SwarmCompiler compiler) {
        this.parser = parser;
        this.compiler = compiler;
    }

    /**
     * Loads a YAML workflow from the classpath and compiles it into a Swarm.
     */
    public Swarm load(String classpathResource) throws IOException {
        SwarmDefinition definition = parser.parseResource(classpathResource);
        return compiler.compile(definition);
    }

    /**
     * Loads a YAML workflow from the classpath with template variables.
     */
    public Swarm load(String classpathResource, Map<String, Object> variables) throws IOException {
        SwarmDefinition definition = parser.parseResource(classpathResource, variables);
        return compiler.compile(definition);
    }

    /**
     * Loads a YAML workflow from a file path.
     */
    public Swarm loadFile(Path yamlFile) throws IOException {
        SwarmDefinition definition = parser.parse(yamlFile);
        return compiler.compile(definition);
    }

    /**
     * Loads a YAML workflow from a file path with template variables.
     */
    public Swarm loadFile(Path yamlFile, Map<String, Object> variables) throws IOException {
        SwarmDefinition definition = parser.parse(yamlFile, variables);
        return compiler.compile(definition);
    }

    /**
     * Parses and compiles an inline YAML string.
     */
    public Swarm fromYaml(String yaml) throws IOException {
        SwarmDefinition definition = parser.parseString(yaml);
        return compiler.compile(definition);
    }

    /**
     * Parses and compiles an inline YAML string with template variables.
     */
    public Swarm fromYaml(String yaml, Map<String, Object> variables) throws IOException {
        SwarmDefinition definition = parser.parseString(yaml, variables);
        return compiler.compile(definition);
    }

    public YamlSwarmParser getParser() { return parser; }
    public SwarmCompiler getCompiler() { return compiler; }
}
