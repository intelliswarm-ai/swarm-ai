package ai.intelliswarm.swarmai.config;

import ai.intelliswarm.swarmai.event.SwarmEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
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

        @EventListener
        public void handleSwarmEvent(SwarmEvent event) {
            logger.info("SwarmEvent [{}]: {} (SwarmId: {})", 
                event.getType(), event.getMessage(), event.getSwarmId());
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