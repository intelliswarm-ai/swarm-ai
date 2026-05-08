package ai.intelliswarm.swarmai.state.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("TaskItem + TaskList + TaskListRenderer Tests")
class TaskListTest {

    @Nested
    @DisplayName("TaskItem record")
    class TaskItemRecord {

        @Test
        @DisplayName("of(description) creates a top-level task with no active form")
        void factoryOf() {
            TaskItem t = TaskItem.of("do the thing");
            assertEquals("do the thing", t.description());
            assertNull(t.activeForm());
            assertNull(t.parentTaskId());
            assertTrue(t.metadata().isEmpty());
            assertFalse(t.hasParent());
        }

        @Test
        @DisplayName("blank description is rejected")
        void blankDescriptionRejected() {
            assertThrows(IllegalArgumentException.class, () -> TaskItem.of(""));
            assertThrows(IllegalArgumentException.class, () -> TaskItem.of("   "));
            assertThrows(NullPointerException.class, () -> TaskItem.of(null));
        }

        @Test
        @DisplayName("effectiveActiveForm: explicit if set, falls back to description")
        void effectiveActiveForm() {
            assertEquals("running", TaskItem.of("run", "running").effectiveActiveForm());
            assertEquals("run", TaskItem.of("run").effectiveActiveForm());
            assertEquals("run", TaskItem.of("run", "").effectiveActiveForm());
        }

        @Test
        @DisplayName("hasParent reflects parentTaskId presence")
        void hasParent() {
            assertFalse(TaskItem.of("x").hasParent());
            assertTrue(new TaskItem("x", null, "parent-1", java.util.Map.of()).hasParent());
            assertFalse(new TaskItem("x", null, "", java.util.Map.of()).hasParent());
        }

        @Test
        @DisplayName("metadata is defensively copied (immutable)")
        void metadataCopied() {
            java.util.Map<String, Object> mut = new java.util.HashMap<>();
            mut.put("k", "v");
            TaskItem t = new TaskItem("x", null, null, mut);
            mut.put("hacked", "yes");
            assertEquals(1, t.metadata().size());
        }
    }

