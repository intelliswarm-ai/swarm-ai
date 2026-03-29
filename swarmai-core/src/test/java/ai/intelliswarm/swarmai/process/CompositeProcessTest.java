package ai.intelliswarm.swarmai.process;

import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("CompositeProcess Tests")
class CompositeProcessTest {

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("creates from varargs")
        void createsFromVarargs() {
            Process p1 = mock(Process.class);
            Process p2 = mock(Process.class);
            when(p1.getType()).thenReturn(ProcessType.SEQUENTIAL);
            when(p2.getType()).thenReturn(ProcessType.HIERARCHICAL);

            CompositeProcess composite = CompositeProcess.of(p1, p2);

            assertEquals(2, composite.stageCount());
            assertSame(p1, composite.getStage(0));
            assertSame(p2, composite.getStage(1));
        }

        @Test
        @DisplayName("rejects empty stages")
        void rejectsEmpty() {
            assertThrows(IllegalArgumentException.class, () ->
                    CompositeProcess.of(List.of()));
        }

        @Test
        @DisplayName("rejects null stages")
        void rejectsNull() {
            assertThrows(IllegalArgumentException.class, () ->
                    CompositeProcess.of((List<Process>) null));
        }
    }

    @Nested
    @DisplayName("Execution")
    class ExecutionTests {

        @Test
        @DisplayName("executes stages in order")
        void executesInOrder() {
            Process stage1 = mock(Process.class);
            Process stage2 = mock(Process.class);

            SwarmOutput output1 = SwarmOutput.builder()
                    .swarmId("test")
                    .rawOutput("stage1 result")
                    .finalOutput("stage1 final")
                    .successful(true)
                    .build();

            SwarmOutput output2 = SwarmOutput.builder()
                    .swarmId("test")
                    .rawOutput("stage2 result")
                    .finalOutput("stage2 final")
                    .successful(true)
                    .build();

            when(stage1.execute(any(), any(Map.class), anyString())).thenReturn(output1);
            when(stage2.execute(any(), any(Map.class), anyString())).thenReturn(output2);
            when(stage1.getType()).thenReturn(ProcessType.SEQUENTIAL);
            when(stage2.getType()).thenReturn(ProcessType.PARALLEL);

            CompositeProcess composite = CompositeProcess.of(stage1, stage2);
            Task task = Task.builder().description("test").build();

            SwarmOutput result = composite.execute(List.of(task), Map.of(), "swarm-1");

            assertTrue(result.isSuccessful());
            assertEquals("stage2 final", result.getFinalOutput());

            // Both stages should have been called
            verify(stage1).execute(any(), any(Map.class), eq("swarm-1"));
            verify(stage2).execute(any(), any(Map.class), eq("swarm-1"));
        }

        @Test
        @DisplayName("single stage works")
        void singleStage() {
            Process stage = mock(Process.class);
            SwarmOutput output = SwarmOutput.builder()
                    .swarmId("test")
                    .rawOutput("done")
                    .successful(true)
                    .build();

            when(stage.execute(any(), any(Map.class), anyString())).thenReturn(output);
            when(stage.getType()).thenReturn(ProcessType.SEQUENTIAL);

            CompositeProcess composite = CompositeProcess.of(stage);
            Task task = Task.builder().description("test").build();

            SwarmOutput result = composite.execute(List.of(task), Map.of(), "swarm-1");

            assertTrue(result.isSuccessful());
            assertEquals(1, composite.stageCount());
        }

        @Test
        @DisplayName("output includes metadata about stages")
        void outputIncludesMetadata() {
            Process stage1 = mock(Process.class);
            Process stage2 = mock(Process.class);

            SwarmOutput output = SwarmOutput.builder()
                    .swarmId("test").rawOutput("done").successful(true).build();

            when(stage1.execute(any(), any(Map.class), anyString())).thenReturn(output);
            when(stage2.execute(any(), any(Map.class), anyString())).thenReturn(output);
            when(stage1.getType()).thenReturn(ProcessType.SEQUENTIAL);
            when(stage2.getType()).thenReturn(ProcessType.HIERARCHICAL);

            CompositeProcess composite = CompositeProcess.of(stage1, stage2);
            Task task = Task.builder().description("test").build();

            SwarmOutput result = composite.execute(List.of(task), Map.of(), "swarm-1");

            assertEquals(2, result.getMetadata().get("stages"));
        }
    }

    @Nested
    @DisplayName("Properties")
    class PropertiesTests {

        @Test
        @DisplayName("type is SEQUENTIAL")
        void typeIsSequential() {
            Process stage = mock(Process.class);
            when(stage.getType()).thenReturn(ProcessType.SEQUENTIAL);
            CompositeProcess composite = CompositeProcess.of(stage);
            assertEquals(ProcessType.SEQUENTIAL, composite.getType());
        }

        @Test
        @DisplayName("is not async")
        void isNotAsync() {
            Process stage = mock(Process.class);
            when(stage.getType()).thenReturn(ProcessType.SEQUENTIAL);
            CompositeProcess composite = CompositeProcess.of(stage);
            assertFalse(composite.isAsync());
        }

        @Test
        @DisplayName("toString includes stage count")
        void toStringIncludesStages() {
            Process stage = mock(Process.class);
            when(stage.getType()).thenReturn(ProcessType.SEQUENTIAL);
            CompositeProcess composite = CompositeProcess.of(stage);
            assertTrue(composite.toString().contains("stages=1"));
        }

        @Test
        @DisplayName("delegates validation to all stages")
        void delegatesValidation() {
            Process stage1 = mock(Process.class);
            Process stage2 = mock(Process.class);
            when(stage1.getType()).thenReturn(ProcessType.SEQUENTIAL);
            when(stage2.getType()).thenReturn(ProcessType.PARALLEL);

            CompositeProcess composite = CompositeProcess.of(stage1, stage2);
            List<Task> tasks = List.of(Task.builder().description("test").build());

            composite.validateTasks(tasks);

            verify(stage1).validateTasks(tasks);
            verify(stage2).validateTasks(tasks);
        }
    }
}
