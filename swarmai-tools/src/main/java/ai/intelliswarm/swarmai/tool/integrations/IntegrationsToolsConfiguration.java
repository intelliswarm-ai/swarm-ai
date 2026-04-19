package ai.intelliswarm.swarmai.tool.integrations;

import ai.intelliswarm.swarmai.tool.common.config.SpringAiToolBindingSupport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.util.function.Function;

/**
 * Spring AI function-bean registrations for the {@code integrations} tool category:
 * universal OpenAPI client (later: GraphQL, gRPC, SOAP, Zapier NLA, IFTTT, MCP, etc.).
 */
@Configuration
public class IntegrationsToolsConfiguration {

    @Bean
    @ConditionalOnBean(OpenApiToolkit.class)
    @Description("Universal OpenAPI 3.x client. 'list_operations' to enumerate endpoints, 'invoke' to call " +
            "a specific operationId with parameters. Load the spec from 'spec_url' or inline 'spec'.")
    public Function<OpenApiToolkit.Request, String> openapi_call(OpenApiToolkit tool) {
        return SpringAiToolBindingSupport.bind(tool);
    }
}
