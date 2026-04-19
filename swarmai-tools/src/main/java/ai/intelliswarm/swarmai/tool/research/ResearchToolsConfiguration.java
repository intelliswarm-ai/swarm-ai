package ai.intelliswarm.swarmai.tool.research;

import ai.intelliswarm.swarmai.tool.common.config.SpringAiToolBindingSupport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.util.function.Function;

/**
 * Spring AI function-bean registrations for the {@code research} tool category:
 * Wikipedia, arXiv, Wolfram Alpha. Add new research-category tools here.
 */
@Configuration
public class ResearchToolsConfiguration {

    @Bean
    @Description("Look up factual information on Wikipedia. Supports 'summary' (abstract for a page title), " +
            "'search' (ranked titles matching a query), and 'page' (full article body). No API key required.")
    public Function<WikipediaTool.Request, String> wikipedia(WikipediaTool tool) {
        return SpringAiToolBindingSupport.bind(tool);
    }

    @Bean
    @Description("Answer math, science, engineering, unit-conversion, and general knowledge questions via " +
            "Wolfram Alpha's computational engine. Requires WOLFRAM_APPID.")
    public Function<WolframAlphaTool.Request, String> wolfram_alpha(WolframAlphaTool tool) {
        return SpringAiToolBindingSupport.bind(tool);
    }

    @Bean
    @Description("Search arXiv for scientific preprints (CS, physics, math, biology). 'search' for keywords, " +
            "'get' for a specific arXiv ID. No API key required.")
    public Function<ArxivTool.Request, String> arxiv_search(ArxivTool tool) {
        return SpringAiToolBindingSupport.bind(tool);
    }
}
