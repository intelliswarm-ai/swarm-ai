package ai.intelliswarm.swarmai.dsl;

import ai.intelliswarm.swarmai.dsl.compiler.ConditionEvaluator;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConditionEvaluatorTest {

    @Test
    void numericLessThan() {
        assertTrue(ConditionEvaluator.evaluate("round < 3", Map.of("round", 2L)));
        assertFalse(ConditionEvaluator.evaluate("round < 3", Map.of("round", 3L)));
        assertFalse(ConditionEvaluator.evaluate("round < 3", Map.of("round", 5L)));
    }

    @Test
    void numericGreaterEquals() {
        assertTrue(ConditionEvaluator.evaluate("score >= 80", Map.of("score", 80)));
        assertTrue(ConditionEvaluator.evaluate("score >= 80", Map.of("score", 95)));
        assertFalse(ConditionEvaluator.evaluate("score >= 80", Map.of("score", 79)));
    }

    @Test
    void stringEquals() {
        assertTrue(ConditionEvaluator.evaluate("category == \"BILLING\"",
                Map.of("category", "BILLING")));
        assertFalse(ConditionEvaluator.evaluate("category == \"BILLING\"",
                Map.of("category", "TECHNICAL")));
    }

    @Test
    void stringSingleQuotes() {
        assertTrue(ConditionEvaluator.evaluate("category == 'BILLING'",
                Map.of("category", "BILLING")));
    }

    @Test
    void booleanEquals() {
        assertTrue(ConditionEvaluator.evaluate("approved == true",
                Map.of("approved", true)));
        assertFalse(ConditionEvaluator.evaluate("approved == true",
                Map.of("approved", false)));
    }

    @Test
    void logicalOr() {
        assertTrue(ConditionEvaluator.evaluate("score >= 80 || iteration >= 3",
                Map.of("score", 85, "iteration", 1)));
        assertTrue(ConditionEvaluator.evaluate("score >= 80 || iteration >= 3",
                Map.of("score", 50, "iteration", 3)));
        assertFalse(ConditionEvaluator.evaluate("score >= 80 || iteration >= 3",
                Map.of("score", 50, "iteration", 1)));
    }

    @Test
    void logicalAnd() {
        assertTrue(ConditionEvaluator.evaluate("approved == true && score > 50",
                Map.of("approved", true, "score", 60)));
        assertFalse(ConditionEvaluator.evaluate("approved == true && score > 50",
                Map.of("approved", true, "score", 30)));
        assertFalse(ConditionEvaluator.evaluate("approved == true && score > 50",
                Map.of("approved", false, "score", 60)));
    }

    @Test
    void parentheses() {
        assertTrue(ConditionEvaluator.evaluate("(score >= 80) || (iteration >= 3)",
                Map.of("score", 85, "iteration", 1)));
    }

    @Test
    void notEquals() {
        assertTrue(ConditionEvaluator.evaluate("status != \"FAILED\"",
                Map.of("status", "SUCCESS")));
        assertFalse(ConditionEvaluator.evaluate("status != \"FAILED\"",
                Map.of("status", "FAILED")));
    }

    @Test
    void nullHandling() {
        // Missing state key → null
        assertFalse(ConditionEvaluator.evaluate("missing == true", Map.of()));
        assertTrue(ConditionEvaluator.evaluate("missing != true", Map.of()));
    }

    @Test
    void mixedNumericTypes() {
        // Integer state value vs Long literal
        assertTrue(ConditionEvaluator.evaluate("count < 5", Map.of("count", 3)));
        assertTrue(ConditionEvaluator.evaluate("count < 5", Map.of("count", 3L)));
    }

    @Test
    void validateAcceptsValidExpressions() {
        assertDoesNotThrow(() -> ConditionEvaluator.validate("round < 3"));
        assertDoesNotThrow(() -> ConditionEvaluator.validate("score >= 80 || iteration >= 3"));
        assertDoesNotThrow(() -> ConditionEvaluator.validate("category == \"BILLING\""));
    }

    @Test
    void toPredicateContains() {
        var pred = ConditionEvaluator.toPredicate("contains('risk')");
        assertTrue(pred.test("This report identifies a major risk factor"));
        assertFalse(pred.test("This report is all clear"));
    }

    @Test
    void toPredicateContainsCaseInsensitive() {
        var pred = ConditionEvaluator.toPredicate("contains('RISK')");
        assertTrue(pred.test("This has risk factors"));
    }

    @Test
    void toPredicateUnsupported() {
        assertThrows(ConditionEvaluator.ConditionParseException.class,
                () -> ConditionEvaluator.toPredicate("startsWith('abc')"));
    }
}
