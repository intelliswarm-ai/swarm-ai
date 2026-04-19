package ai.intelliswarm.swarmai.tool.cloud;

import ai.intelliswarm.swarmai.tool.common.config.SpringAiToolBindingSupport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.util.function.Function;

/**
 * Spring AI function-bean registrations for the {@code cloud} tool category:
 * S3 (later: GCS, Azure Blob Storage, AWS Lambda).
 *
 * <p>Each binding is gated on the tool bean actually being loaded — optional-dep tools
 * like S3 only activate when their SDK is on the classpath.
 */
@Configuration
public class CloudToolsConfiguration {

    @Bean
    @ConditionalOnBean(S3Tool.class)
    @Description("Work with AWS S3: list objects under a prefix, read a small text object, write a text " +
            "object, head metadata, or delete.")
    public Function<S3Tool.Request, String> s3_object(S3Tool tool) {
        return SpringAiToolBindingSupport.bind(tool);
    }
}
