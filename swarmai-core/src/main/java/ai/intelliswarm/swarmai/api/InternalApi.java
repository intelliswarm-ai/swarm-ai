package ai.intelliswarm.swarmai.api;

import java.lang.annotation.*;

/**
 * Marks a class, interface, or method as internal implementation detail.
 * Internal API elements may change without notice between any versions.
 *
 * <p>Consumers should NOT depend on annotated elements.
 * If you find yourself needing an internal API, open an issue requesting
 * it be promoted to {@link PublicApi}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface InternalApi {
    /** Reason this is internal, or pointer to the public alternative. */
    String value() default "";
}
