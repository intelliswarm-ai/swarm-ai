package ai.intelliswarm.swarmai.examples.stock.tools;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import org.springframework.stereotype.Component;

import java.util.HashMap;
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
            
            // Evaluate the expression
            Object result = engine.eval(operation);
            
            if (result instanceof Number) {
                Number num = (Number) result;
                // Return integer if it's a whole number, otherwise return decimal
                if (num.doubleValue() == num.intValue()) {
                    return String.valueOf(num.intValue());
                } else {
                    return String.valueOf(num.doubleValue());
                }
            }
            
            return String.valueOf(result);
            
        } catch (ScriptException e) {
            return "Error: Invalid mathematical expression - " + e.getMessage();
        } catch (Exception e) {
            return "Error: Calculation failed - " + e.getMessage();
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
    
    // Request record for Spring AI function binding
    public record Request(String expression) {}
}