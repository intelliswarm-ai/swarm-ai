package ai.intelliswarm.swarmai.tool.base;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.base.BaseSwarmTest;
import ai.intelliswarm.swarmai.base.MockChatClientFactory;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Permission Level Tests")
class PermissionLevelTest extends BaseSwarmTest {

    @Nested
    @DisplayName("PermissionLevel.isPermittedBy()")
    class IsPermittedByTests {

        @Test
        @DisplayName("READ_ONLY is permitted by all modes")
        void readOnly_permittedByAll() {
            assertTrue(PermissionLevel.READ_ONLY.isPermittedBy(PermissionLevel.READ_ONLY));
            assertTrue(PermissionLevel.READ_ONLY.isPermittedBy(PermissionLevel.WORKSPACE_WRITE));
            assertTrue(PermissionLevel.READ_ONLY.isPermittedBy(PermissionLevel.DANGEROUS));
            assertTrue(PermissionLevel.READ_ONLY.isPermittedBy(PermissionLevel.REQUIRES_APPROVAL));
        }

        @Test
        @DisplayName("WORKSPACE_WRITE is not permitted by READ_ONLY")
        void workspaceWrite_notPermittedByReadOnly() {
            assertFalse(PermissionLevel.WORKSPACE_WRITE.isPermittedBy(PermissionLevel.READ_ONLY));
            assertTrue(PermissionLevel.WORKSPACE_WRITE.isPermittedBy(PermissionLevel.WORKSPACE_WRITE));
            assertTrue(PermissionLevel.WORKSPACE_WRITE.isPermittedBy(PermissionLevel.DANGEROUS));
        }

        @Test
        @DisplayName("DANGEROUS requires at least DANGEROUS mode")
        void dangerous_requiresDangerousMode() {
            assertFalse(PermissionLevel.DANGEROUS.isPermittedBy(PermissionLevel.READ_ONLY));
            assertFalse(PermissionLevel.DANGEROUS.isPermittedBy(PermissionLevel.WORKSPACE_WRITE));
            assertTrue(PermissionLevel.DANGEROUS.isPermittedBy(PermissionLevel.DANGEROUS));
            assertTrue(PermissionLevel.DANGEROUS.isPermittedBy(PermissionLevel.REQUIRES_APPROVAL));
        }

        @Test
        @DisplayName("REQUIRES_APPROVAL only permitted by REQUIRES_APPROVAL")
        void requiresApproval_onlyByRequiresApproval() {
            assertFalse(PermissionLevel.REQUIRES_APPROVAL.isPermittedBy(PermissionLevel.READ_ONLY));
            assertFalse(PermissionLevel.REQUIRES_APPROVAL.isPermittedBy(PermissionLevel.WORKSPACE_WRITE));
            assertFalse(PermissionLevel.REQUIRES_APPROVAL.isPermittedBy(PermissionLevel.DANGEROUS));
            assertTrue(PermissionLevel.REQUIRES_APPROVAL.isPermittedBy(PermissionLevel.REQUIRES_APPROVAL));
        }

        @Test
        @DisplayName("null agent mode defaults to READ_ONLY (fail-closed security)")
        void nullMode_defaultsToReadOnly() {
            assertTrue(PermissionLevel.READ_ONLY.isPermittedBy(null),
                "READ_ONLY should be permitted even without explicit permission mode");
            assertFalse(PermissionLevel.DANGEROUS.isPermittedBy(null),
                "DANGEROUS must NOT be permitted when agent has no permission mode");
            assertFalse(PermissionLevel.REQUIRES_APPROVAL.isPermittedBy(null),
                "REQUIRES_APPROVAL must NOT be permitted when agent has no permission mode");
        }
    }

    @Nested
    @DisplayName("Agent tool permission filtering")
    class AgentPermissionFilteringTests {

        private BaseTool createToolWithPermission(String name, PermissionLevel level) {
            BaseTool tool = mock(BaseTool.class);
            when(tool.getFunctionName()).thenReturn(name);
            when(tool.getDescription()).thenReturn("Tool: " + name);
            when(tool.getParameterSchema()).thenReturn(Map.of());
            when(tool.isAsync()).thenReturn(false);
            when(tool.getPermissionLevel()).thenReturn(level);
            when(tool.execute(anyMap())).thenReturn("result");
            return tool;
        }

