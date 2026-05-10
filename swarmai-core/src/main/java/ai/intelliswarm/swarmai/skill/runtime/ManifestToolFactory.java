package ai.intelliswarm.swarmai.skill.runtime;

import ai.intelliswarm.swarmai.skill.ContainerSkillSpec;
import ai.intelliswarm.swarmai.skill.runtime.CapabilityManifest.Capability;
import ai.intelliswarm.swarmai.skill.runtime.CapabilityManifest.Parameter;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Materialises a {@link CapabilityManifest} into a list of
 * {@link BaseTool} instances the Agent can call directly. This is the
 * generic, manifest-driven equivalent of the hand-coded
 * {@link KaliToolbox}: same shape, but driven by data rather than Java
 * factories.
 *
 * <h2>Why this is the keystone of dynamic capability acquisition</h2>
 * The framework's self-improving loop wants to ask Codex for a new
 * capability, build the resulting Dockerfile, register the new tools
 * with the running Agent, and continue. Without a manifest+factory
 * pair, every new capability needs hand-written Java glue. With them,
 * the agent's toolkit grows automatically as new images get built.
 *
 * <h2>How it composes with images</h2>
 * Two flavours, picked by the caller:
 * <ul>
 *   <li>{@link #fromBuiltImage} — capability runs against an existing
 *       image tag (e.g. one built once and reused across many tools, like
 *       the shared Kali toolbox).</li>
 *   <li>{@link #fromInlineDockerfile} — capability runs against an
 *       inline Dockerfile that the runtime builds lazily; the SHA1 hash
 *       of the Dockerfile is the cache key.</li>
 * </ul>
 *
 * <h2>Output handling</h2>
 * By default the tool returns the container's stdout truncated to ~1.5KB.
 * Callers can supply a per-tool or per-manifest output parser via
 * {@link Builder#parserFor} for tools whose stdout benefits from
 * structured post-processing (e.g. nmap → typed Findings).
 */
public final class ManifestToolFactory {

    private static final Logger logger = LoggerFactory.getLogger(ManifestToolFactory.class);

    private final ContainerSkillRuntime runtime;
    private final String image;            // mutually exclusive with dockerfile
    private final String dockerfile;       // mutually exclusive with image
    private final Map<String, Function<String, String>> outputParsers;
    private final Function<String, String> defaultParser;

    private ManifestToolFactory(Builder b) {
        this.runtime = b.runtime;
        this.image = b.image;
        this.dockerfile = b.dockerfile;
        this.outputParsers = Map.copyOf(b.outputParsers);
        this.defaultParser = b.defaultParser != null
                ? b.defaultParser
                : ManifestToolFactory::truncateForAgent;
        if ((image == null) == (dockerfile == null)) {
            throw new IllegalArgumentException(
                    "exactly one of image / dockerfile must be set");
        }
    }

    /** Materialise every capability in the manifest into a callable BaseTool. */
    public List<BaseTool> toTools(CapabilityManifest manifest) {
        List<BaseTool> tools = new ArrayList<>(manifest.size());
        for (Capability cap : manifest.tools()) {
            try {
                tools.add(toTool(cap));
            } catch (RuntimeException e) {
                // Skip a single bad capability rather than failing the
                // whole manifest — a hallucinated tool definition shouldn't
                // poison the rest of the toolkit.
                logger.warn("[manifest:{}] skipping capability '{}': {}",
                        manifest.name(), cap.name(), e.getMessage());
            }
        }
        return tools;
    }

    /** Materialise a single capability. Public for callers that filter manifests. */
    public BaseTool toTool(Capability cap) {
        ContainerSkillSpec.Builder specBuilder = (image != null)
                ? ContainerSkillSpec.builder(image)
                : ContainerSkillSpec.builderFromDockerfile(dockerfile);
        specBuilder
                .entrypoint("/bin/sh")
                .command(List.of("-c", cap.entrypoint()))
                .network(cap.network())
                .timeout(cap.timeout());
        if (cap.memory() != null) specBuilder.memory(cap.memory());
        cap.envFromParams().forEach(specBuilder::envFromParam);

        Function<String, String> parser = outputParsers.getOrDefault(cap.name(), defaultParser);

        return ContainerBackedBaseTool.builder()
                .name(cap.name())
                .description(cap.description())
                .triggerWhen(cap.triggerWhen())
                .avoidWhen(cap.avoidWhen())
                .category(cap.category().isEmpty() ? "container" : cap.category())
                .tags(cap.tags())
                .parameterSchema(toJsonSchema(cap.parameters()))
                .spec(specBuilder.build())
                .runtime(runtime)
                .outputParser(parser)
                .build();
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    /** Translate the manifest's parameter list into a JSON Schema draft 7-ish object. */
    private static Map<String, Object> toJsonSchema(List<Parameter> params) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Parameter p : params) {
            properties.put(p.name(), Map.of(
                    "type", p.type(),
                    "description", p.description()));
            if (p.required()) required.add(p.name());
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    private static String truncateForAgent(String stdout) {
        if (stdout == null) return "(no output)";
        String s = stdout.trim();
        if (s.isEmpty()) return "(no output)";
        if (s.length() > 1500) s = s.substring(0, 1497) + "...";
        return s;
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private ContainerSkillRuntime runtime;
        private String image;
        private String dockerfile;
        private final Map<String, Function<String, String>> outputParsers = new LinkedHashMap<>();
        private Function<String, String> defaultParser;

        public Builder runtime(ContainerSkillRuntime r) { this.runtime = r; return this; }
        public Builder image(String tag) { this.image = tag; return this; }
        public Builder dockerfile(String src) { this.dockerfile = src; return this; }

        /**
         * Register a parser for a specific tool name. Useful when one tool
         * needs structured parsing (e.g. nmap) while the rest of the manifest
         * is happy with raw stdout.
         */
        public Builder parserFor(String toolName, Function<String, String> parser) {
            this.outputParsers.put(toolName, parser);
            return this;
        }

        /** Override the default parser applied when a tool has no specific parser. */
        public Builder defaultParser(Function<String, String> parser) {
            this.defaultParser = parser;
            return this;
        }

        public ManifestToolFactory build() { return new ManifestToolFactory(this); }
    }
}
