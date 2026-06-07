package ai.intelliswarm.swarmai.tool.common;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

@Component
public class CalculatorTool implements BaseTool {

    private static final Pattern VALID_EXPRESSION = Pattern.compile("^[0-9+\\-*/().% ]+$");
    private final ScriptEngine engine;

    public CalculatorTool() {
        ScriptEngineManager manager = new ScriptEngineManager();
        this.engine = manager.getEngineByName("JavaScript");
    }

    @Override
    public String getFunctionName() {
        return "calculator";
    }

    @Override
    public String getDescription() {
        return "Useful to perform mathematical calculations like sum, minus, multiplication, division. Input should be a mathematical expression like '200*7' or '5000/2*10'";
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String operation = (String) parameters.get("expression");
        System.out.println("🧮 CalculatorTool: Executing calculation: " + operation);
        try {
            // Validate input contains only safe mathematical characters
            if (!VALID_EXPRESSION.matcher(operation.trim()).matches()) {
                return "Error: Invalid characters in mathematical expression. Only numbers and basic operators (+, -, *, /, %, parentheses) are allowed.";
            }

            // Prefer the JS engine when present (legacy Nashorn / GraalJS).
            // On JDK 21+ without GraalJS the engine is null, so we fall back
            // to a tiny pure-Java evaluator that supports the same operator
            // set the regex above already restricts the input to.
            Object result = engine != null ? engine.eval(operation) : null;
            if (result == null) {
                double computed = SimpleArithmetic.evaluate(operation);
                return formatNumber(computed);
            }

            if (result instanceof Number num) {
                return formatNumber(num.doubleValue());
            }

            return String.valueOf(result);

        } catch (ScriptException e) {
            return "Error: Invalid mathematical expression - " + e.getMessage();
        } catch (SimpleArithmetic.ParseException e) {
            return "Error: Invalid mathematical expression - " + e.getMessage();
        } catch (ArithmeticException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error: Calculation failed - " + e.getMessage();
        }
    }

    /**
     * Render a numeric result as a canonical decimal string — always plain,
     * never scientific notation, integer-typed when whole. Downstream callers
     * that parse with {@code new BigDecimal(...)} won't choke on exponent
     * forms produced by some script engines.
     */
    private static String formatNumber(double value) {
        if (Double.isNaN(value)) return "NaN";
        if (Double.isInfinite(value)) return value > 0 ? "Infinity" : "-Infinity";
        // Whole number → render as int when it fits.
        if (value == Math.floor(value) && Math.abs(value) < Long.MAX_VALUE) {
            return Long.toString((long) value);
        }
        // BigDecimal.valueOf(double) routes through Double.toString, which
        // we then plain-render to guarantee no E-notation slips through.
        return BigDecimal.valueOf(value).toPlainString();
    }

    /**
     * Tiny recursive-descent evaluator for the operator subset
     * {@code + - * / % ( )} plus decimal numbers and unary minus.
     * Used as a fallback when no JavaScript engine is on the classpath
     * (e.g. JDK 21+ without GraalJS).
     */
    static final class SimpleArithmetic {

        static final class ParseException extends RuntimeException {
            ParseException(String msg) { super(msg); }
        }

        private final String src;
        private int pos;

        private SimpleArithmetic(String src) { this.src = src; }

        static double evaluate(String expression) {
            SimpleArithmetic p = new SimpleArithmetic(expression);
            double result = p.parseExpression();
            p.skipWhitespace();
            if (p.pos < p.src.length()) {
                throw new ParseException("Unexpected character '" + p.src.charAt(p.pos)
                        + "' at position " + p.pos);
            }
            return result;
        }

        private double parseExpression() {
            double v = parseTerm();
            while (true) {
                skipWhitespace();
                if (peek('+')) { pos++; v += parseTerm(); }
                else if (peek('-')) { pos++; v -= parseTerm(); }
                else break;
            }
            return v;
        }

        private double parseTerm() {
            double v = parseFactor();
            while (true) {
                skipWhitespace();
                if (peek('*')) { pos++; v *= parseFactor(); }
                else if (peek('/')) {
                    pos++;
                    double denom = parseFactor();
                    if (denom == 0.0) throw new ArithmeticException("Division by zero");
                    v /= denom;
                }
                else if (peek('%')) {
                    pos++;
                    double denom = parseFactor();
                    if (denom == 0.0) throw new ArithmeticException("Modulo by zero");
                    v %= denom;
                }
                else break;
            }
            return v;
        }

        private double parseFactor() {
            skipWhitespace();
            if (peek('+')) { pos++; return parseFactor(); }
            if (peek('-')) { pos++; return -parseFactor(); }
            if (peek('(')) {
                pos++;
                double v = parseExpression();
                skipWhitespace();
                if (!peek(')')) throw new ParseException("Expected ')' at position " + pos);
                pos++;
                return v;
            }
            return parseNumber();
        }

        private double parseNumber() {
            skipWhitespace();
            int start = pos;
            while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
                pos++;
            }
            if (pos == start) {
                throw new ParseException("Expected a number at position " + pos);
            }
            try {
                return Double.parseDouble(src.substring(start, pos));
            } catch (NumberFormatException e) {
                throw new ParseException("Invalid number '" + src.substring(start, pos) + "'");
            }
        }

        private void skipWhitespace() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
        }

        private boolean peek(char c) {
            return pos < src.length() && src.charAt(pos) == c;
        }
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> expression = new HashMap<>();
        expression.put("type", "string");
        expression.put("description", "Mathematical expression to evaluate (e.g., '200*7', '5000/2*10')");
        properties.put("expression", expression);

        schema.put("properties", properties);
        schema.put("required", new String[]{"expression"});

        return schema;
    }

    @Override
    public boolean isAsync() {
        return false;
    }

    @Override
    public String getTriggerWhen() {
        return "User needs mathematical calculations: arithmetic, percentages, ratios, growth rates, financial formulas.";
    }

    @Override
    public String getAvoidWhen() {
        return "User asks for data retrieval, text analysis, web searches, or file operations that don't involve math.";
    }

    @Override
    public String getCategory() {
        return "computation";
    }

    @Override
    public List<String> getTags() {
        return List.of("math", "arithmetic", "finance", "calculation");
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return Map.of(
            "type", "string",
            "description", "Numeric result as a string (e.g., '42', '3.14')",
            "example", "42.0"
        );
    }

    // Request record for Spring AI function binding
    public record Request(String expression) {}
}
