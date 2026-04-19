package ai.intelliswarm.swarmai.tool.messaging;

import ai.intelliswarm.swarmai.tool.common.config.SpringAiToolBindingSupport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.util.function.Function;

/**
 * Spring AI function-bean registrations for the {@code messaging} tool category:
 * Kafka producer (later: RabbitMQ, Google Pub/Sub, AWS SQS, etc.).
 */
@Configuration
public class MessagingToolsConfiguration {

    @Bean
    @ConditionalOnBean(KafkaProducerTool.class)
    @Description("Publish a message to a Kafka topic. Requires KAFKA_BOOTSTRAP_SERVERS.")
    public Function<KafkaProducerTool.Request, String> kafka_produce(KafkaProducerTool tool) {
        return SpringAiToolBindingSupport.bind(tool);
    }
}
