package ai.intelliswarm.swarmai.skill;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Container metadata for a {@link SkillType#CONTAINER} skill.
 *
 * <p>A container skill is executed by spawning a one-shot Docker container,
 * passing parameters as environment variables, and capturing stdout as the
 * skill's return value. Use cases the in-process Groovy runtime can't reach:
 * pentest tools (nmap, hydra), data science with native deps, headless
 * browsers, anything that wants to be a Linux box.
 *
 * <p>Phase-1 fields (this record):
 * <ul>
 *   <li>{@link #image} — pre-built image reference, e.g. {@code alpine:3.19}.</li>
 *   <li>{@link #command} — optional override of the image's {@code CMD} /
 *       {@code ENTRYPOINT}. When empty, the image's default runs.</li>
 *   <li>{@link #envFromParams} — maps skill parameter names to environment
 *       variable names inside the container. {@code params.target →
 *       envFromParams.put("target", "TARGET")} sets {@code TARGET=<value>}.</li>
 *   <li>{@link #network} — Docker network mode. Default {@code "none"} for
 *       safety; override to {@code "bridge"} or {@code "host"} when the
 *       skill needs network access.</li>
 *   <li>{@link #memory} — Docker memory cap, e.g. {@code "256m"} or
 *       {@code "1g"}. Null = no cap (not recommended).</li>
 *   <li>{@link #timeout} — wall-clock cap on container execution. Mandatory.</li>
 * </ul>
 *
 * <p>Future fields (Phase 2): inline {@code dockerfile} for builds, mounted
 * input/output workspace, structured result file (e.g. {@code /work/result.json}).
 */
public record ContainerSkillSpec(
        String image,
        String dockerfile,
        String entrypoint,
        List<String> command,
        Map<String, String> envFromParams,
        String network,
        String memory,
        Duration timeout) {

    /** Default network mode — no egress unless the skill explicitly opts in. */
    public static final String DEFAULT_NETWORK = "none";

    /** Default execution cap — most skills are short-lived. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    public ContainerSkillSpec {
        // Normalise inputs first: trim whitespace so round-trips through
        // SKILL.md text (which uses .trim() during parsing) are stable.
        if (image != null) image = image.trim();
        if (dockerfile != null) dockerfile = dockerfile.trim();
        if (entrypoint != null) entrypoint = entrypoint.trim();

        boolean hasImage = image != null && !image.isEmpty();
        boolean hasDockerfile = dockerfile != null && !dockerfile.isEmpty();
        if (!hasImage && !hasDockerfile) {
            throw new IllegalArgumentException(
                    "either image (pre-built tag) or dockerfile (inline build) is required");
        }
        // After trim, replace nulls with empty so callers can rely on non-null fields.
        if (image == null) image = "";
        if (dockerfile == null) dockerfile = "";
        command = command == null ? List.of() : List.copyOf(command);
        envFromParams = envFromParams == null
                ? Map.of()
                : Map.copyOf(envFromParams);
        if (network == null || network.isBlank()) network = DEFAULT_NETWORK;
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException(
                    "timeout must be a positive Duration; got " + timeout);
        }
    }

    /** True when the spec carries an inline Dockerfile that the runtime must build. */
    public boolean hasDockerfile() {
        return dockerfile != null && !dockerfile.isBlank();
    }

    /** True when the spec carries a pre-built image tag the runtime must pull. */
    public boolean hasImage() {
        return image != null && !image.isBlank();
    }

    /** True when the spec overrides the image's default entrypoint. */
    public boolean hasEntrypoint() {
        return entrypoint != null && !entrypoint.isBlank();
    }

    /** Convenience: spec with defaults for everything but image. */
    public static ContainerSkillSpec of(String image) {
        return new ContainerSkillSpec(image, null, null, List.of(), Map.of(),
                DEFAULT_NETWORK, null, DEFAULT_TIMEOUT);
    }

    /** Convenience: spec backed by an inline Dockerfile (built lazily by the runtime). */
    public static ContainerSkillSpec ofDockerfile(String dockerfile) {
        return new ContainerSkillSpec(null, dockerfile, null, List.of(), Map.of(),
                DEFAULT_NETWORK, null, DEFAULT_TIMEOUT);
    }

    /** Builder for readability when there are several knobs. */
    public static Builder builder(String image) {
        return new Builder(image);
    }

    /** Builder anchored on an inline Dockerfile (built lazily by the runtime). */
    public static Builder builderFromDockerfile(String dockerfile) {
        Builder b = new Builder(null);
        b.dockerfile = Objects.requireNonNull(dockerfile, "dockerfile");
        return b;
    }

    public static final class Builder {
        private String image;
        private String dockerfile;
        private String entrypoint;
        private List<String> command = List.of();
        private final Map<String, String> envFromParams = new LinkedHashMap<>();
        private String network = DEFAULT_NETWORK;
        private String memory;
        private Duration timeout = DEFAULT_TIMEOUT;

        Builder(String image) {
            this.image = image; // null is OK when paired with dockerfile() later
        }

        /** Override or supply the image tag (mutually exclusive with dockerfile, except as build target). */
        public Builder image(String tag) {
            this.image = tag;
            return this;
        }

        /** Set an inline Dockerfile — runtime will build it before running. */
        public Builder dockerfile(String src) {
            this.dockerfile = src;
            return this;
        }

        /**
         * Override the image's default ENTRYPOINT. Required when wrapping an
         * image whose entrypoint is the tool itself (e.g. {@code instrumentisto/nmap}
         * sets {@code ENTRYPOINT=nmap}, so without this override a
         * {@code command: [sh, -c, ...]} would run {@code nmap sh -c ...}).
         * Setting {@code entrypoint("sh")} makes the command list interpreted
         * by sh instead.
         */
        public Builder entrypoint(String value) {
            this.entrypoint = value;
            return this;
        }

        public Builder command(List<String> cmd) {
            this.command = cmd == null ? List.of() : List.copyOf(cmd);
            return this;
        }

        public Builder command(String... cmd) {
            return command(List.of(cmd));
        }

        public Builder envFromParam(String paramName, String envVarName) {
            this.envFromParams.put(
                    Objects.requireNonNull(paramName, "paramName"),
                    Objects.requireNonNull(envVarName, "envVarName"));
            return this;
        }

        public Builder network(String mode) {
            this.network = (mode == null || mode.isBlank()) ? DEFAULT_NETWORK : mode;
            return this;
        }

        public Builder memory(String memSpec) {
            this.memory = memSpec;
            return this;
        }

        public Builder timeout(Duration t) {
            this.timeout = t;
            return this;
        }

        public ContainerSkillSpec build() {
            return new ContainerSkillSpec(image, dockerfile, entrypoint, command, envFromParams,
                    network, memory, timeout);
        }
    }
}
