package ai.intelliswarm.swarmai.dsl.compiler;

import ai.intelliswarm.swarmai.state.AgentState;

import java.util.Map;
import java.util.function.Predicate;

/**
 * Lightweight expression evaluator for graph conditional edges and task conditions.
 *
 * <p>Supported expressions:
 * <ul>
 *   <li>Numeric comparisons: {@code round < 3}, {@code score >= 80}</li>
 *   <li>String equality: {@code category == "BILLING"}</li>
 *   <li>Boolean checks: {@code approved == true}</li>
 *   <li>Logical operators: {@code score >= 80 || iteration >= 3}</li>
 *   <li>Grouping: {@code (approved == true) && (score > 50)}</li>
 * </ul>
 *
 * <pre>{@code
 * // Evaluate against AgentState
 * boolean result = ConditionEvaluator.evaluate("round < 3", state);
 *
 * // Create a Predicate<String> for task conditions
 * Predicate<String> pred = ConditionEvaluator.toPredicate("contains('risk')");
 * }</pre>
 */
public final class ConditionEvaluator {

    private ConditionEvaluator() {}

    /**
     * Evaluates a condition expression against the given state.
     */
    public static boolean evaluate(String expression, AgentState state) {
        return evaluate(expression, state.data());
    }

    /**
     * Evaluates a condition expression against a raw state map.
     */
    public static boolean evaluate(String expression, Map<String, Object> stateData) {
        Parser parser = new Parser(expression.trim(), stateData);
        boolean result = parser.parseOr();
        if (parser.pos < parser.input.length()) {
            throw new ConditionParseException("Unexpected characters at position " + parser.pos +
                    " in expression: " + expression);
        }
        return result;
    }

    /**
     * Creates a Predicate that tests context strings (for task conditions).
     */
    public static Predicate<String> toPredicate(String expression) {
        String trimmed = expression.trim();
        if (trimmed.startsWith("contains(")) {
            String arg = extractStringArg(trimmed, "contains");
            return ctx -> ctx != null && ctx.toLowerCase().contains(arg.toLowerCase());
        }
        throw new ConditionParseException("Unsupported task condition: " + expression +
                ". Supported: contains('...')");
    }

    /**
     * Validates that an expression can be parsed without errors.
     */
    public static void validate(String expression) {
        try {
            // Parse with empty state to check syntax
            Parser parser = new Parser(expression.trim(), Map.of());
            parser.parseOr();
        } catch (ConditionParseException e) {
            throw e;
        } catch (Exception e) {
            throw new ConditionParseException("Invalid expression: " + expression + " — " + e.getMessage());
        }
    }

    private static String extractStringArg(String expr, String funcName) {
        int start = expr.indexOf('(');
        int end = expr.lastIndexOf(')');
        if (start < 0 || end < 0 || end <= start) {
            throw new ConditionParseException("Malformed function call: " + expr);
        }
        String inner = expr.substring(start + 1, end).trim();
        // Remove quotes
        if ((inner.startsWith("'") && inner.endsWith("'")) ||
            (inner.startsWith("\"") && inner.endsWith("\""))) {
            return inner.substring(1, inner.length() - 1);
        }
        return inner;
    }

    // ========================================
    // Recursive-descent parser
    // ========================================

    private static class Parser {
        final String input;
        final Map<String, Object> state;
        int pos = 0;

        Parser(String input, Map<String, Object> state) {
            this.input = input;
            this.state = state;
        }

        // or = and ( "||" and )*
        boolean parseOr() {
            boolean left = parseAnd();
            while (match("||")) {
                boolean right = parseAnd();
                left = left || right;
            }
            return left;
        }

        // and = comparison ( "&&" comparison )*
        boolean parseAnd() {
            boolean left = parseComparison();
            while (match("&&")) {
                boolean right = parseComparison();
                left = left && right;
            }
            return left;
        }

        // comparison = value ( op value )?
        // or "(" or ")" grouping
        boolean parseComparison() {
            skipWhitespace();
            if (pos < input.length() && input.charAt(pos) == '(') {
                pos++; // consume '('
                boolean result = parseOr();
                skipWhitespace();
                if (pos < input.length() && input.charAt(pos) == ')') {
                    pos++; // consume ')'
                } else {
                    throw new ConditionParseException("Missing closing ')' in: " + input);
                }
                return result;
            }

            Object left = parseValue();
            skipWhitespace();

            String op = parseOperator();
            if (op == null) {
                // Single value — treat as boolean
                return toBoolean(left);
            }

            Object right = parseValue();
            return compare(left, op, right);
        }

