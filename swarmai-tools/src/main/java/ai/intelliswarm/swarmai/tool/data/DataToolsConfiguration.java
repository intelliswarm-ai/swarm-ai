package ai.intelliswarm.swarmai.tool.data;

import ai.intelliswarm.swarmai.tool.common.config.SpringAiToolBindingSupport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.util.function.Function;

/**
 * Spring AI function-bean registrations for the {@code data} tool category:
 * weather, external data feeds. Add new data-category tools here.
 */
@Configuration
public class DataToolsConfiguration {

    @Bean
    @Description("Get current weather or a 5-day / 3-hour forecast for a city or lat/lon coordinate. " +
            "Requires OPENWEATHER_API_KEY.")
    public Function<OpenWeatherMapTool.Request, String> weather(OpenWeatherMapTool tool) {
        return SpringAiToolBindingSupport.bind(tool);
    }
}
