package ai.intelliswarm.swarmai.state;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Checkpoint System Tests")
class CheckpointTest {

    @Nested
    @DisplayName("Checkpoint record")
    class CheckpointRecordTests {

        @Test
        @DisplayName("creates with all fields")
        void createsWithAllFields() {
            AgentState state = AgentState.of(Map.of("key", "value"));
            Checkpoint cp = Checkpoint.create("wf-1", "task-1", "task-2", state);

            assertNotNull(cp.id());
            assertEquals("wf-1", cp.workflowId());
            assertEquals("task-1", cp.completedTaskId());
            assertEquals("task-2", cp.nextTaskId());
            assertEquals(state, cp.state());
            assertNotNull(cp.timestamp());
            assertTrue(cp.metadata().isEmpty());
        }

        @Test
        @DisplayName("creates with metadata")
        void createsWithMetadata() {
            AgentState state = AgentState.empty();
            Checkpoint cp = Checkpoint.create("wf-1", "task-1", null, state,
                    Map.of("iteration", 3, "status", "COMPLETED"));

            assertEquals(3, cp.metadata().get("iteration"));
            assertEquals("COMPLETED", cp.metadata().get("status"));
        }

        @Test
        @DisplayName("rejects null workflow ID")
        void rejectsNullWorkflowId() {
            assertThrows(NullPointerException.class, () ->
                    new Checkpoint("id", null, null, null, AgentState.empty(), null, null));
        }

        @Test
        @DisplayName("rejects null state")
        void rejectsNullState() {
            assertThrows(NullPointerException.class, () ->
                    new Checkpoint("id", "wf-1", null, null, null, null, null));
        }

        @Test
        @DisplayName("defaults timestamp and metadata when null")
        void defaultsNulls() {
            Checkpoint cp = new Checkpoint("id", "wf-1", null, null,
                    AgentState.empty(), null, null);
            assertNotNull(cp.timestamp());
            assertNotNull(cp.metadata());
        }
    }

    @Nested
    @DisplayName("InMemoryCheckpointSaver")
    class InMemoryCheckpointSaverTests {

        private InMemoryCheckpointSaver saver;

        @BeforeEach
        void setUp() {
            saver = new InMemoryCheckpointSaver();
        }

        @Test
        @DisplayName("saves and loads latest checkpoint")
        void savesAndLoadsLatest() {
            AgentState state = AgentState.of(Map.of("step", 1));
            Checkpoint cp = Checkpoint.create("wf-1", "task-1", "task-2", state);
            saver.save(cp);

            Optional<Checkpoint> loaded = saver.loadLatest("wf-1");
            assertTrue(loaded.isPresent());
            assertEquals(cp.id(), loaded.get().id());
            assertEquals("task-1", loaded.get().completedTaskId());
        }

        @Test
        @DisplayName("returns latest by timestamp")
        void returnsLatestByTimestamp() throws InterruptedException {
            saver.save(Checkpoint.create("wf-1", "task-1", "task-2", AgentState.empty()));
            Thread.sleep(10); // ensure different timestamps
            Checkpoint latest = Checkpoint.create("wf-1", "task-2", "task-3", AgentState.empty());
            saver.save(latest);

            Optional<Checkpoint> loaded = saver.loadLatest("wf-1");
            assertTrue(loaded.isPresent());
            assertEquals("task-2", loaded.get().completedTaskId());
        }

        @Test
        @DisplayName("loads all checkpoints ordered by timestamp")
        void loadsAllOrdered() throws InterruptedException {
            saver.save(Checkpoint.create("wf-1", "task-1", "task-2", AgentState.empty()));
            Thread.sleep(10);
            saver.save(Checkpoint.create("wf-1", "task-2", "task-3", AgentState.empty()));

            List<Checkpoint> all = saver.loadAll("wf-1");
            assertEquals(2, all.size());
            assertEquals("task-1", all.get(0).completedTaskId());
            assertEquals("task-2", all.get(1).completedTaskId());
        }

        @Test
        @DisplayName("returns empty for unknown workflow")
        void returnsEmptyForUnknown() {
            assertTrue(saver.loadLatest("unknown").isEmpty());
            assertEquals(List.of(), saver.loadAll("unknown"));
        }

        @Test
        @DisplayName("deletes all checkpoints for workflow")
        void deletesWorkflow() {
            saver.save(Checkpoint.create("wf-1", "task-1", "task-2", AgentState.empty()));
            saver.save(Checkpoint.create("wf-1", "task-2", null, AgentState.empty()));
            saver.save(Checkpoint.create("wf-2", "task-1", null, AgentState.empty()));

            saver.delete("wf-1");

            assertTrue(saver.loadLatest("wf-1").isEmpty());
            assertTrue(saver.loadLatest("wf-2").isPresent());
        }

