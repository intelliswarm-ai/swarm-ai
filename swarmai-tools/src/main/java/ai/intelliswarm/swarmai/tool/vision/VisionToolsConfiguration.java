package ai.intelliswarm.swarmai.tool.vision;

import ai.intelliswarm.swarmai.tool.common.config.SpringAiToolBindingSupport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.util.function.Function;

/**
 * Spring AI function-bean registrations for the {@code vision} tool category:
 * image generation (DALL-E). Later: SDXL, vision Q&A, etc.
 */
@Configuration
public class VisionToolsConfiguration {

    @Bean
    @Description("Generate images from a text prompt via OpenAI's Images API (DALL-E 3, DALL-E 2, " +
            "gpt-image-1). Returns a URL or base64 bytes; optionally saves to disk.")
    public Function<ImageGenerationTool.Request, String> image_generate(ImageGenerationTool tool) {
        return SpringAiToolBindingSupport.bind(tool);
    }
}
