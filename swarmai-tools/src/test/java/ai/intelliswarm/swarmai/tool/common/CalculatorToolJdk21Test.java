package ai.intelliswarm.swarmai.tool.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the JDK 21+ behaviour of {@link CalculatorTool} where no JavaScript
 * engine ships out of the box. Verifies the pure-Java fallback produces
 * canonical decimal strings that downstream {@code new BigDecimal(...)}
 * parsers can consume directly.
 */
class CalculatorToolJdk21Test {

    private final CalculatorTool calc = new CalculatorTool();

    @Test
    @DisplayName("integer result is rendered as a plain integer string")
    void integerResult_returnsPlainInteger() {
        assertEquals("36", calc.execute(Map.of("expression", "(120 + 24) / 4")));
    }

    @Test
    @DisplayName("decimal result is rendered without scientific notation")
    void decimalResult_returnsPlainDecimal() {
        Object out = calc.execute(Map.of("expression", "0.1 + 0.2"));
        assertTrue(out instanceof String);
        String s = (String) out;
        assertFalse(s.toLowerCase().contains("e"),
                "calculator output must be plain decimal, no scientific notation: " + s);
        BigDecimal parsed = new BigDecimal(s);
        assertTrue(parsed.compareTo(new BigDecimal("0.299")) > 0
                && parsed.compareTo(new BigDecimal("0.301")) < 0);
    }

    @Test
    @DisplayName("the showcase split-bill expression evaluates correctly")
    void showcaseExpression_splitBill() {
        assertEquals("36", calc.execute(Map.of("expression", "((120.0)+(24.0))/(4)")));
    }

    @Test
    @DisplayName("celsius→fahrenheit composition works end-to-end")
    void celsiusToFahrenheit() {
        assertEquals("212", calc.execute(Map.of("expression", "(100.0 * 9 / 5) + 32")));
    }

    @Test
    @DisplayName("modulo and unary minus parse correctly")
    void moduloAndUnaryMinus() {
        assertEquals("2", calc.execute(Map.of("expression", "17 % 5")));
        assertEquals("-7", calc.execute(Map.of("expression", "-(3 + 4)")));
    }

    @Test
    @DisplayName("division by zero returns a friendly error string")
    void divisionByZero() {
        Object out = calc.execute(Map.of("expression", "10 / 0"));
        assertTrue(out instanceof String);
        assertTrue(((String) out).startsWith("Error:"), "expected error string, got: " + out);
    }

    @Test
    @DisplayName("invalid characters still rejected by the input regex")
    void invalidCharactersStillRejected() {
        Object out = calc.execute(Map.of("expression", "sqrt(16)"));
        assertTrue(out instanceof String);
        assertTrue(((String) out).startsWith("Error: Invalid characters"),
                "expected regex rejection, got: " + out);
    }
}
