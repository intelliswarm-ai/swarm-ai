package ai.intelliswarm.swarmai.dsl.parser;

import ai.intelliswarm.swarmai.dsl.model.SwarmDefinition;
import ai.intelliswarm.swarmai.dsl.model.SwarmYamlRoot;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses YAML workflow definitions into {@link SwarmDefinition} instances.
 * Supports {@code {{variable}}} template substitution before parsing.
 *
 * <pre>{@code
 * YamlSwarmParser parser = new YamlSwarmParser();
 *
 * // From file
 * SwarmDefinition def = parser.parse(Path.of("workflow.yaml"));
 *
 * // With template variables
 * SwarmDefinition def = parser.parse(Path.of("workflow.yaml"),
 *     Map.of("topic", "AI Safety", "outputDir", "/tmp/results"));
 *
 * // From classpath resource
 * SwarmDefinition def = parser.parseResource("workflows/research.yaml");
 * }</pre>
 */
public class YamlSwarmParser {

    private static final Logger logger = LoggerFactory.getLogger(YamlSwarmParser.class);
    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\{\\{\\s*(\\w+)\\s*}}");

    private final ObjectMapper yamlMapper;

    public YamlSwarmParser() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.yamlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Parses a YAML file into a SwarmDefinition.
     */
    public SwarmDefinition parse(Path yamlFile) throws IOException {
        return parse(yamlFile, Map.of());
    }

    /**
     * Parses a YAML file with template variable substitution.
     * Variables in the form {@code {{name}}} are replaced with values from the map.
     */
    public SwarmDefinition parse(Path yamlFile, Map<String, Object> variables) throws IOException {
        String content = Files.readString(yamlFile);
        return parseString(content, variables);
    }

    /**
     * Parses a classpath resource into a SwarmDefinition.
     */
    public SwarmDefinition parseResource(String resourcePath) throws IOException {
        return parseResource(resourcePath, Map.of());
    }

    /**
     * Parses a classpath resource with template variable substitution.
     */
    public SwarmDefinition parseResource(String resourcePath, Map<String, Object> variables) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            String content = new String(is.readAllBytes());
            return parseString(content, variables);
        }
    }

    /**
     * Parses a YAML string into a SwarmDefinition.
     */
    public SwarmDefinition parseString(String yaml) throws IOException {
        return parseString(yaml, Map.of());
    }

    /**
     * Parses a YAML string with template variable substitution.
     */
    public SwarmDefinition parseString(String yaml, Map<String, Object> variables) throws IOException {
        String resolved = substituteVariables(yaml, variables);
        SwarmYamlRoot root = yamlMapper.readValue(resolved, SwarmYamlRoot.class);

        if (root.getSwarm() == null) {
            throw new SwarmParseException("YAML must have a top-level 'swarm:' key");
        }

        SwarmDefinition definition = root.getSwarm();
        validate(definition);

        logger.info("Parsed swarm definition: name={}, agents={}, tasks={}, process={}",
                definition.getName(),
                definition.getAgents().size(),
                definition.getTasks().size(),
                definition.getProcess());

        return definition;
    }

    private String substituteVariables(String yaml, Map<String, Object> variables) {
        if (variables.isEmpty()) {
            return yaml;
        }

        Matcher matcher = TEMPLATE_PATTERN.matcher(yaml);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String varName = matcher.group(1);
            Object value = variables.get(varName);
            String replacement = value != null ? Matcher.quoteReplacement(String.valueOf(value)) : matcher.group(0);
            matcher.appendReplacement(result, replacement);
        }
        matcher.appendTail(result);

        return result.toString();
    }

    private void validate(SwarmDefinition definition) {
        if (definition.getAgents().isEmpty()) {
            throw new SwarmParseException("At least one agent must be defined");
        }
        if (definition.getTasks().isEmpty()) {
            throw new SwarmParseException("At least one task must be defined");
        }

        // Validate agent references in tasks
        definition.getTasks().forEach((taskId, taskDef) -> {
            if (taskDef.getAgent() != null && !definition.getAgents().containsKey(taskDef.getAgent())) {
                throw new SwarmParseException(
                        "Task '" + taskId + "' references unknown agent '" + taskDef.getAgent() + "'");
            }

            // Validate dependency references
            for (String dep : taskDef.getDependsOn()) {
                if (!definition.getTasks().containsKey(dep)) {
                    throw new SwarmParseException(
                            "Task '" + taskId + "' depends on unknown task '" + dep + "'");
                }
            }
        });

        // Validate managerAgent reference
        if (definition.getManagerAgent() != null && !definition.getAgents().containsKey(definition.getManagerAgent())) {
            throw new SwarmParseException(
                    "managerAgent '" + definition.getManagerAgent() + "' is not defined in agents");
        }

        // Validate process type
        try {
            ai.intelliswarm.swarmai.process.ProcessType.valueOf(definition.getProcess());
        } catch (IllegalArgumentException e) {
            throw new SwarmParseException("Unknown process type: '" + definition.getProcess() + "'");
        }
    }
}
