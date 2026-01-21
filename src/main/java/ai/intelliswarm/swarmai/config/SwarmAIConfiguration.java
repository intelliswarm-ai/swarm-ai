package ai.intelliswarm.swarmai.config;

import ai.intelliswarm.swarmai.event.SwarmEvent;
import ai.intelliswarm.swarmai.observability.config.ObservabilityProperties;
import ai.intelliswarm.swarmai.observability.event.EnrichedSwarmEvent;
import ai.intelliswarm.swarmai.observability.replay.EventStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Configuration
public class SwarmAIConfiguration {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    @Component
    public static class SwarmEventListener {

        private static final Logger logger = LoggerFactory.getLogger(SwarmEventListener.class);

        private final EventStore eventStore;
        private final ObservabilityProperties observabilityProperties;

        @Autowired
        public SwarmEventListener(
                @Autowired(required = false) EventStore eventStore,
                @Autowired(required = false) ObservabilityProperties observabilityProperties) {
            this.eventStore = eventStore;
            this.observabilityProperties = observabilityProperties;
        }

        @EventListener
        public void handleSwarmEvent(SwarmEvent event) {
            logger.info("SwarmEvent [{}]: {} (SwarmId: {})",
                event.getType(), event.getMessage(), event.getSwarmId());

            // Store enriched event if observability is enabled
            if (observabilityProperties != null && observabilityProperties.isReplayEnabled() && eventStore != null) {
                try {
                    EnrichedSwarmEvent enrichedEvent = EnrichedSwarmEvent.fromSwarmEvent(event);
                    eventStore.store(enrichedEvent);
                } catch (Exception e) {
                    logger.debug("Failed to store enriched event: {}", e.getMessage());
                }
            }
        }
    }

    @ConfigurationProperties(prefix = "swarmai.default")
    public static class SwarmAIProperties {
        private Integer maxRpm = 30;
        private Integer maxExecutionTime = 300000; // 5 minutes
        private boolean verbose = false;
        private String language = "en";

        // Getters and setters
        public Integer getMaxRpm() { return maxRpm; }
        public void setMaxRpm(Integer maxRpm) { this.maxRpm = maxRpm; }
        public Integer getMaxExecutionTime() { return maxExecutionTime; }
        public void setMaxExecutionTime(Integer maxExecutionTime) { this.maxExecutionTime = maxExecutionTime; }
        public boolean isVerbose() { return verbose; }
        public void setVerbose(boolean verbose) { this.verbose = verbose; }
        public String getLanguage() { return language; }
        public void setLanguage(String language) { this.language = language; }
    }
}