        Object parseValue() {
            skipWhitespace();
            if (pos >= input.length()) {
                throw new ConditionParseException("Unexpected end of expression: " + input);
            }

            char c = input.charAt(pos);

            // String literal
            if (c == '"' || c == '\'') {
                return parseStringLiteral(c);
            }

            // Number
            if (Character.isDigit(c) || (c == '-' && pos + 1 < input.length() && Character.isDigit(input.charAt(pos + 1)))) {
                return parseNumber();
            }

            // Boolean literal or identifier
            String word = parseIdentifier();
            if ("true".equals(word)) return Boolean.TRUE;
            if ("false".equals(word)) return Boolean.FALSE;
            if ("null".equals(word)) return null;

            // State variable lookup
            return state.get(word);
        }

        String parseStringLiteral(char quote) {
            pos++; // consume opening quote
            StringBuilder sb = new StringBuilder();
            while (pos < input.length() && input.charAt(pos) != quote) {
                sb.append(input.charAt(pos));
                pos++;
            }
            if (pos < input.length()) pos++; // consume closing quote
            return sb.toString();
        }

        Number parseNumber() {
            int start = pos;
            if (input.charAt(pos) == '-') pos++;
            boolean isDecimal = false;
            while (pos < input.length() && (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.')) {
                if (input.charAt(pos) == '.') isDecimal = true;
                pos++;
            }
            String numStr = input.substring(start, pos);
            return isDecimal ? Double.parseDouble(numStr) : Long.parseLong(numStr);
        }

        String parseIdentifier() {
            int start = pos;
            while (pos < input.length() && (Character.isLetterOrDigit(input.charAt(pos)) || input.charAt(pos) == '_')) {
                pos++;
            }
            if (pos == start) {
                throw new ConditionParseException("Expected identifier at position " + pos + " in: " + input);
            }
            return input.substring(start, pos);
        }

        String parseOperator() {
            if (pos >= input.length()) return null;

            // Two-char operators
            if (pos + 1 < input.length()) {
                String two = input.substring(pos, pos + 2);
                if (two.equals("==") || two.equals("!=") || two.equals(">=") || two.equals("<=")) {
                    pos += 2;
                    return two;
                }
                // Check for || and && (handled at higher level, not here)
                if (two.equals("||") || two.equals("&&")) {
                    return null;
                }
            }

            // Single-char operators
            char c = input.charAt(pos);
            if (c == '<' || c == '>') {
                pos++;
                return String.valueOf(c);
            }

            return null;
        }

        boolean compare(Object left, String op, Object right) {
            // Null handling
            if (left == null && right == null) return "==".equals(op);
            if (left == null || right == null) return "!=".equals(op);

            // Numeric comparison
            if (left instanceof Number && right instanceof Number) {
                double l = ((Number) left).doubleValue();
                double r = ((Number) right).doubleValue();
                return switch (op) {
                    case "==" -> l == r;
                    case "!=" -> l != r;
                    case "<" -> l < r;
                    case "<=" -> l <= r;
                    case ">" -> l > r;
                    case ">=" -> l >= r;
                    default -> throw new ConditionParseException("Unknown operator: " + op);
                };
            }

            // Number vs non-number: try to parse left as number
            if (right instanceof Number && !(left instanceof Number)) {
                try {
                    double l = Double.parseDouble(String.valueOf(left));
                    double r = ((Number) right).doubleValue();
                    return switch (op) {
                        case "==" -> l == r;
                        case "!=" -> l != r;
                        case "<" -> l < r;
                        case "<=" -> l <= r;
                        case ">" -> l > r;
                        case ">=" -> l >= r;
                        default -> throw new ConditionParseException("Unknown operator: " + op);
                    };
                } catch (NumberFormatException ignored) {}
            }

            // String/Object equality
            String l = String.valueOf(left);
            String r = String.valueOf(right);
            return switch (op) {
                case "==" -> l.equals(r);
                case "!=" -> !l.equals(r);
                default -> throw new ConditionParseException(
                        "Operator '" + op + "' not supported for non-numeric comparison: " + l + " " + op + " " + r);
            };
        }

        boolean toBoolean(Object value) {
            if (value == null) return false;
            if (value instanceof Boolean b) return b;
            if (value instanceof Number n) return n.doubleValue() != 0;
            return !String.valueOf(value).isBlank();
        }

        boolean match(String token) {
            skipWhitespace();
            if (pos + token.length() <= input.length() &&
                input.substring(pos, pos + token.length()).equals(token)) {
                pos += token.length();
                return true;
            }
            return false;
        }

        void skipWhitespace() {
            while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
                pos++;
            }
        }
    }

    /**
     * Thrown when a condition expression cannot be parsed.
     */
    public static class ConditionParseException extends RuntimeException {
        public ConditionParseException(String message) {
            super(message);
        }
    }
}
