package ai.intelliswarm.swarmai.tool.productivity;

import ai.intelliswarm.swarmai.tool.common.config.SpringAiToolBindingSupport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.util.function.Function;

/**
 * Spring AI function-bean registrations for the {@code productivity} tool category:
 * Jira (later: Gmail, Google Drive, Linear, Confluence, etc.).
 */
@Configuration
public class ProductivityToolsConfiguration {

    @Bean
    @Description("Work with Jira Cloud: search issues via JQL, get a single issue, create an issue, or add " +
            "a comment. Requires JIRA_BASE_URL, JIRA_EMAIL, JIRA_API_TOKEN.")
    public Function<JiraTool.Request, String> jira(JiraTool tool) {
        return SpringAiToolBindingSupport.bind(tool);
    }
}
