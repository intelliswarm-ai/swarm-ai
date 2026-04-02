package ai.intelliswarm.swarmai.dsl.parser;

import ai.intelliswarm.swarmai.dsl.model.SwarmDefinition;
import ai.intelliswarm.swarmai.dsl.model.SwarmYamlRoot;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ai.intelliswarm.swarmai.dsl.model.EdgeDefinition;
import ai.intelliswarm.swarmai.dsl.model.ConditionalEdgeDefinition;
import ai.intelliswarm.swarmai.dsl.model.GraphDefinition;
import ai.intelliswarm.swarmai.dsl.model.ToolHookDefinition;
import ai.intelliswarm.swarmai.dsl.compiler.ConditionEvaluator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        boolean isGraph = definition.getGraph() != null;

        if (definition.getAgents().isEmpty()) {
            throw new SwarmParseException("At least one agent must be defined");
        }
        if (!isGraph && definition.getTasks().isEmpty()) {
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
        ai.intelliswarm.swarmai.process.ProcessType processType;
        try {
            processType = ai.intelliswarm.swarmai.process.ProcessType.valueOf(definition.getProcess());
        } catch (IllegalArgumentException e) {
            throw new SwarmParseException("Unknown process type: '" + definition.getProcess() + "'");
        }

        // COMPOSITE requires stages
        if (processType == ai.intelliswarm.swarmai.process.ProcessType.COMPOSITE) {
            if (definition.getStages() == null || definition.getStages().isEmpty()) {
                throw new SwarmParseException("COMPOSITE process requires at least one stage in 'stages:'");
            }
            for (int i = 0; i < definition.getStages().size(); i++) {
                var stage = definition.getStages().get(i);
                if (stage.getProcess() == null) {
                    throw new SwarmParseException("Stage " + i + " must specify a 'process' type");
                }
                try {
                    var stageType = ai.intelliswarm.swarmai.process.ProcessType.valueOf(stage.getProcess());
                    if (stageType == ai.intelliswarm.swarmai.process.ProcessType.COMPOSITE) {
                        throw new SwarmParseException("Stage " + i + " cannot be COMPOSITE (no nested composites)");
                    }
                } catch (IllegalArgumentException e) {
                    throw new SwarmParseException("Stage " + i + " has unknown process type: '" + stage.getProcess() + "'");
                }
            }
        }

        // Validate tool hooks on agents
        Set<String> validHookTypes = Set.of("audit", "sanitize", "rate-limit", "deny", "custom");
        definition.getAgents().forEach((agentId, agentDef) -> {
            List<ToolHookDefinition> hooks = agentDef.getToolHooks();
            if (hooks == null) return;
            for (int i = 0; i < hooks.size(); i++) {
                ToolHookDefinition hook = hooks.get(i);
                if (hook.getType() == null || !validHookTypes.contains(hook.getType())) {
                    throw new SwarmParseException(
                            "Agent '" + agentId + "' toolHook[" + i + "] has invalid type: '" +
                            hook.getType() + "'. Valid types: " + validHookTypes);
                }
                switch (hook.getType()) {
                    case "sanitize" -> {
                        if (hook.getPatterns() == null || hook.getPatterns().isEmpty()) {
                            throw new SwarmParseException(
                                    "Agent '" + agentId + "' toolHook[" + i + "] (sanitize) requires 'patterns'");
                        }
                    }
                    case "rate-limit" -> {
                        if (hook.getMaxCalls() == null || hook.getMaxCalls() <= 0) {
                            throw new SwarmParseException(
                                    "Agent '" + agentId + "' toolHook[" + i + "] (rate-limit) requires 'maxCalls' > 0");
                        }
                        if (hook.getWindowSeconds() == null || hook.getWindowSeconds() <= 0) {
                            throw new SwarmParseException(
                                    "Agent '" + agentId + "' toolHook[" + i + "] (rate-limit) requires 'windowSeconds' > 0");
                        }
                    }
                    case "deny" -> {
                        if (hook.getTools() == null || hook.getTools().isEmpty()) {
                            throw new SwarmParseException(
                                    "Agent '" + agentId + "' toolHook[" + i + "] (deny) requires 'tools'");
                        }
                    }
                    case "custom" -> {
                        if (hook.getHookClass() == null || hook.getHookClass().isBlank()) {
                            throw new SwarmParseException(
                                    "Agent '" + agentId + "' toolHook[" + i + "] (custom) requires 'class'");
                        }
                    }
                    default -> { /* audit has no required fields */ }
                }
            }
        });

        // Validate workflow hooks
        if (definition.getHooks() != null) {
            Set<String> validHookPoints = Set.of(
                    "BEFORE_WORKFLOW", "AFTER_WORKFLOW", "BEFORE_TASK", "AFTER_TASK",
                    "BEFORE_TOOL", "AFTER_TOOL", "ON_ERROR", "ON_CHECKPOINT");
            Set<String> validWorkflowHookTypes = Set.of("log", "checkpoint", "custom");

            for (int i = 0; i < definition.getHooks().size(); i++) {
                var hook = definition.getHooks().get(i);
                if (hook.getPoint() == null || !validHookPoints.contains(hook.getPoint())) {
                    throw new SwarmParseException("hooks[" + i + "] has invalid point: '" +
                            hook.getPoint() + "'. Valid: " + validHookPoints);
                }
                if (hook.getType() == null || !validWorkflowHookTypes.contains(hook.getType())) {
                    throw new SwarmParseException("hooks[" + i + "] has invalid type: '" +
                            hook.getType() + "'. Valid: " + validWorkflowHookTypes);
                }
                if ("custom".equals(hook.getType()) && (hook.getHookClass() == null || hook.getHookClass().isBlank())) {
                    throw new SwarmParseException("hooks[" + i + "] (custom) requires 'class'");
                }
            }
        }

        // Validate task conditions
        definition.getTasks().forEach((taskId, taskDef) -> {
            if (taskDef.getCondition() != null && !taskDef.getCondition().isBlank()) {
                try {
                    // Validate that the condition expression is parseable
                    if (taskDef.getCondition().startsWith("contains(")) {
                        ConditionEvaluator.toPredicate(taskDef.getCondition());
                    } else {
                        ConditionEvaluator.validate(taskDef.getCondition());
                    }
                } catch (Exception e) {
                    throw new SwarmParseException("Task '" + taskId + "' has invalid condition '" +
                            taskDef.getCondition() + "': " + e.getMessage());
                }
            }
        });

        // Validate graph definition
        if (isGraph) {
            GraphDefinition graph = definition.getGraph();
            if (graph.getNodes().isEmpty()) {
                throw new SwarmParseException("Graph must define at least one node");
            }

            // Validate node agent references
            graph.getNodes().forEach((nodeId, nodeDef) -> {
                if (nodeDef.getAgent() == null) {
                    throw new SwarmParseException("Graph node '" + nodeId + "' must specify an agent");
                }
                if (!definition.getAgents().containsKey(nodeDef.getAgent())) {
                    throw new SwarmParseException("Graph node '" + nodeId +
                            "' references unknown agent '" + nodeDef.getAgent() + "'");
                }
                if (nodeDef.getTask() == null || nodeDef.getTask().isBlank()) {
                    throw new SwarmParseException("Graph node '" + nodeId + "' must specify a task description");
                }
            });

            // Validate edges
            Set<String> validTargets = new java.util.HashSet<>(graph.getNodes().keySet());
            validTargets.add("START");
            validTargets.add("END");

            boolean hasStartEdge = false;
            for (EdgeDefinition edge : graph.getEdges()) {
                if (edge.getFrom() == null) {
                    throw new SwarmParseException("Edge must specify 'from'");
                }
                if (!validTargets.contains(edge.getFrom())) {
                    throw new SwarmParseException("Edge 'from' references unknown node: '" + edge.getFrom() + "'");
                }
                if ("START".equals(edge.getFrom())) hasStartEdge = true;

                if (edge.isConditional()) {
                    for (ConditionalEdgeDefinition cond : edge.getConditional()) {
                        String target = cond.target();
                        if (target != null && !validTargets.contains(target)) {
                            throw new SwarmParseException("Conditional edge target '" + target +
                                    "' is not a known node");
                        }
                        // Validate condition expression syntax
                        if (cond.getWhen() != null) {
                            try {
                                ConditionEvaluator.validate(cond.getWhen());
                            } catch (ConditionEvaluator.ConditionParseException e) {
                                throw new SwarmParseException("Invalid condition '" + cond.getWhen() +
                                        "' in edge from '" + edge.getFrom() + "': " + e.getMessage());
                            }
                        }
                    }
                } else {
                    if (edge.getTo() == null) {
                        throw new SwarmParseException("Static edge from '" + edge.getFrom() + "' must specify 'to'");
                    }
                    if (!validTargets.contains(edge.getTo())) {
                        throw new SwarmParseException("Edge 'to' references unknown node: '" + edge.getTo() + "'");
                    }
                }
            }

            if (!hasStartEdge) {
                throw new SwarmParseException("Graph must have at least one edge from START");
            }

            // Validate state channel types
            if (definition.getState() != null && definition.getState().getChannels() != null) {
                Set<String> validChannelTypes = Set.of("lastWriteWins", "appender", "counter", "stringAppender");
                definition.getState().getChannels().forEach((name, channelDef) -> {
                    if (!validChannelTypes.contains(channelDef.getType())) {
                        throw new SwarmParseException("Unknown channel type '" + channelDef.getType() +
                                "' for channel '" + name + "'. Valid types: " + validChannelTypes);
                    }
                });
            }
        }
    }
}