    @Nested
    @DisplayName("TaskList lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("createTask adds a PROPOSED entry and returns its id")
        void createTaskAddsProposedEntry() {
            InMemoryPlanChannelAccessor ch = new InMemoryPlanChannelAccessor();
            TaskList list = new TaskList(ch);
            String id = list.createTask("write tests");

            assertNotNull(id);
            PlanEntry e = ch.get().byId(id).orElseThrow();
            assertEquals(PlanEntry.Status.PROPOSED, e.status());
            assertEquals("agent", e.proposedBy());
            assertTrue(e.payload() instanceof TaskItem);
            assertEquals("write tests", ((TaskItem) e.payload()).description());
        }

        @Test
        @DisplayName("createTask with explicit active form populates both fields")
        void createTaskWithActiveForm() {
            InMemoryPlanChannelAccessor ch = new InMemoryPlanChannelAccessor();
            TaskList list = new TaskList(ch);
            String id = list.createTask("run tests", "running tests");

            TaskItem t = (TaskItem) ch.get().byId(id).orElseThrow().payload();
            assertEquals("run tests", t.description());
            assertEquals("running tests", t.activeForm());
        }

        @Test
        @DisplayName("createSubTask sets parentTaskId on the TaskItem")
        void createSubTask() {
            InMemoryPlanChannelAccessor ch = new InMemoryPlanChannelAccessor();
            TaskList list = new TaskList(ch);
            String parent = list.createTask("plan");
            String child = list.createSubTask(parent, "draft outline", "drafting outline");

            TaskItem t = (TaskItem) ch.get().byId(child).orElseThrow().payload();
            assertEquals(parent, t.parentTaskId());
            assertTrue(t.hasParent());
        }

        @Test
        @DisplayName("startTask: PROPOSED → APPROVED with proposedBy attribution")
        void startTask() {
            InMemoryPlanChannelAccessor ch = new InMemoryPlanChannelAccessor();
            TaskList list = new TaskList(ch);
            String id = list.createTask("x");

            list.startTask(id);

            PlanEntry e = ch.get().byId(id).orElseThrow();
            assertEquals(PlanEntry.Status.APPROVED, e.status());
            assertEquals("started", e.reason());
            assertEquals("agent", e.decidedBy());
        }

        @Test
        @DisplayName("completeTask: APPROVED → EXECUTED")
        void completeTask() {
            InMemoryPlanChannelAccessor ch = new InMemoryPlanChannelAccessor();
            TaskList list = new TaskList(ch);
            String id = list.createTask("x");
            list.startTask(id);
            list.completeTask(id);

            assertEquals(PlanEntry.Status.EXECUTED,
                    ch.get().byId(id).orElseThrow().status());
        }

        @Test
        @DisplayName("completeTask with result note records the note as reason")
        void completeTaskWithNote() {
            InMemoryPlanChannelAccessor ch = new InMemoryPlanChannelAccessor();
            TaskList list = new TaskList(ch);
            String id = list.createTask("x");
            list.startTask(id);
            list.completeTask(id, "wrote 42 tests, all passing");

            PlanEntry e = ch.get().byId(id).orElseThrow();
            assertEquals("wrote 42 tests, all passing", e.reason());
        }

        @Test
        @DisplayName("cancelTask: PROPOSED → REJECTED (also valid from APPROVED)")
        void cancelTask() {
            InMemoryPlanChannelAccessor ch = new InMemoryPlanChannelAccessor();
            TaskList list = new TaskList(ch);
            String pendingId = list.createTask("a");
            String startedId = list.createTask("b");
            list.startTask(startedId);

            list.cancelTask(pendingId, "no longer needed");
            list.cancelTask(startedId, "blocked");

            assertEquals(PlanEntry.Status.REJECTED, ch.get().byId(pendingId).orElseThrow().status());
            assertEquals(PlanEntry.Status.REJECTED, ch.get().byId(startedId).orElseThrow().status());
        }

        @Test
        @DisplayName("operations on terminal task throw IllegalStateException")
        void terminalTaskRefusesTransitions() {
            InMemoryPlanChannelAccessor ch = new InMemoryPlanChannelAccessor();
            TaskList list = new TaskList(ch);
            String id = list.createTask("x");
            list.startTask(id);
            list.completeTask(id);

            assertThrows(IllegalStateException.class, () -> list.startTask(id));
            assertThrows(IllegalStateException.class, () -> list.cancelTask(id, "no"));
        }

        @Test
        @DisplayName("operations on unknown id throw IllegalArgumentException")
        void unknownIdRejected() {
            TaskList list = new TaskList(new InMemoryPlanChannelAccessor());
            assertThrows(IllegalArgumentException.class, () -> list.startTask("nope"));
            assertThrows(IllegalArgumentException.class, () -> list.completeTask("nope"));
        }

        @Test
        @DisplayName("operations on a non-TaskItem entry throw IllegalArgumentException")
        void nonTaskItemEntryRejected() {
            InMemoryPlanChannelAccessor ch = new InMemoryPlanChannelAccessor();
            // Append a non-TaskItem entry directly
            ch.append(PlanEntry.builder()
                    .id("not-a-task")
                    .proposedBy("tool:x")
                    .payload("just a string")
                    .risk(Risk.LOW)
                    .build());

            TaskList list = new TaskList(ch);
            assertThrows(IllegalArgumentException.class, () -> list.startTask("not-a-task"));
        }

        @Test
        @DisplayName("supersedeTask marks old SUPERSEDED + appends fresh PROPOSED replacement")
        void supersedeTask() {
            InMemoryPlanChannelAccessor ch = new InMemoryPlanChannelAccessor();
            TaskList list = new TaskList(ch);
            String oldId = list.createTask("vague task");
            String newId = list.supersedeTask(oldId, TaskItem.of("revised: specific task"));

            assertEquals(PlanEntry.Status.SUPERSEDED, ch.get().byId(oldId).orElseThrow().status());
            PlanEntry replacement = ch.get().byId(newId).orElseThrow();
            assertEquals(PlanEntry.Status.PROPOSED, replacement.status());
            assertEquals(oldId, replacement.supersedes());
            assertEquals("revised: specific task",
                    ((TaskItem) replacement.payload()).description());
        }

        @Test
        @DisplayName("custom proposedBy attribution flows through every transition")
        void customProposedBy() {
            InMemoryPlanChannelAccessor ch = new InMemoryPlanChannelAccessor();
            TaskList list = new TaskList(ch, "operator-cli");
            String id = list.createTask("x");
            list.startTask(id);

            PlanEntry e = ch.get().byId(id).orElseThrow();
            assertEquals("operator-cli", e.proposedBy());
            assertEquals("operator-cli", e.decidedBy());
        }
    }

