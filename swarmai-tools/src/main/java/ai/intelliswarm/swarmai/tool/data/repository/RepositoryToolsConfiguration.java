package ai.intelliswarm.swarmai.tool.data.repository;

import ai.intelliswarm.swarmai.tool.common.config.SpringAiToolBindingSupport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.util.function.Function;

/**
 * Spring AI function-bean registration for the Spring Data repository tool. Gated on the tool
 * bean being present — it requires {@code spring-data-commons} on the classpath.
 */
@Configuration
public class RepositoryToolsConfiguration {

    @Bean
    @ConditionalOnBean(SpringDataRepositoryTool.class)
    @Description("Query the application's Spring Data repositories. 'list_repositories' to enumerate, " +
            "'list_methods' for a repo's callable methods, 'invoke' to run a method with JSON args.")
    public Function<SpringDataRepositoryTool.Request, String> repo_query(SpringDataRepositoryTool tool) {
        return SpringAiToolBindingSupport.bind(tool);
    }
}
