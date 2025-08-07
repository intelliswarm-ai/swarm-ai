package ai.intelliswarm.swarmai.swarm;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import ai.intelliswarm.swarmai.process.Process;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.memory.Memory;
import ai.intelliswarm.swarmai.knowledge.Knowledge;
import ai.intelliswarm.swarmai.event.SwarmEvent;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class Swarm {

    @NotNull
    private final String id;
    
    @NotEmpty
    private final List<Agent> agents;
    
    @NotEmpty
    private final List<Task> tasks;
    
    private final ProcessType processType;
    private final Agent managerAgent;
    private final boolean verbose;
    private final Memory memory;
    private final Knowledge knowledge;
    private final Map<String, Object> config;
    private final Integer maxRpm;
    private final String language;
    
    @JsonIgnore
    private ApplicationEventPublisher eventPublisher;
    
    private final LocalDateTime createdAt;
    private SwarmOutput lastOutput;
    private SwarmStatus status = SwarmStatus.READY;

    private Swarm(Builder builder) {
        this.id = builder.id != null ? builder.id : UUID.randomUUID().toString();
        this.agents = new ArrayList<>(builder.agents);
        this.tasks = new ArrayList<>(builder.tasks);
        this.processType = builder.processType;
        this.managerAgent = builder.managerAgent;
        this.verbose = builder.verbose;
        this.memory = builder.memory;
        this.knowledge = builder.knowledge;
        this.config = new HashMap<>(builder.config);
        this.maxRpm = builder.maxRpm;
        this.language = builder.language;
        this.eventPublisher = builder.eventPublisher;
        this.createdAt = LocalDateTime.now();
        
        validateConfiguration();
        assignAgentsToTasks();
    }

    public SwarmOutput kickoff(Map<String, Object> inputs) {
        publishEvent(SwarmEvent.Type.SWARM_STARTED, "Swarm kickoff initiated");
        
        try {
            status = SwarmStatus.RUNNING;
            
            Process process = createProcess();
            SwarmOutput output = process.execute(tasks, inputs);
            
            this.lastOutput = output;
            status = SwarmStatus.COMPLETED;
            
            publishEvent(SwarmEvent.Type.SWARM_COMPLETED, "Swarm execution completed successfully");
            
            return output;
            
        } catch (Exception e) {
            status = SwarmStatus.FAILED;
            publishEvent(SwarmEvent.Type.SWARM_FAILED, "Swarm execution failed: " + e.getMessage());
            throw new RuntimeException("Swarm execution failed", e);
        }
    }

    public CompletableFuture<SwarmOutput> kickoffAsync(Map<String, Object> inputs) {
        return CompletableFuture.supplyAsync(() -> kickoff(inputs));
    }

    public List<SwarmOutput> kickoffForEach(List<Map<String, Object>> inputsList) {
        publishEvent(SwarmEvent.Type.SWARM_STARTED, "Swarm kickoff for each initiated with " + inputsList.size() + " inputs");
        
        return inputsList.stream()
            .map(this::kickoff)
            .collect(Collectors.toList());
    }

    public CompletableFuture<List<SwarmOutput>> kickoffForEachAsync(List<Map<String, Object>> inputsList) {
        List<CompletableFuture<SwarmOutput>> futures = inputsList.stream()
            .map(this::kickoffAsync)
            .collect(Collectors.toList());
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList()));
    }

    private Process createProcess() {
        return switch (processType) {
            case SEQUENTIAL -> new ai.intelliswarm.swarmai.process.SequentialProcess(agents, eventPublisher);
            case HIERARCHICAL -> new ai.intelliswarm.swarmai.process.HierarchicalProcess(agents, managerAgent, eventPublisher);
        };
    }

    private void validateConfiguration() {
        if (processType == ProcessType.HIERARCHICAL && managerAgent == null) {
            throw new IllegalStateException("Manager agent is required for hierarchical process");
        }
        
        if (tasks.isEmpty()) {
            throw new IllegalStateException("At least one task is required");
        }
        
        if (agents.isEmpty()) {
            throw new IllegalStateException("At least one agent is required");
        }
    }

    private void assignAgentsToTasks() {
        List<Task> tasksWithoutAgents = tasks.stream()
            .filter(task -> task.getAgent() == null)
            .collect(Collectors.toList());
        
        if (!tasksWithoutAgents.isEmpty() && !agents.isEmpty()) {
            // Simple round-robin assignment for tasks without specific agents
            for (int i = 0; i < tasksWithoutAgents.size(); i++) {
                Agent assignedAgent = agents.get(i % agents.size());
                // Note: This would require modifying Task to allow agent assignment post-creation
                // or implementing a task assignment strategy
            }
        }
    }

    public void resetMemory() {
        if (memory != null) {
            memory.clear();
            publishEvent(SwarmEvent.Type.MEMORY_RESET, "Swarm memory has been reset");
        }
    }

    public void shareMemory(Memory sharedMemory) {
        this.agents.forEach(agent -> {
            // This would require agent to have a setMemory method
            // or implementing a memory sharing strategy
        });
    }

    private void publishEvent(SwarmEvent.Type type, String message) {
        if (eventPublisher != null) {
            SwarmEvent event = new SwarmEvent(this, type, message, id);
            eventPublisher.publishEvent(event);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private List<Agent> agents = new ArrayList<>();
        private List<Task> tasks = new ArrayList<>();
        private ProcessType processType = ProcessType.SEQUENTIAL;
        private Agent managerAgent;
        private boolean verbose = false;
        private Memory memory;
        private Knowledge knowledge;
        private Map<String, Object> config = new HashMap<>();
        private Integer maxRpm;
        private String language = "en";
        private ApplicationEventPublisher eventPublisher;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder agents(List<Agent> agents) {
            this.agents = new ArrayList<>(agents);
            return this;
        }

        public Builder agent(Agent agent) {
            this.agents.add(agent);
            return this;
        }

        public Builder tasks(List<Task> tasks) {
            this.tasks = new ArrayList<>(tasks);
            return this;
        }

        public Builder task(Task task) {
            this.tasks.add(task);
            return this;
        }

        public Builder process(ProcessType processType) {
            this.processType = processType;
            return this;
        }

        public Builder managerAgent(Agent managerAgent) {
            this.managerAgent = managerAgent;
            return this;
        }

        public Builder verbose(boolean verbose) {
            this.verbose = verbose;
            return this;
        }

        public Builder memory(Memory memory) {
            this.memory = memory;
            return this;
        }

        public Builder knowledge(Knowledge knowledge) {
            this.knowledge = knowledge;
            return this;
        }

        public Builder config(String key, Object value) {
            this.config.put(key, value);
            return this;
        }

        public Builder maxRpm(Integer maxRpm) {
            this.maxRpm = maxRpm;
            return this;
        }

        public Builder language(String language) {
            this.language = language;
            return this;
        }

        public Builder eventPublisher(ApplicationEventPublisher eventPublisher) {
            this.eventPublisher = eventPublisher;
            return this;
        }

        public Swarm build() {
            return new Swarm(this);
        }
    }

    // Getters
    public String getId() { return id; }
    public List<Agent> getAgents() { return new ArrayList<>(agents); }
    public List<Task> getTasks() { return new ArrayList<>(tasks); }
    public ProcessType getProcessType() { return processType; }
    public Agent getManagerAgent() { return managerAgent; }
    public boolean isVerbose() { return verbose; }
    public Memory getMemory() { return memory; }
    public Knowledge getKnowledge() { return knowledge; }
    public Map<String, Object> getConfig() { return new HashMap<>(config); }
    public Integer getMaxRpm() { return maxRpm; }
    public String getLanguage() { return language; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public SwarmOutput getLastOutput() { return lastOutput; }
    public SwarmStatus getStatus() { return status; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Swarm)) return false;
        Swarm swarm = (Swarm) o;
        return Objects.equals(id, swarm.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Swarm{" +
                "id='" + id + '\'' +
                ", agents=" + agents.size() +
                ", tasks=" + tasks.size() +
                ", processType=" + processType +
                ", status=" + status +
                '}';
    }

    public enum SwarmStatus {
        READY,
        RUNNING,
        COMPLETED,
        FAILED
    }
}