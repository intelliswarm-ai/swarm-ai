package ai.intelliswarm.swarmai.studio;

import ai.intelliswarm.swarmai.observability.decision.DecisionTracer;
import ai.intelliswarm.swarmai.observability.replay.EventStore;
import ai.intelliswarm.swarmai.studio.controller.StudioController;
import ai.intelliswarm.swarmai.studio.controller.StudioPageController;
import ai.intelliswarm.swarmai.studio.event.StudioEventBroadcaster;
import ai.intelliswarm.swarmai.studio.service.WorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for the SwarmAI Studio Web UI.
 *
 * Activated when {@code swarmai.studio.enabled=true} is set.
 * Creates all Studio beans: event broadcaster, workflow service,
 * REST controller, and page controller.
 *
 * Dependencies on EventStore and DecisionTracer are optional --
 * the Studio will function with reduced capabilities if the
 * observability subsystem is not fully enabled.
 */
@Configuration
@ConditionalOnProperty(prefix = "swarmai.studio", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(StudioProperties.class)
public class StudioAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(StudioAutoConfiguration.class);

    @Autowired(required = false)
    private EventStore eventStore;

    @Autowired(required = false)
    private DecisionTracer decisionTracer;

    /**
     * Creates the SSE event broadcaster that pushes live SwarmEvents
     * to connected Studio clients.
     */
    @Bean
    @ConditionalOnMissingBean
    public StudioEventBroadcaster studioEventBroadcaster(StudioProperties properties) {
        logger.info("Initializing StudioEventBroadcaster (maxConnections={}, bufferSize={})",
                properties.getMaxSseConnections(), properties.getEventBufferSize());
        return new StudioEventBroadcaster(properties);
    }

    /**
     * Creates the workflow service that provides workflow listing,
     * graph building, event querying, and metrics aggregation.
     */
    @Bean
    @ConditionalOnMissingBean
    public WorkflowService workflowService() {
        logger.info("Initializing WorkflowService (eventStore={}, decisionTracer={})",
                eventStore != null ? "available" : "not available",
                decisionTracer != null ? "available" : "not available");
        return new WorkflowService(eventStore, decisionTracer);
    }

    /**
     * Creates the REST API controller for Studio endpoints.
     */
    @Bean
    @ConditionalOnMissingBean
    public StudioController studioController(
            WorkflowService workflowService,
            StudioEventBroadcaster studioEventBroadcaster) {
        logger.info("Initializing StudioController at /api/studio");
        return new StudioController(workflowService, studioEventBroadcaster, decisionTracer);
    }

    /**
     * Creates the page controller for serving the Studio SPA.
     */
    @Bean
    @ConditionalOnMissingBean
    public StudioPageController studioPageController() {
        logger.info("Initializing StudioPageController at /studio");
        return new StudioPageController();
    }
}
