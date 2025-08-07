package ai.intelliswarm.swarmai.event;

import org.springframework.context.ApplicationEvent;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

public class SwarmEvent extends ApplicationEvent {

    private final Type type;
    private final String message;
    private final String swarmId;
    private final LocalDateTime eventTime;
    private final Map<String, Object> metadata;

    public SwarmEvent(Object source, Type type, String message, String swarmId) {
        super(source);
        this.type = type;
        this.message = message;
        this.swarmId = swarmId;
        this.eventTime = LocalDateTime.now();
        this.metadata = new HashMap<>();
    }

    public SwarmEvent(Object source, Type type, String message, String swarmId, Map<String, Object> metadata) {
        super(source);
        this.type = type;
        this.message = message;
        this.swarmId = swarmId;
        this.eventTime = LocalDateTime.now();
        this.metadata = new HashMap<>(metadata);
    }

    public enum Type {
        // Swarm Events
        SWARM_STARTED,
        SWARM_COMPLETED,
        SWARM_FAILED,
        MEMORY_RESET,
        
        // Process Events
        PROCESS_STARTED,
        PROCESS_COMPLETED,
        PROCESS_FAILED,
        
        // Task Events
        TASK_STARTED,
        TASK_COMPLETED,
        TASK_FAILED,
        TASK_SKIPPED,
        
        // Agent Events
        AGENT_STARTED,
        AGENT_COMPLETED,
        AGENT_FAILED,
        
        // Tool Events
        TOOL_STARTED,
        TOOL_COMPLETED,
        TOOL_FAILED,
        
        // Memory Events
        MEMORY_SAVED,
        MEMORY_SEARCHED,
        
        // Knowledge Events
        KNOWLEDGE_QUERIED,
        KNOWLEDGE_SOURCE_ADDED
    }

    // Getters
    public Type getType() { return type; }
    public String getMessage() { return message; }
    public String getSwarmId() { return swarmId; }
    public LocalDateTime getEventTime() { return eventTime; }
    public Map<String, Object> getMetadata() { return new HashMap<>(metadata); }

    @Override
    public String toString() {
        return "SwarmEvent{" +
                "type=" + type +
                ", message='" + message + '\'' +
                ", swarmId='" + swarmId + '\'' +
                ", eventTime=" + eventTime +
                '}';
    }
}