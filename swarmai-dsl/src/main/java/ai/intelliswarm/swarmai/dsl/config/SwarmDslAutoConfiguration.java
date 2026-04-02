package ai.intelliswarm.swarmai.dsl.config;

import ai.intelliswarm.swarmai.dsl.SwarmLoader;
import ai.intelliswarm.swarmai.dsl.compiler.SwarmCompiler;
import ai.intelliswarm.swarmai.dsl.parser.YamlSwarmParser;
import ai.intelliswarm.swarmai.knowledge.Knowledge;
import ai.intelliswarm.swarmai.memory.Memory;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Spring Boot auto-configuration for the YAML DSL.
 * Automatically wires available ChatClients, tools, memory, and knowledge
 * into a ready-to-use {@link SwarmLoader} bean.
 */
@AutoConfiguration
@ConditionalOnClass(SwarmLoader.class)
public class SwarmDslAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public YamlSwarmParser yamlSwarmParser() {
        return new YamlSwarmParser();
    }

    @Bean
    @ConditionalOnMissingBean
    public SwarmCompiler swarmCompiler(
            @Autowired(required = false) ChatClient chatClient,
            @Autowired(required = false) List<BaseTool> tools,
            @Autowired(required = false) ApplicationEventPublisher eventPublisher,
            @Autowired(required = false) Memory memory,
            @Autowired(required = false) Knowledge knowledge) {

        SwarmCompiler.Builder builder = SwarmCompiler.builder();

        if (chatClient != null) {
            builder.chatClient(chatClient);
        }

        if (tools != null && !tools.isEmpty()) {
            Map<String, BaseTool> toolMap = tools.stream()
                    .collect(Collectors.toMap(BaseTool::getFunctionName, Function.identity(), (a, b) -> a));
            builder.tools(toolMap);
        }

        if (eventPublisher != null) {
            builder.eventPublisher(eventPublisher);
        }
        if (memory != null) {
            builder.memory(memory);
        }
        if (knowledge != null) {
            builder.knowledge(knowledge);
        }

        return builder.build();
    }

    @Bean
    @ConditionalOnMissingBean
    public SwarmLoader swarmLoader(YamlSwarmParser parser, SwarmCompiler compiler) {
        return new SwarmLoader(parser, compiler);
    }
}
