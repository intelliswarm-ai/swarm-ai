package ai.intelliswarm.swarmai.example;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.process.ProcessType;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class SimpleSwarmExample implements CommandLineRunner {

    private final ChatClient chatClient;
    private final ApplicationEventPublisher eventPublisher;

    public SimpleSwarmExample(ChatClient.Builder chatClientBuilder, ApplicationEventPublisher eventPublisher) {
        this.chatClient = chatClientBuilder.build();
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void run(String... args) throws Exception {
        // Create agents
        Agent researcher = Agent.builder()
            .role("Senior Research Analyst")
            .goal("Uncover cutting-edge developments in AI and data science")
            .backstory("You work at a leading tech think tank. Your expertise lies in identifying emerging trends.")
            .chatClient(chatClient)
            .verbose(true)
            .build();

        Agent writer = Agent.builder()
            .role("Tech Content Strategist")
            .goal("Craft compelling content on tech advancements")
            .backstory("You are a renowned Content Strategist, known for your insightful and engaging articles.")
            .chatClient(chatClient)
            .verbose(true)
            .build();

        // Create tasks
        Task researchTask = Task.builder()
            .description("Conduct a comprehensive analysis of the latest advancements in AI in 2024")
            .expectedOutput("A comprehensive 3 paragraphs long report on the latest AI advancements in 2024")
            .agent(researcher)
            .build();

        Task writeTask = Task.builder()
            .description("Using the research provided, develop an engaging blog post about the latest AI advancements")
            .expectedOutput("A 4 paragraph blog post formatted as markdown about the latest AI advancements in 2024")
            .agent(writer)
            .dependsOn(researchTask)
            .build();

        // Create swarm
        Swarm swarm = Swarm.builder()
            .agent(researcher)
            .agent(writer)
            .task(researchTask)
            .task(writeTask)
            .process(ProcessType.SEQUENTIAL)
            .verbose(true)
            .eventPublisher(eventPublisher)
            .build();

        // Execute swarm
        System.out.println("=== Starting SwarmAI Execution ===");
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("topic", "AI advancements in 2024");

        SwarmOutput result = swarm.kickoff(inputs);

        System.out.println("=== Swarm Execution Complete ===");
        System.out.println("Final Output:");
        System.out.println(result.getFinalOutput());
        System.out.println("\n=== Execution Statistics ===");
        System.out.println("Success Rate: " + (result.getSuccessRate() * 100) + "%");
        System.out.println("Execution Time: " + result.getExecutionTime());
        System.out.println("Task Count: " + result.getTaskOutputs().size());
    }
}