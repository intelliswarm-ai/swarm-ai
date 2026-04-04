package ai.intelliswarm.swarmai.selfimproving;

import ai.intelliswarm.swarmai.selfimproving.model.WorkflowShape;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowShapeTest {

    @Test
    void shouldProduceFeatureMap() {
        WorkflowShape shape = new WorkflowShape(
                5, 3, true, true, false,
                Set.of("WEB", "DATA"), "SELF_IMPROVING", 3, 2.5, true, false
        );

        Map<String, Object> features = shape.toFeatureMap();

        assertEquals(5, features.get("task_count"));
        assertEquals(3, features.get("max_depth"));
        assertEquals(true, features.get("has_skill_gen"));
        assertEquals(true, features.get("has_parallel"));
        assertEquals(2, features.get("tool_category_count"));
        assertEquals("SELF_IMPROVING", features.get("process_type"));
        assertEquals(3, features.get("agent_count"));
        assertEquals(2.5, features.get("avg_tools_per_agent"));
        assertEquals(true, features.get("has_budget"));
        assertEquals(false, features.get("has_governance"));
    }

    @Test
    void shouldMatchExactCondition() {
        WorkflowShape shape = new WorkflowShape(
                3, 2, false, false, false,
                Set.of("WEB"), "SEQUENTIAL", 2, 1.0, true, false
        );

        assertTrue(shape.matches(Map.of("process_type", "SEQUENTIAL")));
        assertFalse(shape.matches(Map.of("process_type", "PARALLEL")));
    }

    @Test
    void shouldMatchThresholdCondition() {
        WorkflowShape shape = new WorkflowShape(
                3, 2, false, false, false,
                Set.of("WEB"), "SEQUENTIAL", 2, 1.0, true, false
        );

        assertTrue(shape.matches(Map.of("max_depth", "<=3")));
        assertTrue(shape.matches(Map.of("max_depth", "<=2")));
        assertFalse(shape.matches(Map.of("max_depth", "<=1")));
    }

    @Test
    void shouldMatchMultipleConditions() {
        WorkflowShape shape = new WorkflowShape(
                3, 2, false, false, false,
                Set.of("WEB"), "SEQUENTIAL", 2, 1.0, true, false
        );

        assertTrue(shape.matches(Map.of(
                "max_depth", "<=3",
                "has_skill_gen", false,
                "process_type", "SEQUENTIAL"
        )));

        assertFalse(shape.matches(Map.of(
                "max_depth", "<=3",
                "has_skill_gen", true  // doesn't match
        )));
    }

    @Test
    void shouldBeDomainAgnostic() {
        // WorkflowShape captures structure, not domain
        Map<String, Object> features = new WorkflowShape(
                3, 2, false, false, false,
                Set.of("WEB"), "SEQUENTIAL", 2, 1.0, true, false
        ).toFeatureMap();

        // No feature should contain domain-specific terms
        for (String key : features.keySet()) {
            assertFalse(key.contains("research"), "Feature key should not contain domain terms");
            assertFalse(key.contains("analysis"), "Feature key should not contain domain terms");
        }
    }
}