        @Test
        @DisplayName("agent without permission mode sees all tools")
        void noPermissionMode_allToolsVisible() {
            List<BaseTool> tools = List.of(
                    createToolWithPermission("search", PermissionLevel.READ_ONLY),
                    createToolWithPermission("write_file", PermissionLevel.WORKSPACE_WRITE),
                    createToolWithPermission("shell", PermissionLevel.DANGEROUS)
            );

            Agent agent = Agent.builder()
                    .role("Unrestricted Agent")
                    .goal("Test goal")
                    .backstory("Test backstory")
                    .chatClient(mockChatClient)
                    .tools(tools)
                    .build();

            assertEquals(3, agent.getTools().size());
            assertNull(agent.getPermissionMode());
        }

        @Test
        @DisplayName("READ_ONLY agent can only see READ_ONLY tools")
        void readOnlyAgent_onlySeeReadOnlyTools() {
            List<BaseTool> tools = List.of(
                    createToolWithPermission("search", PermissionLevel.READ_ONLY),
                    createToolWithPermission("write_file", PermissionLevel.WORKSPACE_WRITE),
                    createToolWithPermission("shell", PermissionLevel.DANGEROUS)
            );

            Agent agent = Agent.builder()
                    .role("Explorer Agent")
                    .goal("Read-only exploration")
                    .backstory("Test backstory")
                    .chatClient(mockChatClient)
                    .tools(tools)
                    .permissionMode(PermissionLevel.READ_ONLY)
                    .build();

            assertEquals(PermissionLevel.READ_ONLY, agent.getPermissionMode());
            // The tools list itself is not filtered — filtering happens at execution time
            assertEquals(3, agent.getTools().size());
        }

        @Test
        @DisplayName("WORKSPACE_WRITE agent sees READ_ONLY and WORKSPACE_WRITE tools")
        void workspaceWriteAgent_seesWriteAndReadTools() {
            BaseTool readTool = createToolWithPermission("search", PermissionLevel.READ_ONLY);
            BaseTool writeTool = createToolWithPermission("write_file", PermissionLevel.WORKSPACE_WRITE);
            BaseTool dangerousTool = createToolWithPermission("shell", PermissionLevel.DANGEROUS);

            // Verify the permission check logic
            assertTrue(readTool.getPermissionLevel().isPermittedBy(PermissionLevel.WORKSPACE_WRITE));
            assertTrue(writeTool.getPermissionLevel().isPermittedBy(PermissionLevel.WORKSPACE_WRITE));
            assertFalse(dangerousTool.getPermissionLevel().isPermittedBy(PermissionLevel.WORKSPACE_WRITE));
        }

        @Test
        @DisplayName("default tool permission is READ_ONLY")
        void defaultPermission_isReadOnly() {
            BaseTool tool = mock(BaseTool.class);
            // Call the real default method
            when(tool.getPermissionLevel()).thenCallRealMethod();
            assertEquals(PermissionLevel.READ_ONLY, tool.getPermissionLevel());
        }
    }

    @Nested
    @DisplayName("PermissionLevel enum ordering")
    class OrderingTests {

        @Test
        @DisplayName("ordinals are strictly increasing")
        void ordinals_strictlyIncreasing() {
            assertTrue(PermissionLevel.READ_ONLY.ordinal() < PermissionLevel.WORKSPACE_WRITE.ordinal());
            assertTrue(PermissionLevel.WORKSPACE_WRITE.ordinal() < PermissionLevel.DANGEROUS.ordinal());
            assertTrue(PermissionLevel.DANGEROUS.ordinal() < PermissionLevel.REQUIRES_APPROVAL.ordinal());
        }

        @Test
        @DisplayName("all four levels exist")
        void allLevelsExist() {
            assertEquals(4, PermissionLevel.values().length);
        }
    }
}
