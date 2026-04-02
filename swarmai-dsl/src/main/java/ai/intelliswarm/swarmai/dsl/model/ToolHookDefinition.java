package ai.intelliswarm.swarmai.dsl.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * YAML definition for a tool hook on an agent.
 *
 * <p>Supported built-in types:
 * <ul>
 *   <li>{@code audit} — logs every tool call with timestamp, tool name, and parameters</li>
 *   <li>{@code sanitize} — redacts patterns (e.g. emails, phone numbers) from tool output</li>
 *   <li>{@code rate-limit} — warns when tool calls exceed a threshold within a time window</li>
 *   <li>{@code deny} — blocks specific tools from being invoked</li>
 *   <li>{@code custom} — loads a custom ToolHook implementation by class name</li>
 * </ul>
 *
 * <pre>{@code
 * toolHooks:
 *   - type: audit
 *   - type: sanitize
 *     patterns:
 *       - "\\b[\\w.+-]+@[\\w-]+\\.[a-z]{2,}\\b"
 *   - type: rate-limit
 *     maxCalls: 10
 *     windowSeconds: 30
 *   - type: deny
 *     tools: [shell-command, file-write]
 *   - type: custom
 *     class: "com.example.MyCustomHook"
 * }</pre>
 */
public class ToolHookDefinition {

    private String type;

    private List<String> patterns = new ArrayList<>();

    @JsonProperty("maxCalls")
    private Integer maxCalls;

    @JsonProperty("windowSeconds")
    private Integer windowSeconds;

    private List<String> tools = new ArrayList<>();

    @JsonProperty("class")
    private String hookClass;

    // --- Getters & Setters ---

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public List<String> getPatterns() { return patterns; }
    public void setPatterns(List<String> patterns) { this.patterns = patterns; }

    public Integer getMaxCalls() { return maxCalls; }
    public void setMaxCalls(Integer maxCalls) { this.maxCalls = maxCalls; }

    public Integer getWindowSeconds() { return windowSeconds; }
    public void setWindowSeconds(Integer windowSeconds) { this.windowSeconds = windowSeconds; }

    public List<String> getTools() { return tools; }
    public void setTools(List<String> tools) { this.tools = tools; }

    public String getHookClass() { return hookClass; }
    public void setHookClass(String hookClass) { this.hookClass = hookClass; }
}
