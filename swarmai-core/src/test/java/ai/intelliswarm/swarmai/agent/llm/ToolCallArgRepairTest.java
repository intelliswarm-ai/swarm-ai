package ai.intelliswarm.swarmai.agent.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallArgRepairTest {

    @Test
    void trailingCommaInObjectIsStripped() {
        var r = ToolCallArgRepair.repair("{\"a\": 1, \"b\": 2,}");
        assertThat(r.changed()).isTrue();
        assertThat(r.text()).isEqualTo("{\"a\": 1, \"b\": 2}");
    }

    @Test
    void trailingCommaInArrayIsStripped() {
        var r = ToolCallArgRepair.repair("[1, 2, 3,]");
        assertThat(r.changed()).isTrue();
        assertThat(r.text()).isEqualTo("[1, 2, 3]");
    }

    @Test
    void unclosedObjectIsClosed() {
        var r = ToolCallArgRepair.repair("{\"a\": 1");
        assertThat(r.changed()).isTrue();
        assertThat(r.text()).isEqualTo("{\"a\": 1}");
    }

    @Test
    void unclosedNestedStructureIsClosed() {
        var r = ToolCallArgRepair.repair("{\"a\": [1, 2");
        assertThat(r.changed()).isTrue();
        assertThat(r.text()).isEqualTo("{\"a\": [1, 2]}");
    }

    @Test
    void pythonLiteralsConvertedOutsideStrings() {
        var r = ToolCallArgRepair.repair("{\"a\": True, \"b\": False, \"c\": None}");
        assertThat(r.changed()).isTrue();
        assertThat(r.text()).isEqualTo("{\"a\": true, \"b\": false, \"c\": null}");
    }

    @Test
    void pythonLiteralsInsideStringsPreserved() {
        // "True" inside a string is real data, not a literal — must not be touched
        var r = ToolCallArgRepair.repair("{\"a\": \"this is True\"}");
        assertThat(r.changed()).isFalse();
        assertThat(r.text()).isEqualTo("{\"a\": \"this is True\"}");
    }

    @Test
    void surrogateHalvesAreDropped() {
        // Lone high surrogate (no following low surrogate)
        String bad = "{\"x\":\"a\uD800b\"}";
        var r = ToolCallArgRepair.repair(bad);
        assertThat(r.changed()).isTrue();
        assertThat(r.text()).isEqualTo("{\"x\":\"ab\"}");
    }

    @Test
    void controlCharactersAreStripped() {
        String bad = "{\"x\": \"hi\"}";
        var r = ToolCallArgRepair.repair(bad);
        assertThat(r.changed()).isTrue();
        assertThat(r.text()).doesNotContain("");
    }

    @Test
    void wellFormedJsonIsUnchanged() {
        var r = ToolCallArgRepair.repair("{\"a\": 1, \"b\": [true, false, null]}");
        assertThat(r.changed()).isFalse();
        assertThat(r.text()).isEqualTo("{\"a\": 1, \"b\": [true, false, null]}");
    }

    @Test
    void nullAndEmptyInputArePassthrough() {
        assertThat(ToolCallArgRepair.repair(null).text()).isNull();
        assertThat(ToolCallArgRepair.repair("").text()).isEmpty();
    }

    @Test
    void wordBoundaryProtectsIdentifiers() {
        // "Truelove" should NOT become "truelove" — it's not the Python literal
        var r = ToolCallArgRepair.repair("{\"name\": \"Truelove\"}");
        assertThat(r.text()).isEqualTo("{\"name\": \"Truelove\"}");
    }
}
