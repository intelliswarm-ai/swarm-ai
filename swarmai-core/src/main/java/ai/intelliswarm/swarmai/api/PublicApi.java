package ai.intelliswarm.swarmai.api;

import java.lang.annotation.*;

/**
 * Marks a class, interface, or method as part of the stable public API.
 * Public API elements are covered by semantic versioning:
 * - Breaking changes require a major version bump
 * - New additions require a minor version bump
 *
 * <p>Consumers may depend on the signature and behavior of annotated elements.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface PublicApi {
    /** Optional note about stability or planned changes. */
    String since() default "";
}