    @Nested
    @DisplayName("TaskList queries")
    class Queries {

        @Test
        @DisplayName("status-bucket queries return correct subsets")
        void statusBuckets() {
            InMemoryPlanChannelAccessor ch = new InMemoryPlanChannelAccessor();
            TaskList list = new TaskList(ch);

            String pending = list.createTask("p");
            String inProg = list.createTask("i");
            list.startTask(inProg);
            String done = list.createTask("d");
            list.startTask(done);
            list.completeTask(done);
            String cancelled = list.createTask("c");
            list.cancelTask(cancelled, "no");

            assertEquals(1, list.pendingTasks().size());
            assertEquals(pending, list.pendingTasks().get(0).id());
            assertEquals(1, list.inProgressTasks().size());
            assertEquals(inProg, list.inProgressTasks().get(0).id());
            assertEquals(1, list.completedTasks().size());
            assertEquals(done, list.completedTasks().get(0).id());
            assertEquals(1, list.cancelledTasks().size());
            assertEquals(cancelled, list.cancelledTasks().get(0).id());
        }

        @Test
        @DisplayName("allTasks ignores non-TaskItem entries on the channel")
        void filtersNonTaskItems() {
            InMemoryPlanChannelAccessor ch = new InMemoryPlanChannelAccessor();
            ch.append(PlanEntry.builder()
                    .id("mut-1").proposedBy("tool:os_filesystem")
                    .payload("not a TaskItem")
                    .risk(Risk.MEDIUM).build());

            TaskList list = new TaskList(ch);
            list.createTask("real task");

            assertEquals(1, list.allTasks().size());
            assertTrue(list.allTasks().get(0).payload() instanceof TaskItem);
        }
    }

    @Nested
    @DisplayName("TaskListRenderer")
    class Rendering {

        @Test
        @DisplayName("empty plan renders as empty string (no leaked header)")
        void emptyPlanEmptyOutput() {
            String out = new TaskListRenderer().render(EvolvingPlan.empty());
            assertEquals("", out);
        }

        @Test
        @DisplayName("status icons render correctly for each lifecycle state")
        void statusIcons() {
            InMemoryPlanChannelAccessor ch = new InMemoryPlanChannelAccessor();
            TaskList list = new TaskList(ch);

            list.createTask("pending task");
            String inProg = list.createTask("inProg task", "doing inProg task");
            list.startTask(inProg);
            String done = list.createTask("done task");
            list.startTask(done);
            list.completeTask(done);
            String cancelled = list.createTask("cancelled task");
            list.cancelTask(cancelled, "scope-cut");

            String out = new TaskListRenderer().render(ch.get());
            assertTrue(out.contains("- [ ] pending task"), out);
            assertTrue(out.contains("- [~] doing inProg task"), out);
            assertTrue(out.contains("- [x] done task"), out);
            assertTrue(out.contains("- [-] cancelled task *(cancelled: scope-cut)*"), out);
        }

