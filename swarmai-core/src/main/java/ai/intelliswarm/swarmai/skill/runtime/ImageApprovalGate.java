package ai.intelliswarm.swarmai.skill.runtime;

import java.util.Objects;

/**
 * Pluggable human-in-the-loop checkpoint for container image acquisition.
 * The {@link ContainerSkillRuntime} consults this gate before building a
 * Dockerfile or pulling a pre-built image tag. The gate's job is to give
 * the user (or a programmatic policy) the chance to say "yes, that
 * Dockerfile is safe to build" or "no, this image is suspicious — block".
 *
 * <h2>Why this matters in a self-improving system</h2>
 * The framework will increasingly accept Dockerfiles emitted by Codex /
 * Claude / other LLMs in response to capability gaps. Without this gate,
 * a hallucinated or jailbroken model could:
 * <ul>
 *   <li>pull a malicious base image (typo-squatted registry tag),</li>
 *   <li>exfiltrate environment data during build via {@code RUN curl …},</li>
 *   <li>install backdoors that activate at run time,</li>
 *   <li>consume large bandwidth on bloated bases (10+ GB images).</li>
 * </ul>
 * {@link ContainerPolicy} catches structural patterns; this gate catches
 * the rest by putting the human (or a stricter automated authority) in
 * the loop for novel images.
 *
 * <h2>Decision semantics</h2>
 * <ul>
 *   <li>{@link Decision#APPROVE} — proceed with this build/pull <em>this
 *       time only</em>. The runtime will ask again next time.</li>
 *   <li>{@link Decision#APPROVE_AND_REMEMBER} — proceed and persist the
 *       approval (e.g. to {@code ~/.swarmai/approved-images.txt}) so
 *       future runs skip the prompt for the same image.</li>
 *   <li>{@link Decision#DENY} — abort. The runtime throws
 *       {@link ImageApprovalDeniedException}.</li>
 * </ul>
 *
 * <h2>Default</h2>
 * The runtime ships with {@link AutoApprovalGate} (always APPROVE) so
 * existing tests and callers don't break. Production deployments and
 * self-improving examples should opt into {@link ConsoleApprovalGate}
 * or wrap one with {@link RememberingApprovalGate} for persistent
 * decisions.
 */
@FunctionalInterface
public interface ImageApprovalGate {

    /** Consult the gate. Implementations should be idempotent for cached approvals. */
    Decision approve(Request request);

    /** Three-state decision returned by the gate. */
    enum Decision { APPROVE, APPROVE_AND_REMEMBER, DENY }

    /**
     * Information presented to the gate when an approval is required.
     *
     * @param imageTag        the resolved image tag the runtime will pull
     *                        or use as the build target (e.g.
     *                        {@code swarmai/skill-abc123:1}).
     * @param dockerfile      the full Dockerfile content when this is a
     *                        build request; {@code null} for a pull-only
     *                        request against a pre-existing tag.
     * @param source          a short string describing where the image came
     *                        from (e.g. {@code "codex-generated:gap-X"},
     *                        {@code "user-supplied"}, {@code "kali-toolbox"}).
     * @param baseImage       the FROM image extracted from the Dockerfile,
     *                        or just the image tag for pull-only requests.
     *                        Used for quick pattern-matching in the gate.
     */
    record Request(String imageTag,
                   String dockerfile,
                   String source,
                   String baseImage) {

        public Request {
            Objects.requireNonNull(imageTag, "imageTag");
            source = source == null ? "unknown" : source;
            baseImage = baseImage == null ? "" : baseImage;
        }

        /** True for build requests; false for pre-built-image pull requests. */
        public boolean isBuild() {
            return dockerfile != null && !dockerfile.isBlank();
        }
    }

    /** Thrown by the runtime when the gate returns {@link Decision#DENY}. */
    class ImageApprovalDeniedException extends RuntimeException {
        public ImageApprovalDeniedException(String message) { super(message); }
    }
}
