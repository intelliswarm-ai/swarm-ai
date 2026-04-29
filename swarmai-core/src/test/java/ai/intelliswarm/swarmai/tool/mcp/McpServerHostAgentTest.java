package ai.intelliswarm.swarmai.tool.mcp;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Verifies that {@link McpServerHost#toAgentSpec(Agent)} produces a valid MCP
 * tool spec for a SwarmAI {@link Agent} (i.e. an Agent's persona is published as
 * an MCP tool the same way a {@link BaseTool} is).
 *
 * Doesn't drive the LLM — that requires a live ChatClient and is integration
 * territory. Covers the parts the framework owns: name slugification, schema,
 * description rendering, and filter rules.
 */
class McpServerHostAgentTest {

    private Agent buildAgent(String role, String goal) {
        // Mocking just the minimum the builder needs. Agent's executeTask requires
        // a ChatClient.Builder, but for spec-building we never invoke it.
        ChatModel chatModel = mock(ChatModel.class);
        ChatClient chatClient = ChatClient.builder(chatModel).build();
        return Agent.builder()
                .role(role)
                .goal(goal)
                .backstory("test backstory")
                .chatClient(chatClient)
                .build();
    }

    private McpServerHost host(List<Agent> agents, McpServerProperties props) {
        return new McpServerHost(
                props != null ? props : new McpServerProperties(),
                List.of(),
                agents);
    }

    // ---------------------------------------------------------------- slugifier

    @Test
    void agentMcpName_slugifiesRole() {
        assertThat(McpServerHost.agentMcpName(buildAgent("Senior Data Analyst", "g")))
                .isEqualTo("senior_data_analyst");
        assertThat(McpServerHost.agentMcpName(buildAgent("  Code   Reviewer  ", "g")))
                .isEqualTo("code_reviewer");
        assertThat(McpServerHost.agentMcpName(buildAgent("Q&A / FAQ Bot!", "g")))
                .isEqualTo("q_a_faq_bot");
    }

    // ---------------------------------------------------------------- spec build

    @Test
    void toAgentSpec_carriesNameDescriptionAndInputSchema() {
        Agent a = buildAgent("Senior Data Analyst",
                "Analyze CSV files and produce statistical summaries");
        McpServerHost h = host(List.of(a), null);

        McpServerFeatures.SyncToolSpecification spec = h.toAgentSpec(a);
        McpSchema.Tool tool = spec.tool();

        assertThat(tool.name()).isEqualTo("senior_data_analyst");
        assertThat(tool.description())
                .startsWith("Senior Data Analyst:")
                .contains("Analyze CSV files");
        assertThat(tool.inputSchema()).isNotNull();
    }

    @Test
    void toAgentSpec_inputSchema_requiresTaskField() {
        Agent a = buildAgent("Reviewer", "Review code");
        McpServerHost h = host(List.of(a), null);
        McpServerFeatures.SyncToolSpecification spec = h.toAgentSpec(a);
        // Round-trip via toString of the JsonSchema to confirm it advertises 'task' as required.
        String schemaText = spec.tool().inputSchema().toString();
        assertThat(schemaText).contains("task").contains("required");
    }

    // ---------------------------------------------------------------- handler error path

    @Test
    void handler_missingTaskArg_returnsErrorResultWithGuidance() {
        Agent a = buildAgent("Reviewer", "Review code");
        McpServerHost h = host(List.of(a), null);
        McpServerFeatures.SyncToolSpecification spec = h.toAgentSpec(a);

        // Empty args — should not invoke the LLM at all
        McpSchema.CallToolResult result = spec.call().apply(null, Map.of());

        assertThat(result.isError()).isTrue();
        assertThat(((McpSchema.TextContent) result.content().get(0)).text())
                .contains("Missing required argument 'task'");
    }

    // ---------------------------------------------------------------- filters

    @Test
    void filterAgents_includeWhitelist_winsOverExclude() throws Exception {
        Agent a1 = buildAgent("Data Analyst", "g1");
        Agent a2 = buildAgent("Code Reviewer", "g2");
        McpServerProperties p = new McpServerProperties();
        p.setIncludeAgents(List.of("data_analyst"));
        p.setExcludeAgents(List.of("data_analyst"));   // contradictory; whitelist wins
        McpServerHost h = host(List.of(a1, a2), p);

        java.lang.reflect.Method m = McpServerHost.class.getDeclaredMethod("filterAgents", List.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Agent> kept = (List<Agent>) m.invoke(h, List.of(a1, a2));
        assertThat(kept).hasSize(1);
        assertThat(McpServerHost.agentMcpName(kept.get(0))).isEqualTo("data_analyst");
    }

    @Test
    void filterAgents_excludeBlacklist_dropsMatching() throws Exception {
        Agent a1 = buildAgent("Data Analyst", "g1");
        Agent a2 = buildAgent("Code Reviewer", "g2");
        McpServerProperties p = new McpServerProperties();
        p.setExcludeAgents(List.of("code_reviewer"));
        McpServerHost h = host(List.of(a1, a2), p);

        java.lang.reflect.Method m = McpServerHost.class.getDeclaredMethod("filterAgents", List.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Agent> kept = (List<Agent>) m.invoke(h, List.of(a1, a2));
        assertThat(kept).hasSize(1);
        assertThat(McpServerHost.agentMcpName(kept.get(0))).isEqualTo("data_analyst");
    }
}