        @Test
        @DisplayName("sub-tasks are indented 2 spaces under their parent in render order")
        void subTasksIndented() {
            InMemoryPlanChannelAccessor ch = new InMemoryPlanChannelAccessor();
            TaskList list = new TaskList(ch);
            String parent = list.createTask("parent");
            list.createSubTask(parent, "child A", null);
            list.createSubTask(parent, "child B", null);

            String out = new TaskListRenderer().render(ch.get());

            int parentIdx = out.indexOf("- [ ] parent");
            int childAIdx = out.indexOf("  - [ ] child A");
            int childBIdx = out.indexOf("  - [ ] child B");
            assertTrue(parentIdx >= 0);
            assertTrue(childAIdx > parentIdx);
            assertTrue(childBIdx > childAIdx);
        }

        @Test
        @DisplayName("nested sub-tasks indent further (depth 2 is 4 spaces)")
        void nestedSubTasksDeepIndent() {
            InMemoryPlanChannelAccessor ch = new InMemoryPlanChannelAccessor();
            TaskList list = new TaskList(ch);
            String root = list.createTask("root");
            String mid = list.createSubTask(root, "mid", null);
            list.createSubTask(mid, "leaf", null);

            String out = new TaskListRenderer().render(ch.get());
            assertTrue(out.contains("    - [ ] leaf"), out);
        }

        @Test
        @DisplayName("SUPERSEDED tasks hidden by default; included when configured")
        void supersededHiddenByDefault() {
            InMemoryPlanChannelAccessor ch = new InMemoryPlanChannelAccessor();
            TaskList list = new TaskList(ch);
            String oldId = list.createTask("v1");
            list.supersedeTask(oldId, TaskItem.of("v2"));

            String defaultOut = new TaskListRenderer().render(ch.get());
            assertFalse(defaultOut.contains("v1"), defaultOut);
            assertTrue(defaultOut.contains("v2"), defaultOut);

            String inclusive = new TaskListRenderer("## Tasks", true).render(ch.get());
            assertTrue(inclusive.contains("v1"), inclusive);
            assertTrue(inclusive.contains("*(superseded)*"), inclusive);
        }

        @Test
        @DisplayName("non-TaskItem entries are filtered out of the rendered list")
        void filtersOutMutationEntries() {
            InMemoryPlanChannelAccessor ch = new InMemoryPlanChannelAccessor();
            ch.append(PlanEntry.builder()
                    .id("mut-1").proposedBy("tool:os_filesystem")
                    .payload("not a task")
                    .risk(Risk.MEDIUM).build());
            TaskList list = new TaskList(ch);
            list.createTask("real task");

            String out = new TaskListRenderer().render(ch.get());
            assertTrue(out.contains("real task"), out);
            assertFalse(out.contains("mut-1"), out);
            assertFalse(out.contains("not a task"), out);
        }

        @Test
        @DisplayName("completed task with result note shows the note inline")
        void completedTaskShowsNote() {
            InMemoryPlanChannelAccessor ch = new InMemoryPlanChannelAccessor();
            TaskList list = new TaskList(ch);
            String id = list.createTask("ship it");
            list.startTask(id);
            list.completeTask(id, "deployed in 12s");

            String out = new TaskListRenderer().render(ch.get());
            assertTrue(out.contains("- [x] ship it — deployed in 12s"), out);
        }

        @Test
        @DisplayName("default 'completed' reason is suppressed in the render")
        void defaultCompletedReasonHidden() {
            InMemoryPlanChannelAccessor ch = new InMemoryPlanChannelAccessor();
            TaskList list = new TaskList(ch);
            String id = list.createTask("plain done");
            list.startTask(id);
            list.completeTask(id);

            String out = new TaskListRenderer().render(ch.get());
            assertTrue(out.contains("- [x] plain done"), out);
            assertFalse(out.contains("— completed"), out);
        }

        @Test
        @DisplayName("orphan sub-tasks (parent missing) still render at root level")
        void orphanSubTasksSurfaced() {
            InMemoryPlanChannelAccessor ch = new InMemoryPlanChannelAccessor();
            TaskList list = new TaskList(ch);
            // Create a sub-task referencing a parent that doesn't exist
            list.createTask(new TaskItem("orphan", null, "missing-parent", java.util.Map.of()), null);

            String out = new TaskListRenderer().render(ch.get());
            assertTrue(out.contains("orphan"), out);
        }
    }
}