        @Test
        @DisplayName("replaces checkpoint with same ID")
        void replacesById() {
            Checkpoint cp = Checkpoint.create("wf-1", "task-1", "task-2", AgentState.empty());
            saver.save(cp);

            // Save with same ID but different state
            AgentState updatedState = AgentState.of(Map.of("updated", true));
            Checkpoint updated = new Checkpoint(
                    cp.id(), "wf-1", "task-1", "task-2",
                    updatedState, Instant.now(), Map.of());
            saver.save(updated);

            assertEquals(1, saver.loadAll("wf-1").size());
            Optional<Boolean> val = saver.loadLatest("wf-1").get().state().value("updated");
            assertTrue(val.isPresent());
            assertTrue(val.get());
        }

        @Test
        @DisplayName("size counts all checkpoints")
        void sizeCountsAll() {
            saver.save(Checkpoint.create("wf-1", "t1", "t2", AgentState.empty()));
            saver.save(Checkpoint.create("wf-1", "t2", null, AgentState.empty()));
            saver.save(Checkpoint.create("wf-2", "t1", null, AgentState.empty()));

            assertEquals(3, saver.size());
        }

        @Test
        @DisplayName("clear removes everything")
        void clearRemovesAll() {
            saver.save(Checkpoint.create("wf-1", "t1", "t2", AgentState.empty()));
            saver.save(Checkpoint.create("wf-2", "t1", null, AgentState.empty()));

            saver.clear();
            assertEquals(0, saver.size());
        }

        @Test
        @DisplayName("isolates workflows from each other")
        void isolatesWorkflows() {
            saver.save(Checkpoint.create("wf-1", "task-A", null, AgentState.empty()));
            saver.save(Checkpoint.create("wf-2", "task-B", null, AgentState.empty()));

            assertEquals("task-A", saver.loadLatest("wf-1").get().completedTaskId());
            assertEquals("task-B", saver.loadLatest("wf-2").get().completedTaskId());
        }
    }

    @Nested
    @DisplayName("CompiledSwarm checkpoint integration")
    class CompiledSwarmCheckpointTests {

        @Test
        @DisplayName("resume throws without checkpoint saver")
        void resumeThrowsWithoutSaver() {
            CompiledSwarm swarm = SwarmGraph.create()
                    .addAgent(createAgent())
                    .addTask(createTask())
                    .compileOrThrow();

            assertThrows(IllegalStateException.class, () -> swarm.resume("wf-1"));
        }

        @Test
        @DisplayName("resume throws when no checkpoint exists")
        void resumeThrowsWhenNoCheckpoint() {
            CompiledSwarm swarm = SwarmGraph.create()
                    .addAgent(createAgent())
                    .addTask(createTask())
                    .checkpointSaver(new InMemoryCheckpointSaver())
                    .compileOrThrow();

            assertThrows(IllegalStateException.class, () -> swarm.resume("non-existent"));
        }

        @Test
        @DisplayName("getLatestCheckpoint returns empty without saver")
        void getCheckpointEmptyWithoutSaver() {
            CompiledSwarm swarm = SwarmGraph.create()
                    .addAgent(createAgent())
                    .addTask(createTask())
                    .compileOrThrow();

            assertTrue(swarm.getLatestCheckpoint("wf-1").isEmpty());
            assertEquals(List.of(), swarm.getCheckpoints("wf-1"));
        }

        @Test
        @DisplayName("compiled swarm exposes checkpoint saver")
        void exposesCheckpointSaver() {
            InMemoryCheckpointSaver saver = new InMemoryCheckpointSaver();
            CompiledSwarm swarm = SwarmGraph.create()
                    .addAgent(createAgent())
                    .addTask(createTask())
                    .checkpointSaver(saver)
                    .compileOrThrow();

            assertSame(saver, swarm.getCheckpointSaver());
        }

        @Test
        @DisplayName("interrupt points are preserved after compilation")
        void interruptPointsPreserved() {
            CompiledSwarm swarm = SwarmGraph.create()
                    .addAgent(createAgent())
                    .addTask(createTask())
                    .interruptBefore("task-1")
                    .interruptAfter("task-2")
                    .compileOrThrow();

            assertEquals(List.of("task-1"), swarm.getInterruptBeforeTaskIds());
            assertEquals(List.of("task-2"), swarm.getInterruptAfterTaskIds());
        }

        private ai.intelliswarm.swarmai.agent.Agent createAgent() {
            return ai.intelliswarm.swarmai.agent.Agent.builder()
                    .role("worker")
                    .goal("test")
                    .backstory("test")
                    .chatClient(org.mockito.Mockito.mock(
                            org.springframework.ai.chat.client.ChatClient.class))
                    .build();
        }

        private ai.intelliswarm.swarmai.task.Task createTask() {
            return ai.intelliswarm.swarmai.task.Task.builder()
                    .description("test task")
                    .agent(createAgent())
                    .build();
        }
    }
}
